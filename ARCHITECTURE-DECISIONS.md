# Architecture Decision Records

Multi-tenant Customer Experience (CX) + IT Service Management (ITSM) platform: `core-service`, `notification-service`, `gateway`,
`platform-events`, `frontend` (React), `frontend-angular` (Angular), `infra`. This is a self-built interview/learning project. It is not Freshworks code, and nothing in it
describes how Freshworks is built.

**How to read this file.** Each record has Context, Decision, Consequences (good and bad) and Alternatives. File paths are relative to
the repo root. "Implemented" means the code exists and a test or run in this repo exercised it. "Not executed" means the code exists
but could not run here, because Docker (PostgreSQL, Redis, RabbitMQ, MongoDB) is not installed on the authoring machine. See
`docs/core-service-validation.md` for the executed evidence.

| # | Decision | Status |
|---|---|---|
| 1 | Modular core plus split-ready services, not full microservices | Implemented |
| 2 | Shared database, `tenant_id` discriminator, Hibernate filter, composite FKs | Implemented, tested on H2 |
| 3 | PostgreSQL row-level security as optional defence in depth | Written, **not executed** |
| 4 | Tenant identity from signed JWT claim `tid`, never from client input | Implemented, tested |
| 5 | Stateless HS256 JWT with a DB-backed `jti` revocation list | Implemented, tested |
| 6 | Transactional outbox for domain events | Implemented, tested on H2 |
| 7 | At-least-once delivery with idempotent consumers | Implemented (processor tested; broker path not executed) |
| 8 | `Idempotency-Key` for ticket creation | Implemented, tested |
| 9 | Optimistic locking (`@Version`) for ticket edits | Implemented, tested |
| 10 | Tenant-keyed cache-aside with graceful degradation | Caffeine tested; Redis path tested with mocks only |
| 11 | `DocumentStore` abstraction: Mongo for flexible documents, PostgreSQL authoritative | See section below |
| 12 | Constrained workflow rules (data), not scripting | Implemented, tested |
| 13 | Mock AI provider by default, human approval before anything reaches a customer | See section below |
| 14 | Runtime profiles `local` and `docker` because Docker is unavailable | Implemented |
| 15 | Hand-written WebFlux gateway instead of Spring Cloud Gateway | Implemented, tested with stubs |
| 16 | TanStack Query with tenant-first keys; token in `sessionStorage` | Implemented, unit tested |
| 17 | Business-minute SLA clock; SLA job uses bulk updates without version bump | Implemented, tested |
| 18 | Two Flyway locations: portable SQL and PostgreSQL-only SQL | Implemented, PG location not executed |
| 19 | Bounded offset pagination, whitelisted sort | Implemented, tested |
| 20 | Notification service with its own tables, own Flyway history and a retry/DLQ topology | Implemented, broker path not executed |

---

## ADR-001: Modular core plus split-ready services, not full microservices

**Context.** The domain has strongly related parts: identity, tickets, workflow, SLA, analytics, audit, KB and AI assist. They share
one transactional boundary (a ticket change writes the ticket, an activity row, an audit row and an outbox row in one commit). The
team is one author, the goal is explainable architecture, and Docker is not available for local multi-service runs.

**Decision.** One deployable `core-service` organised in packages by capability (`com.example.cx.core.{tickets,workflow,sla,identity,
outbox,cache,...}`). Only the parts with a clearly different runtime profile are separate services: `notification-service` (event
consumer, fan-out, retry) and `gateway` (edge). Contracts between them are the event envelope
(`platform-events/src/main/java/com/example/cx/events/DomainEvent.java`) and the REST contract (`docs/API-CONTRACT.md`).
`notification-service` can also run embedded as a library (`NotificationAutoConfiguration`), proving the seam is real.

**Consequences.**
- (+) Cross-entity changes are one ACID transaction; no distributed transactions or sagas for the core flow.
- (+) One build, one deploy, simple local run (`scripts/run-local.sh`).
- (+) The split points are already enforced by interfaces (`EventPublisher`, `CacheService`, `DocumentStore`) and by the event contract.
- (-) Core scales as a unit: a hot analytics query and the ticket write path share one JVM and one Hikari pool (default max 10 per node,
  observed in `/actuator/prometheus` as `hikaricp_connections_max 10`).
- (-) Package boundaries are by convention. `ArchitectureRulesTest` enforces only the tenancy and entity-exposure rules, not a general
  module dependency graph.
- (+) Gap closed: `core-service/pom.xml` now depends on `notification-service` (its plain library jar; the standalone executable is the
  separate `exec`-classifier artifact, so Spring Boot repackaging does not conflict). `application-local.yml` sets
  `cx.notifications.embedded=true`, so in profile `local` the outbox relay delivers events to the embedded module and `/api/v1/notifications...`
  is served by core on 8081 (`EmbeddedNotificationsTest`). `application-docker.yml` sets it to `false`: in docker the separate
  notification-service consumes RabbitMQ. To keep the library jar from competing with the host config, the standalone app's config files are
  named `notification-service*.yml` (selected by `spring.config.name` in its `main`), not `application*.yml`.
- (-) The embedded listener runs synchronously on the relay thread, so a slow notification channel delays the relay in local mode only.

**Alternatives.**
1. Full microservices (tickets, workflow, SLA, identity as separate services): rejected. It adds network failure modes, distributed
   consistency and 4+ deployables for a single-author project, with no scaling need demonstrated.
