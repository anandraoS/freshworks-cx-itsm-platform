# Low-Level Design

Companion to `docs/HLD.md`. Paths are relative to the repo root; `core/` means
`core-service/src/main/java/com/example/cx/core/`. Status tags: `[RUN]` exercised by tests or the local H2 run, `[CODE]` exists but needs
Docker to run and was not executed.

---

## 1. Request lifecycle (what runs, in order)

Servlet filter order in core (`docs/diagrams/18-request-lifecycle.mmd` is the picture):

1. `core/common/CorrelationIdFilter` (`@Order(HIGHEST_PRECEDENCE)`): accepts `X-Correlation-Id` only if it matches `^[A-Za-z0-9._-]{1,64}$`, else generates a UUID; puts it in
   the MDC and the response header; on the way out logs `METHOD path -> status in Nms` (no body, headers or query) and **clears `TenantContext` and the MDC**.
2. Spring Security filter chain (`core/security/SecurityConfig`): CORS, stateless sessions, CSRF/basic/form login disabled, `JwtAuthFilter` before
   `UsernamePasswordAuthenticationFilter`.
3. `core/security/JwtAuthFilter`: verify token (`JwtService.verify`), check `TokenRevocationService.isRevoked(jti)`, set the `SecurityContext` (authorities `ROLE_<role>`),
   `TenantContext.set(tid)`, `MDC userId`. In `finally` it clears all three. An invalid or revoked token is **not** an error here: the context stays empty and
   `authorizeHttpRequests` then returns 401 through the entry point.
4. Method security `@PreAuthorize("hasRole('AGENT')")` etc. using `RoleHierarchy` (`ADMIN > MANAGER > AGENT`).
5. Controller: binds DTO (Jakarta Validation), calls a service. Controllers never take a tenant parameter (enforced by `ArchitectureRulesTest`).
6. Service method with `@Transactional`: `TenantAwareJpaTransactionManager` creates the EntityManager, enables the `tenantFilter` with the bound tenant, and (docker profile)
   runs `set_config('app.tenant_id', ?, true)`.
7. `GlobalExceptionHandler` maps `ApiException`, validation, `DataIntegrityViolation`, unsupported method (405) and unsupported media type (415) and unknown errors to the contract error body; unknown errors become 500 `INTERNAL_ERROR`
   without a stack trace (`server.error.include-stacktrace: never`).

Gateway (WebFlux) order: `CorrelationIdFilter` then `AuthFilter` (HIGHEST+20) then `RateLimitFilter` (+30) then `ProxyFilter` (+40).

---

## 2. Key classes and responsibilities

### 2.1 Tenancy and security (`core/tenancy`, `core/security`)

| Class | Responsibility | SOLID note |
|---|---|---|
| `tenancy/TenantContext` | ThreadLocal holder with `current()`, `require()`, `set`, `clear`, `callAs`, `runAs` (restores the previous binding) | Single reason to change: how the current tenant is stored. Static access is a deliberate trade for brevity; the cost is that unit tests must clear it |
| `tenancy/TenantOwned` | `@MappedSuperclass` with `tenant_id`, the filter definition, and the `@PrePersist` guard | Open/closed: every tenant-owned entity inherits isolation by extending it |
| `tenancy/TenantAwareJpaTransactionManager` | The single choke point that binds tenancy to persistence for web, scheduler and listener transactions | Single place to audit; replacing Boot's `JpaTransactionManager` (via `TenancyConfig`) keeps services unaware of RLS |
| `tenancy/TenancyConfig` | Chooses `pgRls = pg-rls flag or profile docker` | Config separated from behaviour |
| `security/JwtService` | Issue/verify HS256 tokens; builds `AuthUser` | Depends on `CxProperties`, not on web types, so it is reused by tests |
| `security/JwtAuthFilter` | Adapts a bearer token into `SecurityContext` plus `TenantContext` | One job; clears in `finally` |
| `security/TokenRevocationService` | DB-backed `jti` revocation plus positive Caffeine cache and a short (3 s) "not revoked" cache; hourly purge of expired rows | Hides storage behind two methods (`revoke`, `isRevoked`) |
| `security/AuthUser`, `Role`, `CurrentUser` | Immutable principal record; role enum with `isStaff()`; static accessor | `AuthUser.isStaff()` centralises the "customer vs staff" rule used in `TicketService` |
| `security/LoginRateLimiter` | Token bucket per `ip:` and `acct:` key; Caffeine bounded to 10 000 keys, idle expiry 30 min | Constructor with injectable clock (`LongSupplier`) makes it testable without sleeping |
| `config/StartupGuards` | `@Profile("docker")` fail-fast on the dev secret | Fail closed at boot, not at first request |

