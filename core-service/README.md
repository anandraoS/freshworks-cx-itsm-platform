# core-service

Multi-tenant Customer Experience + ITSM backend (Spring Boot 3.3.5, Java 21). Port **8081**, API under `/api/v1/**`
(exactly as in `docs/API-CONTRACT.md`), reached through the gateway on 8080.

It owns: identity and tenancy, tickets, workflow + SLA, the transactional outbox and event relay, caching, analytics, the
**knowledge base** (`com.example.cx.core.kb`) and **AI-assisted support** (`com.example.cx.core.ai`, a deterministic *rule-based* mock by default - not machine
learning). Storage split: `docs/kb-storage-notes.md`; safety model: `docs/ai-safety-notes.md`.

## Run

```bash
# from the repo root (JDK 21 path and Maven wrapper path per docs/ARCH-CONSTRAINTS.md)
export JAVA_HOME=C:/Users/sahukari.rao/.jdks/graalvm-jdk-21.0.7
MVN="C:/Users/sahukari.rao/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn.cmd"
"$MVN" -pl core-service -am test                       # 179 tests, docker-tagged ones (7) excluded
"$MVN" -pl core-service -am -DskipTests package
"$JAVA_HOME/bin/java.exe" -jar core-service/target/core-service-1.0.0.jar     # profile "local", port 8081, seeded
node core-service/scripts/smoke-test.mjs                 # 57 HTTP checks incl. KB + AI against the running app (no curl)
```

Swagger UI: `http://localhost:8081/swagger-ui.html`, OpenAPI: `/v3/api-docs`, health: `/actuator/health`,
`/actuator/health/readiness`, metrics: `/actuator/prometheus`.

Seed logins (password `Passw0rd!demo`, synthetic): `acme-retail` (admin@, manager@, agent1@, agent2@, customer1@acme.test),
`globex-it` (admin@, agent1@, customer1@globex.test), `initech-health` (admin@, agent1@initech.test).
Tenants have 150 / 100 / 60 deterministic synthetic tickets over the last 45 days with comments and activity.

## Profiles