2. Single monolith including notifications: rejected. Notification fan-out is the natural async consumer and benefits from independent
   retry, DLQ and scaling.
3. Modular monolith with enforced module boundaries (ArchUnit, Spring Modulith): a reasonable next step. Not done.

---

## ADR-002: Shared database, `tenant_id` discriminator, Hibernate filter, composite foreign keys

**Context.** Multi-tenant SaaS needs isolation with acceptable cost. Per-tenant databases isolate best but multiply operations
(migrations, pools, backups) by the tenant count. Per-tenant schemas have the same problem at smaller scale.

**Decision.** One database and one schema. Every tenant-owned table has `tenant_id uuid not null`. Isolation is layered:
1. Hibernate `@FilterDef/@Filter("tenantFilter")` (`tenancy/TenantOwned.java`) enabled on every transaction's session by
   `tenancy/TenantAwareJpaTransactionManager.java`.
2. Explicit tenant predicates in every repository (`findByIdAndTenantId`, `TicketSearch` always adds `tenantId` first), because
   Hibernate filters do **not** apply to `em.find`.
3. `@PrePersist` in `TenantOwned` refuses a cross-tenant write or a write without a tenant.
4. Composite foreign keys `(tenant_id, x_id)` in the Flyway migrations (for example `fk_ticket_requester` references
   `app_user (tenant_id, id)`), so the database rejects a ticket that points to another tenant's user or category.
5. Cache keys `t:{tenantId}:...` and `tenantId` on every event.
6. Optional PostgreSQL RLS (ADR-003).

**Consequences.**
- (+) One schema to migrate; cheap tenants; cross-tenant analytics for operators are possible.
- (+) Defence in depth: a forgotten predicate is caught by the filter (`TenancyTest`), a forged id returns 404 (`TenantIsolationTest`).
- (-) Noisy-neighbour risk: one large tenant shares indexes, pool and cache with all others. There is no per-tenant quota in the code.
- (-) Every index must lead with `tenant_id` (done in `V15__ticket_indexes.sql`), which a forgotten query can violate (see the plan
  evidence in `PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md`).
- (-) Native SQL and `JdbcTemplate` bypass the Hibernate filter. Today only RLS (not executed here) would catch that.
- (-) Tenant data cannot be moved or restored independently without tooling.

**Alternatives.** Database-per-tenant (strongest isolation, highest ops cost); schema-per-tenant (middle ground, Flyway per schema,
catalog bloat); a hybrid where large tenants get dedicated databases (the natural evolution, not built).

---

## ADR-003: PostgreSQL row-level security as optional defence in depth

**Context.** Application-layer filters can be bypassed by native queries or by a future developer. The database can enforce the same
predicate independently.

**Decision.** `core-service/src/main/resources/db/migration-pg/V1002__pg_row_level_security.sql` enables and forces RLS on the tenant
tables with policy `tenant_id = cx_current_tenant()`, where `cx_current_tenant()` reads `current_setting('app.tenant_id', true)`.
`TenantAwareJpaTransactionManager.doBegin` runs `select set_config('app.tenant_id', ?, true)` (the `true` makes it `SET LOCAL`, so
transaction-scoped). The flag is on only when the `docker` profile is active or `cx.tenancy.pg-rls=true`. V1011 extends the same
policy to the KB, AI and document tables. Deliberately not under RLS: `organization` (login resolves slug to tenant before a tenant is
known), `revoked_token` (no tenant column), `outbox_event` (the relay claims across tenants).

**Consequences.**
- (+) Fails closed: with no tenant set the function returns NULL and the policy matches no rows.
- (+) `SET LOCAL` makes pooling safe, as the value disappears at commit/rollback. A connection returned to Hikari carries no tenant.
- (-) **Never executed here.** `PostgresRlsDockerIT` is tagged `docker` and was not run. Treat this as a reviewed design, not a
  verified control.
- (-) Only transactions opened by the tenant-aware transaction manager are covered. Any DB access outside a transaction sees no tenant
  and returns zero rows (safe but surprising). The per-request `revoked_token` lookup in `TokenRevocationService.isRevoked` runs before
  the tenant is bound, which is fine because that table is not under RLS.
- (-) RLS does not apply to superusers or roles with `BYPASSRLS`. The app must connect as a dedicated non-superuser role, Flyway as the
  owner. The compose file uses one `POSTGRES_USER` (`cx_app`) for the database, which is the **owner**; `FORCE ROW LEVEL SECURITY`
  covers the owner, but a production setup should still separate the migration role from the runtime role.
- (-) Small overhead per transaction (one extra statement, `set_config`). Not measured here.

**Alternatives.** App-layer only (what the `local` profile effectively is); database-per-tenant; PostgreSQL session variables set per
connection checkout instead of per transaction (rejected, since a leaked variable on a pooled connection is the classic RLS bug).

---

## ADR-004: Tenant identity from the signed JWT claim, never from client input

**Context.** The cheapest multi-tenant mistake is trusting a tenant id in a header, path or body.

**Decision.** `security/JwtAuthFilter.java` verifies the token and calls `TenantContext.set(u.tenantId())` from the `tid` claim.
`TenantContext` (`tenancy/TenantContext.java`) is a ThreadLocal cleared in `finally` by both the filter and the outermost
`common/CorrelationIdFilter.java`. Login resolves the tenant by slug on the server (`AuthService.login`), which only selects whose
credentials to verify; the tenant used afterwards comes from the issued token. `ArchitectureRulesTest` fails the build if a class
outside an allow-list calls `TenantContext.set/callAs/runAs`, or if a controller has a parameter whose name contains "tenant", or
exposes an `@Entity`.