### 2.2 Tickets (`core/tickets`)

| Class | Responsibility |
|---|---|
| `TicketController` | HTTP mapping, `@PreAuthorize`, `Idempotency-Key` header, delegates; returns DTO records only |
| `TicketCommandService` | Idempotency wrapper around creation: key validation, request hash, replay, race recovery; owns the transaction boundary via `TransactionTemplate` |
| `TicketService` | Use cases: `search`, `get`, `create`, `update`, `assign`, `transition`, `addComment`, `listComments`, `activity`. Every method derives tenant and actor itself |
| `TicketMapper` | Entity to DTO with batch lookups (users IN, categories IN, attachments IN): constant statement count per page (N+1 guard) |
| `TicketSearch` | Builds the JPA `Specification`; tenant predicate first; customers constrained to own requester id; `LIKE` escape; priority sort by CASE rank |
| `TicketStateMachine` | Pure transition table with `canTransition(table, from, to)`; default table = contract; the active workflow can supply another table |
| `TicketEvents` | Builds event payloads and calls `OutboxService.publish`; also an extension point (`base(...)`) for the AI module |
| `TicketActivityService`, `AuditService` | Append activity and audit rows in the caller's transaction |
| `Ticket`, `TicketComment`, ... | JPA entities extending `TenantOwned`; ids are plain `UUID` columns (no `@ManyToOne`), which removes lazy-loading surprises and makes the batch mapper necessary |

Create-ticket sequence (`TicketCommandService.create` then `TicketService.create`):
```
validate Idempotency-Key -> hash(request)
loop (max 2): existing key? -> replay(hash match ? stored 201 : 409 DUPLICATE_SUBMISSION)
  tx.begin
    TicketService.create:
       validate category (tenant-scoped)        -> number was pre-allocated by TicketNumberAllocator before tx.begin (atomic UPDATE, own tiny tx)
       sla.applyDueDates / refreshStatus        -> engine.apply(workflow rules) -> tickets.save
       attachments.save* -> activity.record -> handleApplied (workflow activity + events)
       events.created -> outbox.publish (MANDATORY tx)  -> audit.record -> TxHooks.afterCommit(evict analytics:)
    idempotency_key insert + flush   (unique violation => whole tx rolls back => loop replays winner)
  tx.commit
```

### 2.3 Outbox and events (`core/outbox`, `platform-events`)

| Class | Responsibility |
|---|---|
| `OutboxService.publish` | Only way to emit events; `Propagation.MANDATORY`; wraps payload in `DomainEvent(schemaVersion=1)`; increments `cx.outbox.enqueued` |
| `OutboxEvent`, `OutboxRepository` | Row with status/lease/attempts; `findDueIds` (PENDING due, or PROCESSING with expired lease), `claim` compare-and-set UPDATE |
| `OutboxRelay` | `@Scheduled` poll; `relayOnce()` claims, deserialises, calls `EventPublisher.publish` **outside** a DB transaction, marks SENT or schedules retry/FAILED |
| `EventPublisher` (interface) | Delivery port. `LocalEventPublisher` (`@Profile("!docker")`) and `RabbitEventPublisher` (`@Profile("docker")`, confirms with a 5 s timeout) |
| `platform-events/DomainEvent`, `EventListener` | The shared contract and the in-process subscription port |