| | `local` (default) | `docker` |
|---|---|---|
| DB | H2 in-memory, PostgreSQL mode | PostgreSQL (`CX_DB_URL`, `CX_DB_USER`, `CX_DB_PASSWORD`) |
| Flyway | `db/migration` | `db/migration` + `db/migration-pg` (partial/GIN indexes, RLS, audit trigger) |
| Cache | Caffeine (`CaffeineCacheService`) | Redis (`RedisCacheService`, falls back to the loader when Redis fails) |
| Events | `LocalEventPublisher` -> in-JVM `com.example.cx.events.EventListener` beans (the **embedded notification module**, `cx.notifications.embedded=true`) | `RabbitEventPublisher` (exchange `cx.events`, persistent, publisher confirms) -> the separate notification-service |
| Notifications API | served by core itself on 8081 (`/api/v1/notifications...`, the gateway's notifications route defaults to core) | served by notification-service on 8082 (gateway `NOTIFICATIONS_URI`) |
| Document store (KB versions, AI metadata) | `JpaDocumentStore` (table `document_store`) | `MongoDocumentStore` (`CX_MONGO_URI`) |
| AI provider | `MockRuleBasedAiProvider` | same default; `AI_PROVIDER=http` + `AI_API_URL` / `AI_API_KEY` enables the optional stub |
| Seeding | on (3 tenants: tickets + 12 KB articles each) | on (`CX_SEED_ENABLED`) |

`docker` needs: `CX_JWT_SECRET` (the app **refuses** to start with the dev default), `CX_DB_PASSWORD`, `CX_RABBIT_USER`,
`CX_RABBIT_PASSWORD`, `CX_MONGO_URI` (carries the credentials; no default); optional `CX_REDIS_*`, `CX_LOGIN_RATE_CAPACITY`, `CX_LOGIN_RATE_REFILL_PER_MIN`, and the AI settings `AI_PROVIDER` / `AI_API_URL` / `AI_API_KEY` (aliases `CX_AI_PROVIDER` / `CX_AI_API_BASE_URL` / `CX_AI_API_KEY`). No secret has a default. The same `CX_DB_*` / `CX_RABBIT_*` / `CX_JWT_SECRET` names are used by notification-service and by `infra/docker-compose.yml` (which derives them from `env.example`).

**Embedded notifications (local).** `core-service` depends on the plain `notification-service` library jar (the standalone executable is the separate `exec` classifier artifact, so there is no Spring Boot repackage conflict).
`application-local.yml` sets `cx.notifications.embedded=true`; `application-docker.yml` sets it to `false` (notifications run in their own container and read RabbitMQ). The module brings its own Flyway location
(`classpath:db/notification-migration`, history table `notif_schema_history`), `notif_*` tables, `cx...`-prefixed bean names and a controller advice scoped to its own controller, so it does not collide with core's
schema, beans or error handling (`EmbeddedNotificationsTest`).
The `local` JWT secret is a clearly dummy value (`dev-only-NOT-A-REAL-SECRET...`). The docker profile has **not been executed**
here (no Docker) - see `docs/core-service-validation.md`.

## Security model

* JWT HS256 (jjwt 0.12.6): `sub`=userId, `tid`=tenantId, `roles`, `email`, `jti`, `iss`, `exp` (60 min). Logout stores the `jti` in
  `revoked_token` (shared by all nodes, purged after expiry). Revoked ids are cached 10 min; "not revoked" results are cached for 3 s (`cx.security.revocation-negative-cache-seconds`, 0 = off) so the per-request lookup is
  not a query per request: logout is immediate on the node that handled it and takes effect on other nodes within that TTL. Wrong signature / expired / `alg=none` / tampered payload -> 401.
* **TenantContext** (ThreadLocal) is set ONLY by `JwtAuthFilter` from the verified token, by `AuthService` / `TenantProvisioner`
  (server-side slug resolution, new tenant id) and by system jobs that iterate tenants themselves (`SlaMonitorJob`, idempotency purge,
  seeder). `ArchitectureRulesTest` fails the build if any other class binds a tenant, if a controller takes a tenant parameter, or if a
  controller exposes an `@Entity`. Client headers/params such as `X-Tenant-Id` are ignored (tested).
* Roles `CUSTOMER`, `AGENT`, `MANAGER`, `ADMIN`; hierarchy ADMIN > MANAGER > AGENT (CUSTOMER is a restriction, outside the hierarchy);
  `@PreAuthorize` on every privileged endpoint. CUSTOMER: own tickets only, PUBLIC comments only, cannot edit/assign, may only reopen.
* Cross-tenant (and cross-requester for customers) ids -> **404**, never 403.
* BCrypt (cost 10); constant-ish timing on unknown tenant/user (dummy hash); one generic "Invalid credentials" message.
* Login / registration rate limit: token bucket per IP and per account; bounded memory (Caffeine `maximumSize` + idle expiry, so rotating
  keys cannot exhaust the heap); 429 + `Retry-After`. Per node (the gateway is the place for a global limit). `X-Forwarded-For` is honoured
  only when `cx.security.trust-forwarded-for=true`.
* Stateless, CSRF disabled deliberately (Authorization-header API, no cookies), CORS only for the configured origin list `cx.cors.allowed-origins` (default `http://localhost:5173,http://localhost:4200`: React and Angular dev servers; override with `CX_CORS_ALLOWED_ORIGINS`).
* No default Spring user / generated password; request bodies and headers are never logged; `Redaction` masks password/token/key/webhook
  values before they enter audit details; `toString()` of credential-bearing DTOs is masked.
* `audit_event` is append-only: `@Immutable`, repository without update/delete (a test fails if one appears), and a PostgreSQL trigger.

## Tenant isolation (defence in depth)

1. `tenant_id` on every tenant-owned table; composite FKs `(tenant_id, x_id)` so a row cannot reference another tenant's user/category/ticket.
2. Hibernate `@FilterDef/@Filter("tenantFilter")` enabled on every transaction's session by `TenantAwareJpaTransactionManager`.
   (A query that forgets the tenant predicate still only sees the bound tenant - tested.)
3. Explicit tenant predicates / `findByIdAndTenantId` in every repository (filters do not apply to `find-by-id`).
4. `@PrePersist` refuses to persist a row for another tenant than the bound one.
5. Cache keys `t:{tenantId}:...`, events carry `tenantId`.
6. **PostgreSQL RLS** (`db/migration-pg/V1002`, policy `tenant_id = current_setting('app.tenant_id')`, `FORCE ROW LEVEL SECURITY`) -
   active **only in the docker profile on PostgreSQL**. It was written but **never executed** (no Docker here); `PostgresRlsDockerIT`
   (tagged `docker`) is the test that proves it on a machine with Docker.
   *Pooling caveat:* the tenant is bound with `select set_config('app.tenant_id', ?, true)` = `SET LOCAL`, i.e. **per transaction**, so a pooled
   connection can never carry one tenant's id into another request. This only works because every DB access runs in a transaction opened
   by `TenantAwareJpaTransactionManager`; a plain `JdbcTemplate` call outside a transaction would see no tenant and fail closed (zero rows).
   RLS is bypassed by superusers / `BYPASSRLS` roles: run the app with a dedicated non-superuser role, Flyway as the owner.
   Not under RLS on purpose: `organization` (login resolves slug before any tenant is known), `revoked_token` (no tenant), `outbox_event`
   (the relay claims across tenants).

## Tickets

State machine (contract defaults, replaceable per tenant by the active workflow's `transitions`), `@Version` optimistic locking
(stale `version` -> 409 `TICKET_VERSION_CONFLICT`; two parallel updates -> exactly one 200 and one 409, tested), `Idempotency-Key`
(table `idempotency_key`: tenant+user+key, SHA-256 request hash, stored response; replay returns the original response with header
`Idempotent-Replay: true`; same key + other body -> 409 `DUPLICATE_SUBMISSION`; concurrent same-key requests -> one ticket),
per-tenant numbers (`TicketNumberAllocator`: one atomic `UPDATE` of `ticket_counter` in its own tiny transaction before the create transaction; unique and increasing, **not gap-free**: a create that fails after allocation burns its number), categories, tags, synthetic attachment metadata
(no bytes), PUBLIC/INTERNAL comments, activity history, search (`q` over title/description/`#number`) + filters + whitelisted sort + bounded
paging (`size` 1..100). No N+1: ids are plain columns and names/attachments are loaded with one batched `IN` query each, so a page of 30
costs the same number of statements as a page of 2 (asserted with Hibernate statistics, `NPlusOneGuardTest`).
A staff PUBLIC reply sets `firstRespondedAt` (this bumps `version` once).

## Workflow and SLA

* Workflow = versioned JSON **data** (`rules[{name, when[{field,op,value}], then[{action,params}]}]` + `transitions[{from,to}]`).
  Fields: priority, severity, type, status, category, title, description, tags, escalationLevel, reopenCount.
  Ops: EQ NE GT LT IN CONTAINS. Actions: ASSIGN_TO_ROLE_LEAST_LOADED, SET_PRIORITY, ESCALATE, NOTIFY, ADD_TAG.
  No scripts / SpEL: values are compared as text, `${ #{ T( {{ <%` is rejected, unknown params are rejected, size limits (50 rules,
  10 conditions, 10 actions each). `POST /workflows/validate` -> `{valid, errors[]}`; `POST /workflows` creates a new version (inactive);
  `POST /workflows/{id}/activate` flips the single active version; everything is audited. Rules run on ticket creation and on every
  transition, once per trigger (no re-evaluation loops, max 20 actions). If the active definition is missing/corrupt the built-in default
  (no rules, contract transitions) is used and `cx_workflow_fallback_total` is incremented.
* SLA policies per priority (first response, resolution, escalation thresholds). Due dates use **business minutes** in the tenant's time
  zone / hours / days (`BusinessCalendar`, DST-safe). PENDING/ON_HOLD pause the clock; resuming shifts the due dates by the business
  minutes spent paused. `slaStatus`: ON_TRACK, AT_RISK (>= 75 % used), BREACHED, MET, PAUSED.
  Policy changes affect new tickets and priority changes, not already-computed due dates of other tickets.
* `SlaMonitorJob` (every 60 s, disabled in tests): iterates tenants, binds each tenant itself, examines at most 200 open tickets per tenant
  (least-recently-checked first), escalates when thresholds are crossed and flags breaches **once** (`escalation_level` only grows,
  `breach_notified` flips once) publishing `ticket.escalated` / `ticket.sla.breached` through the outbox. It uses bulk UPDATEs, so it never
  bumps `version` (no spurious user 409s).

## Outbox and events

`OutboxService.publish(type, payload)` requires an active transaction (`Propagation.MANDATORY`): the event row commits atomically with the
business change. `OutboxRelay` polls (1 s), claims each due row with a compare-and-set UPDATE + lease (portable equivalent of
`FOR UPDATE SKIP LOCKED`; exactly one of N concurrent claimers wins - tested), publishes **outside** any DB transaction, marks SENT; on failure
`attempts++` with exponential backoff (2 s base, cap 5 min), FAILED after `cx.outbox.max-attempts` (8). A crashed relay's lease expires and the row is
re-claimed. Delivery is at-least-once; consumers de-duplicate on `eventId`. `OutboxPurgeJob` (hourly) deletes SENT rows older than `cx.outbox.retention-days` (default 7) in bounded batches
(`purge-batch-size` 500, `purge-max-batches-per-run` 20, `purge-enabled`); PENDING / PROCESSING / FAILED rows are never purged.
Types: `ticket.created`, `ticket.assigned`, `ticket.status.changed`, `ticket.commented`, `ticket.escalated`, `ticket.sla.breached`,
`ai.suggestion.decided` (published by the AI module on approve / reject) and `ticket.workflow.notified` (workflow NOTIFY action; extra type, notification-service renders
unknown types with a generic template). Payload keys follow the notification-service templates (`ticketId, ticketNumber, title, priority,
actorName, assigneeName, fromStatus, toStatus, commentSnippet, escalationLevel, slaType, recipientUserIds`). INTERNAL comments are never
announced to the requester. `correlationId` is taken from the request's `X-Correlation-Id`.
Local: the platform-events `EventListener` interface already exists, so no bridge was needed - any `EventListener` bean (e.g. the
notification module's `InProcessEventListener`) receives events.

## Caching and metrics

`CacheService.getOrLoad(tenantId, key, ttl, type, loader)`: tenant-keyed cache-aside, TTL, single-flight (12 concurrent misses = 1 load, tested),
`evict`, `evictByPrefix`. Used for: analytics dashboard (60 s, also evicted after commit of any ticket write), categories (5 min), SLA policies (5 min),
tenant calendar settings (5 min), active workflow (5 min). Redis failures never fail a request: the loader runs, `cx.cache.fallback` is counted and a
5 s circuit breaker stops contacting a dead Redis. Micrometer counter `cx.cache{cache, result=hit|miss}` -> Prometheus `cx_cache_total{result="hit|miss"}`.

## Analytics

`GET /analytics/dashboard?days=30` (MANAGER+, 1..365). Counts and group-bys are live index-backed queries; the window metrics fold a bounded
(<= 50 000 rows) narrow projection in memory; at larger scale replace by a precomputed rollup behind the same cache (documented in
`AnalyticsService`). `aiAcceptanceRatePercent` is 0 until the AI module registers an `AnalyticsService.AiAcceptanceProvider` bean.

## Knowledge base (`com.example.cx.core.kb`)

Articles `{id,title,slug,body,summary,categoryId,categoryName,visibility PUBLIC|INTERNAL,status DRAFT|PUBLISHED,version,author,createdAt,updatedAt,relatedTicketIds}`;
endpoints exactly per `docs/API-CONTRACT.md` (`/articles`, `/articles/{id}`, `/publish`, `/versions`, `/link-ticket/{ticketId}`, `/article-categories`).

* **Authoritative state is relational** (`kb_category`, `kb_article`, `kb_article_ticket`): tenant FKs, unique slug per tenant, `@Version` optimistic lock
  (`version` in `PUT` is optional; when sent and stale -> 409), audit rows (`KB_ARTICLE_CREATED/UPDATED/PUBLISHED/TICKET_LINKED`, `KB_CATEGORY_CREATED`).
* **Version history**: every committed change (create, edit, publish) writes one immutable snapshot through the `DocumentStore` interface
  (`kb_article_version`, key `{articleId}:{8-digit version}`); `GET /articles/{id}/versions` lists them newest first. No-op edits and repeated publishes add none.
* **Visibility**: CUSTOMER sees PUBLISHED + PUBLIC only (list, get, search; drafts/internal ids -> 404; request filters can only narrow). Write, history and
  link endpoints need AGENT+. Customers never see `relatedTicketIds`.
* **Search**: portable `LIKE` narrowing + keyword scoring (title 5 / summary 3 / body <= 5 / phrase bonus), tenant + visibility scoped, bounded to 300 candidates,
  paginated. PostgreSQL GIN/trigram indexes exist in `db/migration-pg/V1010` but are not used by the portable path.
* **Cache-aside**: category list (5 min) and the PUBLISHED+PUBLIC list per page (60 s), evicted after commit of any write.
* **Seed**: 12 synthetic articles + 6 categories per demo tenant (8 published public, 2 published internal, 1 draft public, 1 draft internal).

## AI-assisted support (`com.example.cx.core.ai`)

`POST /tickets/{id}/ai/analyze`, `GET /tickets/{id}/ai/suggestions`, `POST /ai/suggestions/{id}/approve|reject` (AGENT+, other-tenant ids -> 404).

* **`AiProvider`** interface. Default **`MockRuleBasedAiProvider`**: fixed keyword rules, deterministic, **not machine learning** (category, priority, extractive
  summary, KB recommendation by keyword overlap, templated reply, confidence = rule-match strength, explanation text). Optional **`HttpAiProvider`** stub
  (`AI_PROVIDER=http`, `AI_API_URL` https only, `AI_API_KEY`; empty by default, never required, never called in tests).
* **Input**: `AiSanitizer` drops instruction-like sentences (prompt-injection aware), redacts e-mail / phone / card-like numbers / bearer tokens / secrets, bounds length.
  Ticket text is data; the provider is a pure function with no access to the DB or the ticket write API; output is validated against candidate lists and cleaned.
* **Resilience**: per-attempt timeout, bounded retries (default 2, hard cap 3) inside a total deadline, per-tenant rate limit (default 30/min -> 429), circuit-breaker-lite.
  Failure / timeout / open circuit => **HTTP 200 `degraded:true` + `fallbackMessage`**, no reply text, nothing stored. Test hook: `cx.ai.fault-mode=NONE|FAIL|TIMEOUT`
  (`AiFaultInjector`; refused in the docker profile).
* **Persistence**: `ai_suggestion` (status/tenant/ticket/decision, relational, authoritative) + metadata document (`ai_suggestion_meta`: features, scores, prompt hash,
  provider details, redaction counts, injection signals) in the `DocumentStore`.
* **Human approval**: approve (optionally `editedReply`) posts **exactly one** PUBLIC comment via `TicketService.addComment(..., aiGenerated=true)` and flips the status with a
  compare-and-set (`APPROVED` / `EDITED_APPROVED`); a second approve/reject -> 409; reject needs a reason and posts nothing. Audit for generation, degradation and decisions;
  `ai.suggestion.decided` is published through the outbox; `AiAcceptanceMetrics` feeds `aiAcceptanceRatePercent` (approved / decided, 0 when none).

## Database

`db/migration` V1-V15 portable (H2 PostgreSQL-mode and PostgreSQL): PK/FK/unique/check constraints, composite indexes led by `tenant_id`.
KB / AI / documents: `V20__kb`, `V21__document_store`, `V22__ai_suggestion` (V16-V19 remain free). PostgreSQL-only: `db/migration-pg/V1001` (partial index on open tickets, GIN
`tsvector`, trigram GIN), `V1002` (RLS), `V1003` (append-only audit trigger), `V1010` (KB full-text + trigram), `V1011` (RLS for the KB / AI / document tables).
Tests run Hibernate with `ddl-auto: validate`, so entity/schema drift fails the build.

## Extension points (used by the KB / AI modules, packages `com.example.cx.core.kb` / `core.ai`)

| Need | Use |
|---|---|
| tenant | `TenantContext.require()` (never accept a tenant from the client); extend `TenantOwned`, add `@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)` |
| current user | `CurrentUser.require()` -> `AuthUser` (roles, `isStaff()`, `isManagerOrAbove()`) |
| cache | `CacheService.getOrLoad(...)`, `evict`, `evictByPrefix` + `TxHooks.afterCommit(...)` for eviction |
| events | `OutboxService.publish(type, payload)` inside your transaction; `TicketEvents.base(...)` builds the standard payload |
| audit | `AuditService.record(action, entityType, entityId, details)` |
| post a comment (approved AI reply) | `TicketService.addComment(ticketId, body, visibility, aiGenerated=true)` |
| dashboard AI KPI | implement `AnalyticsService.AiAcceptanceProvider` |
| tenant provisioning hook | `TenantProvisioner.provisionDefaults` |
| flexible documents | `DocumentStore` (`com.example.cx.core.docstore`: JPA table / in-memory / MongoDB) |
| another AI provider | implement `AiProvider` (pure function; select with `cx.ai.provider`) |

## Known gaps / honest limits

* PostgreSQL, Redis, RabbitMQ, MongoDB code paths (RLS, `RedisCacheService`, `RabbitEventPublisher`, `MongoDocumentStore`, `db/migration-pg` incl. V1010/V1011) are
  **not executed** here (no Docker); seven docker-tagged tests exist (incl. `MongoDocumentStoreDockerIT`) but did not run.
* The AI module is a **mock rule-based engine** (keywords, not ML); `HttpAiProvider` is an unexecuted stub. Golden set 36/36 only proves the rules did not drift.
  AI rate limit and circuit breaker are per node. KB search is bounded to 300 candidates per query and the native PostgreSQL full-text path is not wired in.
* With the Mongo document store the version snapshot is not in the PostgreSQL transaction (see `docs/kb-storage-notes.md`).
* Deactivating a user does not revoke tokens already issued (they expire after 60 min; `/auth/me` and re-login check `active`).
* Rate limiting is per node. Single-flight is per node (across nodes: at most one load per node per TTL).
* The SLA job has no distributed leader lock (it is idempotent, so overlap is safe but redundant).
* Analytics window metrics are computed in memory from a bounded projection (see above).
* `ticket.workflow.notified` is an additional event type beyond the seven in the brief.