**Consequences.**
- (+) A forged `X-Tenant-Id` header or `?tenantId=` is ignored (tested in `TenantIsolationTest`).
- (+) Cross-tenant ids return 404, not 403, so existence is not leaked.
- (-) Switching tenant means logging in again. Users who belong to several tenants need one login per tenant.
- (-) Tenant suspension is not instant: a token stays valid until `exp` (60 minutes) unless individually revoked. There is no tenant-level
  kill switch in the code.
- (-) Thread-bound context does not follow `@Async`, virtual threads that hop, or reactive code. The core is blocking MVC, so this holds today.

**Alternatives.** Subdomain-based tenant resolution (good UX, needs DNS and TLS wildcard); tenant header validated against the token
(redundant); opaque tokens looked up server-side (instant revocation, a lookup per request).

---

## ADR-005: Stateless HS256 JWT with a DB-backed `jti` revocation list

**Context.** The SPA needs a simple bearer-token API. Logout should invalidate a token on all nodes.

**Decision.** `security/JwtService.java` issues HS256 tokens (jjwt 0.12.6) with `sub, tid, roles, email, jti, iss, iat, exp` and a
60-minute TTL. The secret must be at least 32 bytes. `StartupGuards` (docker profile) refuses to start with the development secret.
Logout writes the `jti` into `revoked_token` (`TokenRevocationService`), shared by all nodes; positive hits are cached in Caffeine
for 10 minutes. "Not revoked" results are cached for a short TTL (default 3 s, `cx.security.revocation-negative-cache-seconds`, 0 disables);
logout stores the positive entry and drops the negative one, so the revoking node rejects the token at once, and other nodes reject it after
at most the negative TTL. The gateway also verifies
signature and expiry with the same secret (`gateway/.../AuthFilter.java`) but leaves revocation, roles and tenant rules to core.

**Consequences.**
- (+) No session store; any node can authenticate any request.
- (-) The per-request `revoked_token` read is now served from a 3 s negative cache (`TokenRevocationCacheTest`), so a valid token costs about
  one indexed read per 3 s per node instead of one per request. The price is a bounded revocation delay of at most that TTL on nodes other than
  the one that handled the logout (single-node deployments, including compose, are unaffected). Set the TTL to 0 for strict semantics.
- (-) Symmetric key: every service that verifies tokens holds the signing secret (core, gateway, notification). A leak of any one
  allows forging tokens for any tenant. Asymmetric keys (RS256/EdDSA with JWKS) would limit verifiers to a public key.
- (-) No refresh token and no key rotation: the secret is a single environment variable. Changing it logs everyone out.
- (-) Deactivating a user does not revoke tokens already issued (documented in `core-service/README.md`).
- (-) The token lives in `sessionStorage` in the SPA (ADR-016), so XSS is the main risk.

**Alternatives.** Server-side sessions in Redis (instant revoke, stateful); OAuth2/OIDC with an external IdP (the right production
answer, out of scope); short-lived access plus refresh token with rotation.

---

## ADR-006: Transactional outbox for domain events

**Context.** A ticket change must produce a notification event. Publishing to RabbitMQ inside the DB transaction cannot be atomic
(dual write). Publishing after commit can lose the event if the process dies in between.

**Decision.** `outbox/OutboxService.publish` is `@Transactional(propagation = MANDATORY)`: it can only be called inside the business
transaction and inserts an `outbox_event` row (`V14__outbox_event.sql`). `outbox/OutboxRelay.java` polls every second (batch 50),
claims each due row with a compare-and-set UPDATE plus a lease (`OutboxRepository.claim`), publishes **outside** any DB transaction via
`EventPublisher`, then marks the row SENT. Failure: `attempts` is incremented at claim time; backoff is `2s * 2^(attempts-1)` capped at
300 s; after 8 attempts the row becomes FAILED. A crashed relay's lease (60 s) expires and another relay re-claims the row.
`LocalEventPublisher` (profile `!docker`) calls in-JVM `EventListener` beans; `RabbitEventPublisher` (profile `docker`) publishes to
topic exchange `cx.events` with persistent messages and publisher confirms.

**Consequences.**
- (+) The event is committed or rolled back with the business change (tested in `OutboxTest`).
- (+) The claim is a portable stand-in for `FOR UPDATE SKIP LOCKED`; exactly one of 6 concurrent claimers won in the test.
- (-) Delivery latency is bounded below by the poll interval (1 s default).
- (+) The table no longer grows without bound: `OutboxPurgeJob` (hourly) deletes SENT rows older than `cx.outbox.retention-days` (default 7) in
  bounded batches (500 per delete, at most 20 deletes per run); PENDING, PROCESSING and FAILED rows are never purged (`OutboxPurgeTest`).
- (-) Polling adds constant DB load even when idle. Change-data-capture (Debezium) or `LISTEN/NOTIFY` would remove the polling.
- (-) `outbox_event` is not under RLS and the relay runs without a tenant, by design.
- (-) FAILED rows have a metric (`cx_outbox_failed_total`) but no replay endpoint.

**Alternatives.** Publish after commit (can lose events); two-phase commit/XA (heavy, poor broker support); CDC from the WAL (best
at scale, needs Debezium and Kafka Connect or similar); Spring's `@TransactionalEventListener` (still loses events on crash).