State machine of one outbox row:
```
PENDING --claim--> PROCESSING --publish ok--> SENT
   ^                   |  publish fails, attempts < 8: back to PENDING, next_attempt_at = now + min(300, 2 * 2^(attempts-1)) s
   |                   |  attempts >= 8 or unreadable payload: FAILED
   +-- lease expiry (locked_until < now) makes a PROCESSING row claimable again (crashed relay)
```
`attempts` is incremented by `claim`, so `attempts` counts claims. With `max-attempts = 8` a row is tried at most 8 times.

### 2.4 Cache (`core/cache`)

`CacheService` (port) with `CaffeineCacheService` and `RedisCacheService` (adapters). Both implement single-flight with a per-key `CompletableFuture`
**outside** any map lock so a loader that uses the cache again (nested keys) cannot deadlock. Metric label `cache` is the key prefix up to the first colon
(`analytics`, `categories`, `sla`, `settings`, `workflow`). Liskov: callers must work when the cache returns nothing (Redis down), which the Redis adapter proves by
falling back to the loader. Interface segregation: three methods only.

### 2.5 Workflow and SLA (`core/workflow`, `core/sla`)

| Class | Responsibility |
|---|---|
| `WorkflowValidator` | Pure static validation of the rule model (field/operator/action allow-lists, value typing, size caps, expression markers rejected, transitions reachable `NEW` to `RESOLVED`) |
| `WorkflowEngine` | Interpreter: `matches` (all conditions) then `execute` (switch over 5 actions); cap 20 actions per run; a failing action is logged and skipped |
| `WorkflowService` | Versioned CRUD, single active version, cached active definition, safe fallback with `cx.workflow.fallback{reason}` |
| `SlaCalculator`, `BusinessCalendar` | Pure functions for due dates, status, escalation thresholds, DST-safe business minutes; percent used is measured in business minutes (`target - businessMinutesBetween(now, due)`) |
| `SlaService` | Applies due dates, pause/resume, `scanCurrentTenant(batch)` using bulk `updateSlaState` (no version bump) |
| `SlaMonitorJob` | Tenant-iterating scheduler (binds each tenant, bounded batch, per-tenant error isolation, `cx.sla.job.runs/errors`) |

### 2.6 Notification service (`notification-service/src/main/java/com/example/cx/notification`)

| Class | Responsibility |
|---|---|
| `NotificationProcessor` | Transport-agnostic: claim `eventId`, resolve recipients, render, create notification and delivery rows, send per channel, mark DONE; throws `NotificationDeliveryException` after trying all channels |
| `store/NotificationStore` | JDBC persistence; `claim` returns `NEW`, `RESUME` or `DUPLICATE`; every query tenant-scoped |
| `channel/ChannelSender` (+ `InAppChannelSender`, `EmailChannelSender`) | Strategy per channel; adding SMS means a new class, no change to the processor (open/closed) |
| `RetryPolicy` | Pure decision: `RETRY` with delay or `DEAD_LETTER` (`NonRetryableException` or attempts exhausted) |
| `rabbit/NotificationMessageHandler` | Manual-ack listener: ack on success or duplicate; republish with `x-attempt` header to the retry queue and wait for the publisher confirm, then ack; DLQ via reject without requeue |
| `rabbit/NotificationRabbitConfiguration` | Declares exchange, queues, bindings (`ticket.#`, `ai.#`), listener container (manual ack, prefetch, concurrency) |
| `InProcessEventListener` | Adapter implementing `com.example.cx.events.EventListener` for embedded mode |
| `security/JwtAuthInterceptor`, `JwtVerifier` | REST authentication; tenant and user from `tid` and `sub` only |
| `NotificationAutoConfiguration` | Registers the module when `cx.notifications.embedded=true`, enabling library use by a host application |