---

## ADR-007: At-least-once delivery with idempotent consumers

**Context.** Exactly-once across a DB and a broker is not achievable cheaply. The relay can publish and then crash before marking SENT;
RabbitMQ can redeliver after a consumer crash.

**Decision.** The contract in `DomainEvent`/`EventListener` is at-least-once; consumers de-duplicate on `eventId`.
`notification-service/.../NotificationProcessor.java` claims `eventId` in `notif_processed_event` (primary key) first; a duplicate is a
no-op. Notifications and deliveries have unique keys `(event_id, user_id)` and `(event_id, user_id, channel)`, so a retry re-sends only
channels not yet SENT. Consumer errors: manual acks, republish to `cx.notifications.retry` (TTL queue) up to `max-attempts` (5), then
the DLX `cx.events.dlx` to `cx.notifications.dlq` (status DEAD). Poison messages (`NonRetryableException`) go straight to the DLQ.

**Consequences.**
- (+) Redelivery is harmless (`NotificationProcessorTest`, `NotificationMessageHandlerTest` with a fake channel).
- (-) Idempotency is per consumer. A new consumer must implement it again; nothing in the platform enforces it.
- (-) Ordering is not guaranteed across retries: a retried `ticket.assigned` can arrive after a later `ticket.status.changed`.
  Consumers must not assume order (payloads are self-contained).
- (-) The `processed_event` table grows without a purge job.
- (-) The real RabbitMQ topology and `NotificationRabbitDockerIT` were **not executed**.

**Alternatives.** Kafka with consumer offsets plus idempotent writes; exactly-once semantic via transactional consumer (Kafka only);
dedupe in Redis with TTL (lighter, loses dedupe after TTL).

---

## ADR-008: `Idempotency-Key` for ticket creation

**Context.** Browsers retry on timeouts and users double-click. A retried POST must not create two tickets.

**Decision.** `tickets/TicketCommandService.java` requires `Idempotency-Key` (`[A-Za-z0-9._:-]{1,100}`). The key is scoped to
(tenant, user) with a SHA-256 hash of the request body (`V11__idempotency_key.sql`, unique `(tenant_id, user_id, idem_key)`). The key row is
inserted in the same transaction as the ticket, so a failed creation never consumes the key. Same key and same body replays the stored
201 response (`Idempotent-Replay: true`); same key and different body gives 409 `DUPLICATE_SUBMISSION`. Concurrent same-key requests race
on the unique constraint; the loser rolls back and replays the winner. Keys older than 24 h are purged hourly per tenant.

**Consequences.**
- (+) Tested: 4 concurrent same-key creates produce 1 ticket.
- (-) The stored response is JSON up to 20 000 characters in the same table; a hot tenant adds write volume.
- (-) Replay returns the response as it was at creation time, not current ticket state.
- (-) Applies only to ticket creation. Comment creation is not idempotent: a retried comment POST creates a second comment.

**Alternatives.** Natural-key dedupe (title + requester + time window; fragile); client-generated ticket ids (PUT-to-create); Redis
`SETNX` key store (faster, loses atomicity with the DB insert).

---

## ADR-009: Optimistic locking for ticket edits

**Context.** Two agents can edit the same ticket concurrently. Pessimistic locks hold DB connections during user think time.

**Decision.** `tickets/Ticket.java` has a `version` column (`@Version`). Update, assign and transition requests carry `version`;
`TicketService.checkVersion` rejects a stale value with 409 `TICKET_VERSION_CONFLICT`, and Hibernate rejects a concurrent flush. The
SLA monitor uses bulk UPDATEs that do not touch `version`, so background work never causes spurious user conflicts. The UI shows a
"Reload latest" notice. Per-tenant numbering is allocated by `TicketNumberAllocator` (one atomic `UPDATE ticket_counter ... next_number + 1` in its own tiny
transaction, before the business transaction starts).

**Consequences.**
- (+) Tested: two parallel updates with the same version give exactly one 200 and one 409.
- (+) The per-tenant counter lock used to serialise ticket creation inside a tenant (the row stayed locked until the create transaction
  committed). A same-JVM experiment confirmed it (guide section 3.1): the same 10 clients spread over 3 tenants had p99 about 70 ms, one tenant
  p99 477-1033 ms and max up to 3.5 s (12.9 s at c=20). Allocating the number in its own short transaction brought single-tenant c=10 to p99 about 82 ms,
  max about 220 ms. (-) Numbers are unique and increasing but no longer gap-free: a create that fails after allocation burns a number.
- (-) The user must resolve the conflict by hand (no automatic merge).

**Alternatives.** Pessimistic `SELECT FOR UPDATE` on edit; last-write-wins (silent data loss); CRDT/merge (overkill); a sequence
per tenant (also allows gaps, and needs DDL per tenant). Keeping the in-transaction row lock was the previous choice (gap-free, but serialised).

---

## ADR-010: Tenant-keyed cache-aside with graceful degradation

**Context.** The manager dashboard aggregates many rows; categories, SLA policies, settings and the active workflow are read on
nearly every ticket write.

**Decision.** `cache/CacheService` is `getOrLoad(tenantId, key, ttl, type, loader)`, `evict`, `evictByPrefix`. Keys are
`t:{tenantId}:{key}`. Implementations: `CaffeineCacheService` (profile `!docker`, bounded by `cx.cache.max-entries`, per-entry TTL) and
`RedisCacheService` (profile `docker`, JSON values, +-10% TTL jitter). Both do per-node single-flight with a `CompletableFuture` per key.
Redis failure never fails a request: the loader result is returned, `cx_cache_fallback_total{op}` is counted, and a 5 s in-process
circuit breaker stops contacting Redis. Writes evict after commit via `common/TxHooks.afterCommit`. TTLs: dashboard 60 s, categories,
SLA policies, calendar settings and active workflow 5 min. Only DTOs/records are cached, never entities.

**Consequences.**
- (+) Measured locally (H2, Caffeine, single request, warm JVM, 3 753 tickets in the tenant): dashboard hit median 3.2 ms vs miss
  median 21.7 ms (15 and 30 samples; see the performance guide for caveats).
- (+) A dead Redis degrades to "slower", not "down" (tested with a mocked template; **not** with a real Redis).
- (-) Eviction on any ticket write makes the dashboard hit ratio depend on write rate. In a write-heavy load test the `analytics`
  cache hit ratio was 95.0% (31 983 hits / 1 697 misses).
- (-) Single-flight is per node: N nodes can load once each per TTL.
- (-) `evictByPrefix` on Redis uses `SCAN` with a pattern: O(keyspace). Fine for few keys per tenant, not at large scale.
- (-) A failed eviction after commit leaves stale data until TTL (at most 60 s for the dashboard, 5 min for the others).
- (-) Cache invalidation only covers writes through this service. Direct DB edits are invisible to the cache.

**Alternatives.** Read-through with Spring Cache annotations (hides tenant keys); write-through (couples writes to cache availability);
pre-computed rollup tables maintained from outbox events (the documented scale-up in `AnalyticsService`).

---

## ADR-011: `DocumentStore` abstraction: MongoDB for flexible documents, PostgreSQL authoritative

**Context.** Knowledge-base version snapshots and AI suggestion metadata (features, scores, prompt hash, provider details) are
schema-flexible, append-mostly and read by key or prefix. The core business state (ticket, article current content, suggestion status
and decision) needs transactions, foreign keys and tenant RLS.

**Decision.** `docstore/DocumentStore` is a tenant-scoped interface with `put/get/list/averageBy`. Implementations:
`JpaDocumentStore` (default, table `document_store`, V21), `InMemoryDocumentStore` (`cx.docstore=memory`, tests) and
`MongoDocumentStore` (`cx.docstore=mongo`, one collection `cx_documents`, `tenantId` in every query, three indexes created at startup,
`$match/$group/$avg` aggregation). The relational tables stay the source of truth: `kb_article` holds current content (V20), `ai_suggestion` holds
status, ticket, decision and the suggestion fields (V22). Documents are secondary: `KbService` writes one immutable snapshot per committed
article change to collection `kb_article_version` (key `{articleId}:{version zero-padded to 8}`), and `AiAssistService` writes diagnostics (prompt hash, features, scores, redaction
counts, provider attempts and latency) to collection `ai_suggestion_meta` keyed by suggestion id; a failed metadata write is logged and counted (`cx_ai_meta_failed_total`) and never
fails the user-visible result. V1011 applies RLS to `document_store` too. Selection: `cx.docstore` defaults to `jpa` in `application.yml`; `application-docker.yml` sets `mongo` and
requires `CX_MONGO_URI` (no default because it carries credentials).

**Consequences.**
- (+) The default JPA implementation joins the caller's transaction, so a KB snapshot commits atomically with the article edit. Mongo
  cannot offer that, so a Mongo snapshot written inside a transaction that later rolls back would be orphaned. This is why Mongo holds
  only non-authoritative data.
- (+) Local and CI runs need no MongoDB.
- (-) The Mongo path is **not executed** here (no Docker); `MongoDocumentStoreDockerIT` exists but did not run.
- (-) Configuration gap found by reading (not by running): `infra/docker-compose.yml` passes no `CX_MONGO_URI` to `core-service` and `env.example` does not define it, so the docker
  profile would be expected to fail at startup on the unresolved `${CX_MONGO_URI}` placeholder until that variable is added. Likewise `env.example` lists `CX_AI_API_KEY` and
  `CX_AI_API_BASE_URL`, while the code reads `cx.ai.http.api-key` / `cx.ai.http.url` (env `AI_API_KEY`, `AI_API_URL`).
- (-) Two stores means two backup and monitoring stories and no cross-store tenant RLS (Mongo isolation is application-enforced only).
- (-) `JpaDocumentStore` limits a document to 32 000 characters and its `averageBy` folds documents in the JVM (bounded).

**Alternatives.** PostgreSQL `jsonb` for everything (simplest, transactional, RLS applies; the honest default if no Mongo skill or
need exists); Elasticsearch for KB search (better relevance, another system); object storage for snapshots.

---

## ADR-012: Constrained workflow rules (data), not scripting

**Context.** Tenants want automation: route, re-prioritise, tag, escalate, notify. Allowing scripts or expression languages (SpEL,
Groovy, JavaScript) in tenant-controlled configuration is remote code execution waiting to happen.