Processing stages (each its own short transaction): claim, create rows idempotently, send unsent deliveries, complete. A retry re-enters at "send unsent".

### 2.7 Gateway (`gateway/src/main/java/com/example/cx/gateway`)

`AuthFilter` (verify signature/expiry; store subject; public-route allow-list; 401 `UNAUTHENTICATED`), `RateLimitFilter` with `TokenBucketLimiter` (key `u:<sub>` or
`ip:<peer>`; 429 plus `Retry-After`; metric `gateway.rate.limited`), `ProxyFilter` (route by prefix; strips hop-by-hop headers; replaces `X-Forwarded-For` with the
socket peer; streams request and response; metrics `gateway.proxied{route,status}` and `gateway.upstream.errors{route,type}`; maps connect failure to 503 and read timeout to 504),
`CorrelationIdFilter`, `JsonErrorWebExceptionHandler`/`ErrorResponses` (contract-shaped errors), `GatewayProperties`/`RoutesProperties` (typed config).

### 2.8 Frontend (`frontend/src`, React; the Angular twin in `frontend-angular/src/app` is described in `frontend-angular/README.md`)

| Module | Responsibility |
|---|---|
| `api/client.ts` | One `request()` wrapper: base URL, bearer token, fresh `X-Correlation-Id`, JSON, maps error bodies to `ApiError{status, code, kind, fieldErrors, correlationId}`, global 401 handler |
| `api/endpoints.ts`, `api/types.ts` | Typed functions and contract types |
| `auth/AuthContext`, `tokenStore`, `Guards`, `roles` | Session state, `sessionStorage` mirror, `RequireAuth`, `RequireRole` (UI only; the server enforces) |
| `lib/queryKeys.ts`, `queryClient.ts` | Tenant-first keys, retry policy, `staleTime` 15 s |
| `lib/schemas.ts`, `formErrors.ts` | Zod schemas and server `fieldErrors` mapping |
| `lib/ticketFilters.ts` | URL query string to filter state and back |
| `hooks/useNotifications.ts` | 30 s polling with optimistic read-mark and rollback |
| `components/ErrorBoundary.tsx`, `AiPanel.tsx`, `ui/*` | Failure containment, AI suggestion UI (pending/degraded/approved), accessible primitives |
| `pages/*` | Lazy route components |

---

## 3. SOLID and pattern reasoning (specific, not generic)

- **Single responsibility.** `JwtService` signs and verifies; `JwtAuthFilter` adapts HTTP to it; `TokenRevocationService` stores revocations. Splitting them let
  `AuthApiTest` forge expired/`alg=none` tokens against `JwtService` alone.
- **Open/closed.** New cross-cutting tenant isolation for a new entity is "extend `TenantOwned`". New notification channel is "add a `ChannelSender`". New workflow action is
  one `case` in `WorkflowEngine` plus one in `WorkflowValidator` (the validator and the engine are the two places that must agree, which is a weakness: there is no
  shared enum, so they can drift; `WorkflowValidatorTest` and `WorkflowApiTest` guard it).
- **Liskov.** Every `CacheService` implementation may return a freshly loaded value at any time; every `EventPublisher` may throw to request retry; every
  `EventListener` must be idempotent. Callers rely only on those contracts.
- **Interface segregation.** `CacheService` (3 methods), `EventPublisher` (1), `EventListener` (1, functional), `DocumentStore` (4), `ChannelSender` (2).
- **Dependency inversion.** Services depend on ports (`CacheService`, `EventPublisher`, `DocumentStore`, `AnalyticsService.AiAcceptanceProvider`), and Spring picks adapters by
  profile or property. This is what makes the `local` profile credible without Docker, and also what makes the `docker` adapters easy to test with mocks only (which is
  all that was done for Redis).