**Decision.** A workflow is versioned JSON data: `rules[{name, when[{field, op, value}], then[{action, params}]}]` plus
`transitions[{from, to}]`. `workflow/WorkflowValidator.java` allows 10 fields, 6 operators (`EQ NE GT LT IN CONTAINS`) and 5 actions
(`ASSIGN_TO_ROLE_LEAST_LOADED SET_PRIORITY ESCALATE NOTIFY ADD_TAG`), type-checks values per field, rejects expression markers
(`${ #{ T( {{ <%`), rejects unknown params, and caps sizes (50 rules, 10 conditions, 10 actions, 60 transitions). `WorkflowEngine`
is a plain `switch` interpreter, runs once per trigger (create and each transition) and stops at 20 actions, so loops are impossible.
`WorkflowService` falls back to the built-in default (no rules, contract transitions) if the active definition is missing or no
longer validates, and counts `cx_workflow_fallback_total{reason}`.

**Consequences.**
- (+) No code execution surface; every definition is validated before it is stored; versions are immutable; activation is audited.
- (+) A corrupt definition cannot block ticket handling (tested).
- (-) Less expressive: no AND/OR nesting beyond "all conditions must match", no time-based triggers, no custom actions, no webhooks.
- (-) Rules are evaluated synchronously inside the ticket transaction, so a large workflow adds latency to every create/transition (bounded by the caps).
- (-) `ASSIGN_TO_ROLE_LEAST_LOADED` queries open-ticket counts per candidate inside that transaction.

**Alternatives.** Embedded scripting (flexible, unsafe without a sandbox); BPMN engine (heavy); rule engine (Drools; heavy and hard to explain).

---

## ADR-013: Mock AI provider by default, human approval before anything reaches a customer

**Context.** AI-drafted replies carry accuracy, privacy and liability risk. The project must run without any API key or network.

**Decision (design contract).** `docs/API-CONTRACT.md` defines `POST /tickets/{id}/ai/analyze` returning a suggestion with
`provider: "MOCK_RULE_BASED"`, `status: "PENDING_REVIEW"`, a `confidence`, an `explanation` and recommended KB articles. A suggestion
reaches a customer only through `POST /ai/suggestions/{id}/approve` (optionally with an edited reply), which posts the text as a comment via
`TicketService.addComment(..., aiGenerated=true)`. Provider failure or timeout returns HTTP 200 with `degraded: true` and no reply text.
`env.example` sets `CX_AI_PROVIDER=mock`, with empty `CX_AI_API_KEY` and `CX_AI_API_BASE_URL`. Input is sanitised and PII-redacted before it
leaves the service (diagram `docs/diagrams/09-ai-suggestion-flow.mmd`). The `ai.suggestion.decided` event type is reserved for the decision.

**Implementation (read from `core-service/src/main/java/com/example/cx/core/ai` at the end of this documentation pass).**
- Provider port `AiProvider` (pure: no DB, tenant or action access). Default `MockRuleBasedAiProvider` (`cx.ai.provider=mock`, `matchIfMissing`): deterministic keyword rules, explicitly
  not machine learning; "confidence" is rule-match strength, not a probability. Optional `HttpAiProvider` (`cx.ai.provider=http`): https-only, no redirects, fails closed when unconfigured; never executed.
- `AiSanitizer` cleans input (NFKC, control/zero-width removal, tag stripping, instruction-like sentences dropped, secrets and PII patterns redacted, length bounds) and output
  (tags and URLs removed). The service validates provider output against server-side candidate lists (unknown categories, article ids, priorities are dropped).
- Resilience: bounded pool (4 workers, 16 queue), per-call timeout 3 s, up to 2 retries (hard cap 3), 10 s total deadline, per-tenant limit 30 per minute per node, circuit breaker
  (5 consecutive failures, 30 s open, single trial). Failures return HTTP 200 `degraded: true`. The provider call is outside any DB transaction.
- Human gate: `AiDecisionService.approve` flips `PENDING_REVIEW` to `APPROVED`/`EDITED_APPROVED` with a compare-and-set UPDATE (second decision gets 409), posts exactly one PUBLIC comment through
  `TicketService.addComment(..., aiGenerated=true)`, writes audit and the `ai.suggestion.decided` outbox event in the same transaction, and evicts the dashboard cache (the AI acceptance KPI changes).
- Tests: `ai/AiApiTest`, `AiResilienceUnitTest`, `AiBreakerAndProviderSelectionTest`, `AiSanitizerTest`, `MockRuleBasedAiProviderTest`, `HttpProviderSelectedTest`, `AiArchitectureTest`. They are part of the
  179-test core-service run recorded in `docs/core-service-validation.md` (2026-10-17, 0 failures).

**Consequences.**
- (+) Deterministic, offline, testable behaviour; no data leaves the system by default.
- (+) The human is the control point; the `ai_generated` comment flag and activity entry make AI involvement auditable.
- (-) A rule-based mock says nothing about the quality, latency or cost of a real model. No such measurement exists in this repo.
- (-) Human approval caps throughput: it is a review queue, not automation.
- (-) Prompt-injection risk is real once a real provider is enabled, because ticket text is attacker-controlled. Mitigation is the human gate, not a guarantee.

**Alternatives.** Auto-send high-confidence replies (rejected: liability); no AI (misses the product direction); provider-specific SDK
(couples the core to a vendor).

---

## ADR-014: Runtime profiles `local` and `docker`, because Docker is unavailable here

**Context.** The authoring machine cannot run PostgreSQL, Redis, RabbitMQ or MongoDB, but the design targets them.

**Decision.** The same code runs in two profiles through interfaces and `@Profile`/`@ConditionalOnProperty`:

| | `local` (default) | `docker` |
|---|---|---|
| DB | H2 in PostgreSQL mode | PostgreSQL 16 |
| Flyway | `db/migration` | `db/migration` and `db/migration-pg` |
| Cache | Caffeine | Redis |
| Events | in-JVM `EventListener` | RabbitMQ `cx.events` |
| Document store | JPA table (`cx.docstore: jpa`) | MongoDB (`cx.docstore: mongo`, `CX_MONGO_URI` required) |
| RLS | off | on |

Docker-only tests are tagged `docker` and excluded by default (`excluded.groups`), run with `-Pdocker-tests`. Secrets have no defaults in
the docker profile and `StartupGuards` refuses the dev JWT secret.

**Consequences.**
- (+) 179 core-service tests (plus 46 notification, 14 gateway, 53 dsa-lab) and a 57-check HTTP smoke test run on a laptop in minutes.
- (-) **The production-like path is the least verified path.** RLS, Redis, RabbitMQ, Mongo, `db/migration-pg` and the compose stack were
  never executed. H2 PostgreSQL mode is not PostgreSQL (different planner, locking, types and dialect behaviour; a null-ordering
  difference already caused one real defect, see validation record item 4).
- (-) Local numbers (H2, Caffeine, no network hops) say little about production latency.

**Alternatives.** Testcontainers-only (needs Docker); an embedded PostgreSQL (zonky/otj) could give real PG semantics without Docker, not tried here.

---

## ADR-015: Hand-written WebFlux gateway instead of Spring Cloud Gateway

**Decision and rationale** are recorded in `docs/gateway-notes.md`. Summary: three routes and four concerns (correlation id, edge JWT
check, in-memory token-bucket rate limit, streaming proxy with 503/504 mapping). Chosen for explainability and to avoid the Spring Cloud
release-train coupling. Filters are ordered by `getOrder`: correlation, auth (+20), rate limit (+30), proxy (+40).

**Consequences.**
- (+) Roughly 300 lines, 14 tests with JDK `HttpServer` stubs.
- (-) No retries, circuit breaker, Redis-backed rate limit, websocket proxy or dynamic routes. The rate limit is per instance: N
  replicas allow N times the configured rate.
- (-) The gateway holds the shared JWT secret (blast radius, see ADR-005).
- (-) Upstream timeout 504 is implemented (30 s response timeout, 2 s connect) but **not covered by a test** (stated in the notes).
- (-) Server-side core still re-validates everything; the gateway is not a security boundary on its own.

**Alternatives.** Spring Cloud Gateway; Envoy/Kong/cloud API gateway (the production answer when quotas per key, WAF, mTLS are needed); nginx only.

---

## ADR-016: TanStack Query with tenant-first keys; token in `sessionStorage`

**Context.** The SPA must show server state, handle staleness and never show tenant A's cached data to tenant B after a re-login.

**Decision.** TanStack Query for server state (`frontend/src/lib/queryClient.ts`: `staleTime` 15 s, no refetch on window focus, one retry
for non-4xx). Every key starts `['t', tenantId, ...]` (`lib/queryKeys.ts`) and the cache is cleared on logout. Filters, sort and page live in
the URL. Forms use React Hook Form with Zod and map server `fieldErrors` back. Create-ticket reuses one `Idempotency-Key` per form mount.
The JWT is in memory and mirrored to `sessionStorage` (never `localStorage`). Every page is `React.lazy`; vendor manual chunks `react`,
`query`, `forms`.

**Angular twin.** `frontend-angular/` is a second, feature-equivalent UI (Angular 20, signals, `Loadable` instead of TanStack Query, `HttpClient` interceptors, typed reactive forms)
against the same API and gateway; it keeps the same trade-offs (JWT in memory + `sessionStorage`, tenant reset on identity change). See `frontend-angular/README.md` and `docs/frontend-angular-notes.md`.

**Consequences.**
- (+) Bundle: entry path loads about 104 kB gzip for `/login` (react 53.7 + forms 26.4 + query 12.5 + index 11.6, from `frontend/README.md`
  and re-checked against `frontend/dist/assets`).
- (+) Optimistic UI only for notification read marks, with rollback; everything else waits for the server.
- (-) `sessionStorage` is readable by any script on the origin (XSS). HttpOnly cookies would need CSRF handling and backend support.
- (-) Notification polling every 30 s per open tab is load that scales with tabs, not users.
- (-) Staleness up to 15 s on lists.

**Alternatives.** Redux Toolkit Query (similar; heavier); SWR (lighter, fewer features); in-memory-only token plus silent refresh via HttpOnly cookie (better; needs backend work).

---

## ADR-017: Business-minute SLA clock; SLA job uses bulk updates without version bump

**Decision.** `sla/BusinessCalendar.java` computes due dates in business minutes in the tenant's time zone, hours and days (DST-safe).
`PENDING` and `ON_HOLD` pause the clock; resume shifts due dates by the business minutes paused. `SlaMonitorJob` runs every 60 s, iterates tenants
(binding each tenant itself), examines at most 200 open tickets per tenant (least recently checked first, `coalesce(sla_checked_at, epoch)`
instead of `NULLS FIRST` because null ordering differs between databases), escalates when thresholds are crossed and flags a breach once.
The percent of the SLA budget used (which drives AT_RISK and the escalation thresholds) is measured in the same unit as the due dates: business minutes in the
tenant calendar, `(target - businessMinutesBetween(now, due)) / target`, so closed hours consume nothing and paused time is excluded (the due date was shifted
on resume). Example: a HIGH ticket (480 business-minute target) created Friday 15:30 has used 90/480 = 18.75% on Sunday 21:30 and is not AT_RISK
(`SlaCalculatorTest.closedHoursDoNotConsumeBudget_fridayEveningTicketIsNotAtRiskOnSunday`). An earlier version used wall-clock time and would have shown 75% there; fixed.