- **Patterns used.** Transactional outbox, idempotent receiver, cache-aside with single-flight, strategy (channels, publishers), state machine (tickets), interpreter (workflow),
  token bucket, optimistic locking, compare-and-set claim with lease, composite (tenant-safe) foreign keys, circuit breaker (minimal, in `RedisCacheService`).
- **Deliberate deviations.** Static `TenantContext`/`CurrentUser` instead of injected holders (brevity over purity); entities use raw UUID columns instead of associations (avoids N+1,
  costs a batch mapper); `AuditService` uses `EntityManager` criteria queries because exposing `JpaSpecificationExecutor` would have leaked `delete(Specification)` (a real defect found
  by `ArchitectureRulesTest`).

---

## 4. Authentication vs tenant resolution vs authorization vs data isolation (class view)

```
request ──> JwtAuthFilter ──────────────────────────────────────────────────────────────────────────────┐
             AUTHENTICATION:  JwtService.verify (signature, iss, exp) + TokenRevocationService.isRevoked   │
             TENANT RESOLUTION: TenantContext.set(claims.tid)                                              │
controller ─> @PreAuthorize  ── AUTHORIZATION (role hierarchy)                                             │
service ────> CurrentUser.isStaff() / requester check ── AUTHORIZATION (object level, 404 on mismatch)    │
repository ─> findByIdAndTenantId + tenantFilter + @PrePersist + composite FK + RLS ── DATA ISOLATION     ◄┘
```
Each layer is independently testable: `AuthApiTest` (authN), `TenantIsolationTest` and `ArchitectureRulesTest` (tenant resolution and isolation), `RoleMatrixTest` (authZ),
`TenancyTest` (filter and persist guard), `PostgresRlsDockerIT` (RLS, `[CODE]`).

Why 404 and not 403 for another tenant's id: a 403 confirms the id exists. `TicketService.loadVisible` returns `ApiException.notFound` both for a wrong tenant and, for
customers, for another requester's ticket.

### RLS in the class model
`TenantAwareJpaTransactionManager.doBegin` is the only code that touches `app.tenant_id`. It uses `Session.doWork` so the statement runs on the same JDBC connection as the transaction.
If `TenantContext` is empty it does nothing, so the policy sees NULL and fails closed. Because the manager is the only place, adding a `JdbcTemplate` call outside a transaction would
bypass both the Hibernate filter (no filter on JDBC) and RLS (no tenant set): it would return zero rows under RLS, and all tenants' rows without RLS. This is the main residual risk.

---

## 5. Concurrency control inventory

| Place | Mechanism | Why |
|---|---|---|
| Ticket edit/assign/transition | `@Version` and request `version` check | Detect lost updates without locks |
| Ticket numbering | `TicketNumberAllocator` (atomic counter UPDATE in its own short transaction, before the create transaction) | Unique, increasing numbers; gaps possible; no long-held row lock |
| Idempotent create | Unique `(tenant_id, user_id, idem_key)` plus one retry loop | Serialise concurrent duplicates |
| Outbox claim | `UPDATE ... WHERE id=? AND (PENDING and due OR PROCESSING and lease expired)`, updated-row count must be 1 | Multi-relay safety without `SKIP LOCKED` |
| Cache fill | Per-key `CompletableFuture` in `ConcurrentHashMap.putIfAbsent` | Stampede control per node |
| SLA job | Idempotent bulk updates; no leader lock | Overlap is safe, just wasteful |
| Notification consume | `notif_processed_event` primary key claim, unique delivery keys | Duplicate delivery safe |
| Login limiter | `synchronized` per bucket, Caffeine bounded map | Thread-safe, bounded memory |
| Workflow activation | Transaction flips the active flag across the tenant's versions | One active version |

---

## 6. Error handling

`common/ApiException` carries an `ErrorCode`; `GlobalExceptionHandler` emits the contract body with `correlationId` from the MDC. `ErrorWriter` writes the same shape from filters
(401/403 from Spring Security, 429). `RateLimitedException` adds `Retry-After`. `Redaction.redact` masks password, token, key and webhook values before audit details are stored.
Unknown exceptions never expose messages (`include-message: never`). The frontend maps codes to kinds in `mapKind` and shows messages from `describeError` only.