**Consequences.** (+) Idempotent; overlapping runs are redundant but safe. (+) No spurious 409s. (-) No distributed lock (every node runs the
job; ShedLock suggested in code comments). (-) A tenant with more than 200 open tickets is covered by rotation, so breach detection latency
grows with open-ticket count: roughly `ceil(open / 200)` minutes worst case (arithmetic, not measured). (-) One slow tenant scan delays
the next tenants because tenants are scanned sequentially on a single scheduler thread.

**Alternatives.** Per-ticket delayed messages (precise, more infrastructure); database-computed `now() > due` evaluated at query time (no
escalation events); quartz clustering.

---

## ADR-018: Two Flyway locations: portable SQL and PostgreSQL-only SQL

**Decision.** `db/migration` (V1..V15, V20..V22) must run on H2-PostgreSQL mode **and** PostgreSQL. `db/migration-pg` (V1001..V1003, V1010,
V1011) holds PostgreSQL-only features (partial index, GIN full-text, trigram, RLS, audit trigger) and is added only under the `docker`
profile. Version numbers leave gaps on purpose (V16-V19 free) and the PG-only series starts at 1001 so the two sets never collide.
Tests run Hibernate with `ddl-auto: validate`, so an entity/schema mismatch fails the build.

**Consequences.** (+) Local and CI use the real migrations. (-) The PG-only set is not executed. (-) Flyway orders by version across both locations, so a
PG-only file that depends on a portable table needs a higher number (true: 1001+ > 22). (-) Portable SQL was only proven on H2; the first
PostgreSQL run may find type or syntax differences (stated in the validation record).

**Alternatives.** Maintain separate schemas per DB (drift risk); Liquibase with `dbms` conditions; use PostgreSQL everywhere via a zero-Docker embedded PG.

---

## ADR-019: Bounded offset pagination, whitelisted sort

**Decision.** `common/PageParams.java`: `size` 1..100 (default 20), `page >= 0`, sort field from a whitelist plus `id` tie-break. Responses
carry `totalElements` and `totalPages`.

**Consequences.** (+) Unbounded queries are impossible by construction; stable ordering. (-) `OFFSET` cost grows with page number and the
`count(*)` runs on every page request; `page` itself is not capped. On H2 with 50 000 rows in one tenant, an offset-40000 page scanned 50 001
index entries while a keyset form of the same page scanned 10 001 (see the guide; H2 plan evidence, not PostgreSQL). (-) The contract
exposes page numbers, so switching to keyset would be a contract change.

**Alternatives.** Keyset/cursor pagination (better for deep pages, no random access); search-engine pagination.

---

## ADR-020: Notification service with its own tables, own Flyway history and a retry/DLQ topology

**Decision.** `notification-service` owns tables prefixed `notif_` (`V1__notif_init.sql`) with `tenant_id` on every row, a separate Flyway
history table `notif_schema_history`, and its own REST (`/api/v1/notifications...`) authorised from the JWT (`tid`, `sub`). In the compose
file it points at the same PostgreSQL instance and database as core (`POSTGRES_DB` defaults to `cx`), so "own data" is a naming and
ownership convention, not a separate database. Topology: `cx.events` (topic) to `cx.notifications` (bound with `ticket.#` and `ai.#`);
failures go to `cx.notifications.retry` (TTL queue) and back; exhausted or poison messages go via `cx.events.dlx` to `cx.notifications.dlq`.

**Consequences.** (+) Independent scaling and retry policy; DLQ keeps poison messages inspectable. (-) A shared database is a hidden
coupling (one outage, one connection budget, one migration lock domain). (-) `notif_*` tables are not under RLS; isolation relies on
queries keyed by tenant and user from the JWT. (-) Email and other channels are simulated (EMAIL only records SENT and logs). (+)
`notification-service-docker.yml` has no fallback credentials any more: it reads `CX_DB_URL/USER/PASSWORD`, `CX_RABBIT_*` and `CX_JWT_SECRET`
(the same names as core) and startup fails when one is missing. (+) Before creating notifications the processor checks that recipients are
users of the event's tenant (`JdbcRecipientDirectory` over `app_user`, same database) and that a payload `tenantId` equals the envelope's
(`RecipientValidationTest`); on PostgreSQL this binds `app.tenant_id` per transaction for RLS and is **not executed** here. Where `app_user` is
not reachable (a standalone service with its own database) only the payload check applies and the broker is trusted for recipient ids.

**Alternatives.** Database per service (clean ownership, more ops); Kafka instead of RabbitMQ (replay and ordering per key, heavier).

---

## Decisions deliberately not taken

- No service mesh, no mTLS between services, no distributed tracing backend (correlation id in logs and headers instead).
- No per-tenant rate limits or quotas, no tenant-level kill switch.
- No search engine; search is `LIKE` plus a keyword scorer for KB (portable), with PG-only GIN/trigram indexes prepared but not executed.
- No file storage: attachments are metadata only (`ticket_attachment`, size cap 25 MiB in the check constraint, no bytes accepted).