---

## 7. Configuration surface (`CxProperties`, prefix `cx`)

`security.jwt.{secret,ttl-minutes=60,issuer=cx-core-service}`, `security.login-rate.{capacity=10,refill-per-minute=5,max-tracked-keys=10000}`, `security.trust-forwarded-for=false`,
`cors.allowed-origins`, `seed.enabled`, `outbox.{batch-size=50,max-attempts=8,poll-ms=1000,lock-seconds=60,backoff-base-seconds=2,relay-enabled=true}`,
`sla.{job-enabled,job-interval-ms=60000,batch-per-tenant=200,at-risk-percent=75}`, `cache.{max-entries=10000,redis-failure-backoff-seconds=5}`, `tenancy.pg-rls`.
Docker adds `CX_DB_URL/USER/PASSWORD`, `CX_REDIS_*`, `CX_RABBIT_*`, `CX_JWT_SECRET` (no defaults for secrets). Not set anywhere: Hikari pool size (default 10),
Tomcat thread pool (default 200), server request size limits, `percentiles-histogram`.

---

## 8. Testing map (core-service, 179 tests, `docs/core-service-validation.md`)

Unit: `TicketStateMachineTest`, `WorkflowValidatorTest`, `BusinessCalendarTest`, `SlaCalculatorTest`, `LoginRateLimiterTest`, `CacheServiceTest`.
Integration on H2 with MockMvc: `TicketApiTest`, `TenantIsolationTest`, `RoleMatrixTest`, `AuthApiTest`, `WorkflowApiTest`, `SlaJobTest`, `OutboxTest`, `CacheIntegrationTest`,
`NPlusOneGuardTest`, `SeedDataTest`, `PlatformEndpointsTest`. Architecture: `ArchitectureRulesTest`. Docker-only, **not executed**: `PostgresRlsDockerIT`, `RabbitRedisDockerIT`.
Also `ErrorContractTest` (415 / 405 contract errors) and `AnalyticsTimeZoneTest` (tenant-zone trend buckets); `SlaCalculatorTest` (12) covers business-minute percent used.
Whole backend run (2026-10-17): core-service 179, notification-service 46 (processor, retry policy, templates, API, message handler with a fake channel), gateway 14, dsa-lab 53, 0 failures.
Frontends: React Vitest 86 (10 files); Angular Karma 83; Playwright React e2e 17 run / 17 passed (16 passed + 1 skipped without the fault-injected second stack); Angular real-backend smoke 25/25 steps (`docs/e2e-validation.md`, `frontend-angular/README.md`).

---

## 9. KB, document store and AI classes

Read from the source tree at the end of this pass (the code was still being finished by another agent; the tests for these packages were not run by me).

| Class | Responsibility | Design note |
|---|---|---|
| `core/kb/KbController` | REST for articles and article categories; `@PreAuthorize("hasRole('AGENT')")` on writes, versions and links; reads open to any authenticated user | Visibility is **not** decided here |
| `core/kb/KbService` | Use cases; customer visibility (PUBLISHED + PUBLIC only), optimistic `version` check on update, snapshot per change, cache-aside for categories (5 min) and published pages (60 s), audit rows | Uses `TransactionTemplate` read transactions for reads and `@Transactional` for writes |
| `core/kb/KbSearchScorer` | Pure functions: tokenise, light stem, score (title 5, summary 3, body up to 5, phrase bonus) | Deterministic and unit-testable; keeps search portable across H2 and PostgreSQL |
| `core/kb/KbArticle`, `KbCategory`, `KbArticleTicket` (+ repositories) | Tenant-owned entities (`TenantOwned`), explicit `findByIdAndTenantId` style finders | Same isolation recipe as tickets |
| `core/kb/KbCandidate` | Narrow record handed to the AI module (`KbService.publishedCandidates`) | The AI package depends on a read-model, not on KB entities |
| `core/kb/KbDemoSeeder` | Seed articles for demo tenants | Only active when seeding is on |
| `core/docstore/DocumentStore` | Port: `put`, `get`, `list(prefix)`, `averageBy`; tenant is an explicit argument | Small interface, three adapters |
| `JpaDocumentStore`, `InMemoryDocumentStore`, `MongoDocumentStore` | Adapters selected by `cx.docstore` (`jpa` default, `memory`, `mongo`) via `@ConditionalOnProperty` | Liskov: all return only the calling tenant's documents; `MongoDocumentStore` cannot join the SQL transaction |
| `core/ai/AiController` | `@PreAuthorize("hasRole('AGENT')")` on the whole class; analyze, list suggestions, approve, reject | Controller has no business logic |
| `core/ai/AiAssistService` | Orchestrates analyze: load, rate limit, sanitise, provider call (bounded pool, timeout, retries, breaker), persist, audit, degrade | Provider call deliberately outside transactions; every failure becomes an `Outcome` and then a degraded DTO |
| `core/ai/AiDecisionService` | Human approval gate; compare-and-set status change; posts the comment through `TicketService.addComment`; audit and outbox event in one transaction | The only path from a suggestion to customer-visible text |
| `core/ai/AiProvider` (+ `MockRuleBasedAiProvider`, `HttpAiProvider`) | Port plus two adapters selected by `cx.ai.provider`; provider receives only sanitised text and bounded candidate lists | Dependency inversion: the core never imports a vendor SDK |
| `core/ai/AiSanitizer` | Static, pure input and output cleaning | Easy to test with hostile strings (`AiSanitizerTest`) |
| `core/ai/AiSettings`, `AiCircuitBreaker`, `AiRateLimiter`, `AiFaultInjector` | Bounded configuration, breaker, per-tenant fixed-window limiter, test-only fault hook | Each class has one job; settings clamp every knob to a safe range |
| `core/ai/AiSuggestion`, `AiSuggestionRepository` | Entity and repository with atomic `decide(...)` UPDATE and `countDecided(...)` | The `WHERE status = PENDING_REVIEW` clause is the concurrency control |
| `core/ai/AiAcceptanceMetrics` | Implements `AnalyticsService.AiAcceptanceProvider` for the dashboard KPI | Extension point from the core used as intended (README table) |

Approve sequence:
```
POST /ai/suggestions/{id}/approve  (AGENT+)
  tx.begin
    load suggestion + ticket by (id, tenant)      -> 404 if foreign
    status must be PENDING_REVIEW                  -> else 409
    finalText = editedReply or suggestedReply      -> 400 if empty
    repo.decide(... PENDING_REVIEW -> APPROVED | EDITED_APPROVED)  must update 1 row  -> else 409
    TicketService.addComment(ticket, finalText, PUBLIC, aiGenerated=true)   (authorisation, first-response SLA, activity, ticket.commented event)
    repo.attachComment; audit AI_SUGGESTION_*; outbox ai.suggestion.decided; afterCommit evict analytics:
  tx.commit      (a failure anywhere rolls the status flip back too)
```
Note that approval also triggers the normal `ticket.commented` event, so approving produces two events: `ticket.commented` and `ai.suggestion.decided`.

## 10. KB and AI: what is and is not verified

Verified by reading: structure above, constraints in V20-V22, RLS statement in V1011, `cx.docstore` and `cx.ai.*` defaults. Not verified: that the tests in `core-service/src/test/java/com/example/cx/core/{ai,kb,docstore}` pass in the final tree;
any Mongo behaviour (`MongoDocumentStoreDockerIT` needs Docker); the HTTP AI provider; PostgreSQL full-text and trigram indexes; that the docker profile starts (the compose file does not pass `CX_MONGO_URI`, see `ARCHITECTURE-DECISIONS.md` ADR-011).
