# High-Level Design

Multi-tenant CX + ITSM platform (self-built learning project, not Freshworks code). Companion documents: `ARCHITECTURE-DECISIONS.md`
(why), `docs/LLD.md` (classes), `docs/API-CONTRACT.md` (wire contract), `docs/core-service-validation.md` (what was actually run),
`PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md`, `docs/security-hardening.md`. Diagrams are Mermaid sources in `docs/diagrams/*.mmd` with
rendered PNGs in `docs/diagrams/png/`.

**Honesty legend used throughout.** `[RUN]` = executed in this repo (tests or the H2/Caffeine local run). `[CODE]` = code exists, was not
executed because Docker (PostgreSQL, Redis, RabbitMQ, MongoDB) is not installed here. `[ILLUSTRATIVE]` = made-up number to demonstrate a
method; never a Freshworks or production metric.

---

## 1. Requirements and assumptions

### 1.1 Functional (what exists)
| Area | Capability | Where |
|---|---|---|
| Tenancy and identity | Register organization, login by `tenantSlug`, 4 roles (CUSTOMER, AGENT, MANAGER, ADMIN), user admin, tenant settings | `core/auth`, `core/identity` |
| Tickets | Support and IT-service tickets, 7-state lifecycle, assign, comments (PUBLIC/INTERNAL), activity, categories, tags, attachment metadata, search and filters | `core/tickets` |
| Workflow | Versioned constrained rules plus custom transitions | `core/workflow` |
| SLA | Per-priority policies, business-hours clock, pause/resume, escalation, breach detection | `core/sla` |
| Analytics | Manager dashboard | `core/analytics` |
| Audit | Append-only audit trail | `core/audit` |
| Notifications | Event-driven in-app notifications (EMAIL simulated) | `notification-service` |
| KB and AI | Knowledge base, AI suggestions with human approval | `core/kb`, `core/ai` (see section 17) |
| Edge | Auth check, rate limit, correlation id, proxy | `gateway` |
| UI | Two feature-equivalent SPAs on the same API: React (portal, tickets, dashboard, admin) and Angular | `frontend`, `frontend-angular` |

### 1.2 Non-functional targets (design intent, not measured SLOs)
- Tenant isolation is the highest-priority property; a cross-tenant read is a severity-1 defect.
- Writes are durable and atomic with their events (outbox).
- Reads of reference data and the dashboard are cheap (cache-aside).
- Degrade rather than fail when Redis, the AI provider or the notification path is down.
- Every request is traceable by `X-Correlation-Id` in logs, audit rows and event headers.

### 1.3 Assumptions
1. One region, one PostgreSQL primary. No multi-region or active-active design exists.
2. HS256 shared-secret JWT is acceptable for a demo; production would use an IdP (ADR-005).
3. Tenants are small to medium (hundreds to low thousands of open tickets). The dashboard folds up to 50 000 rows in memory
   (`AnalyticsService.MAX_ROWS`).
4. Attachments are metadata only. No file bytes are stored.
5. Browser clients only; no mobile or public webhook API.
6. Email and Slack are placeholders (`integrations` settings are stored, not used to send).

### 1.4 Out of scope (not implemented)
SSO/SAML/OIDC, MFA, billing and plan limits, per-tenant quotas, data residency, GDPR erasure workflow, backup/restore tooling,
multi-region failover, real email/SMS/Slack delivery, file upload and virus scanning, distributed tracing backend, a real LLM.

---

## 2. Capacity estimation (method plus ILLUSTRATIVE numbers)

**Everything in this section is `[ILLUSTRATIVE]`.** The inputs are invented to demonstrate the method. They are not Freshworks figures,
not measured, and not a forecast. Change the inputs and redo the arithmetic.

### 2.1 Method
1. Pick tenants and per-tenant behaviour. 2. Derive daily and per-second rates (average, then peak with a factor). 3. Derive storage per
ticket, then per year. 4. Use Little's law (`concurrency = throughput x time in system`) to size connection pools and consumers.
5. Compare with measured local numbers (section 2.4) only to sanity-check orders of magnitude, never to extrapolate.

### 2.2 Worked example (inputs are made up)
| Input | Value |
|---|---|
| Tenants | 500 |
| Agents per tenant | 30 (15 000 agents) |
| New tickets per tenant per day | 400 |
| Outbox events per ticket lifecycle | 6 (created, assigned, 2 status changes, 2 comments) |
| Comments per ticket | 8 |
| Read:write ratio | 20:1 |
| Peak-to-average factor | 10x (working-hours concentration plus bursts) |

| Derived | Arithmetic | Result |
|---|---|---|
| Tickets per day | 500 x 400 | 200 000 |
| Average creates per second | 200 000 / 86 400 | 2.3 /s |
| Peak creates per second | 2.3 x 10 | 23 /s |
| Events per day | 200 000 x 6 | 1.2 M |
| Average / peak events per second | 1.2 M / 86 400, then x 10 | 13.9 /s, 139 /s |
| Peak write transactions (create plus lifecycle writes, about 8 per ticket) | 23 x 8 | about 185 /s |
| Peak read requests | 185 x 20 | about 3 700 /s |

### 2.3 Storage and connection sizing (same made-up inputs)
| Item | Per ticket (assumed) | Per day |
|---|---|---|
| Ticket row plus its 8 composite indexes and constraints (assumed size) | 2 KB | 400 MB |
| 8 comments at 1 KB | 8 KB | 1.6 GB |
| Activity rows (about 6, 0.3 KB) | 1.8 KB | 360 MB |
| Audit rows (about 5, 0.5 KB) | 2.5 KB | 500 MB |
| Outbox rows (6, 0.8 KB) | 4.8 KB | 960 MB |
| **Total** | **about 19 KB** | **about 3.8 GB** (about 1.4 TB/year) |

Outbox rows are now purged by `OutboxPurgeJob` (SENT rows older than 7 days, bounded batches), so the figure above is the 7-day steady state rather than unbounded growth; it is still the largest avoidable line, and the 7-day window is what the table assumes.

Connection pool (Little's law): if peak DB-touching requests are 3 700 /s and average DB time per request is 5 ms, concurrent connections
in use are `3 700 x 0.005 = 18.5`. Each node's Hikari pool defaults to 10 here (`hikaricp_connections_max 10` was observed), so with
4 nodes (40 connections) there is headroom; with one node the pool would saturate. Every authenticated request also costs a
`revoked_token` primary-key read ("not revoked" results are cached per `jti` for 3 s, so roughly one read per token per 3 s per node), which belongs in that 5 ms. PostgreSQL itself is usually
better with fewer than a few hundred total connections, so a PgBouncer in transaction mode would be the next step. Note the RLS
design uses `SET LOCAL` per transaction, which is compatible with transaction pooling; a session-level `SET` would not be.

Consumer sizing: 139 events/s peak with an average 20 ms of notification processing per event needs `139 x 0.02 = 2.8` busy consumers;
the configured defaults (`concurrency 2`, `max-concurrency 4`, prefetch 10) are the right order of magnitude.

### 2.4 Measured local reference points `[RUN]`
Single laptop, JVM and load generator on the same machine, H2 in memory, Caffeine cache, no network. These show the application
overhead only (see `PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md` section 3 for exact commands and caveats): ticket list at concurrency 10
reached 475.5 req/s (p50 14.0 ms, p95 58.4 ms, p99 91.3 ms); cached dashboard 1 402 req/s (p50 5.1 ms); ticket create 104 req/s (p99
1 943 ms). Do not compare these with the illustrative peak above.

---

## 3. System context and service boundaries

```
 Browser (React SPA :5173 or Angular SPA :4200)
        |  HTTPS (TLS terminated outside this repo)
        v
 gateway (:8080, WebFlux)  correlation id -> JWT check -> token bucket -> proxy
        |                                   (management/actuator on :8090)
        +-- /api/v1/notifications/**  -->  notification-service (:8082)   [docker profile]
        |                                   (locally: same URI as core, see note)
        +-- other /api/v1/**, swagger  -->  core-service (:8081)
                                             |
                  +--------------------------+---------------------------+
                  v                          v                           v
          PostgreSQL 16 (H2 locally)   Redis 7 (Caffeine locally)   RabbitMQ 3.13 (in-JVM locally)
                  ^                                                      |  topic exchange cx.events
                  |                                                      v
          notification-service tables (notif_*)  <------------  cx.notifications (+retry, +dlq)
          MongoDB 7: provisioned in compose; used by the docker profile (cx.docstore=mongo) for KB snapshots and AI metadata (section 17)
```
Diagrams: `01-system-context.mmd`, `02-service-architecture.mmd`, `15-local-infrastructure.mmd`.

| Component | Responsibility | Owns | Does not do |
|---|---|---|---|
| `gateway` | Edge: correlation id, signature/expiry check, per-subject rate limit, streaming proxy, 503/504 mapping | Nothing durable; in-memory buckets | Authorisation, tenant rules, revocation |
| `core-service` | System of record for identity, tickets, workflow, SLA, analytics, audit, outbox; also KB and AI | PostgreSQL tables (section 5) | Delivering notifications |
| `notification-service` | Consume events, de-duplicate, render, store in-app notifications, simulate channels, retry/DLQ | `notif_*` tables | Deciding who is notified (the event carries `recipientUserIds`) |
| `platform-events` | The shared `DomainEvent` record and `EventListener` interface | Contract | Logic |
| `frontend` | React SPA | Browser state only | Authorisation (UI hides, server enforces) |
| `frontend-angular` | Angular SPA (same features, same API contract) | Browser state only | Authorisation (UI hides, server enforces) |

**Local-run note `[RUN]`.** In the `local` profile the gateway's notifications route defaults to the core URI and core **does** serve it: `core-service` depends on the
`notification-service` library jar and `application-local.yml` sets `cx.notifications.embedded=true`, so the outbox relay delivers in-process to the notification module
(`InProcessEventListener`) and `/api/v1/notifications` works on :8081 (`EmbeddedNotificationsTest`). Under the `docker` profile the flag is `false` and notifications run in
the separate notification-service container fed by RabbitMQ. With no `EventListener` bean at all, `LocalEventPublisher` would drop events.

### 3.1 Why the boundaries are where they are
- Ticket, comment, activity, audit and outbox rows change together, so they stay in one service and one transaction (ADR-001).
- Notifications are a pure consequence of events and tolerate delay, so they are separable (ADR-007, ADR-020).
- The gateway is stateless and protects the services from unauthenticated and abusive traffic early (ADR-015).

---

## 4. API contract summary

Source of truth is `docs/API-CONTRACT.md`. Highlights that shape the design:

- Base `/api/v1`, JSON, `Authorization: Bearer <JWT>`, `X-Correlation-Id` accepted/returned (accepted only if `[A-Za-z0-9._-]{1,64}`).
- Error body on every non-2xx: `{timestamp, status, error, code, message, path, correlationId, fieldErrors[]}`. Codes: `VALIDATION_FAILED 400`,
  `UNAUTHENTICATED 401`, `FORBIDDEN 403`, `NOT_FOUND 404` (also for cross-tenant ids), `TICKET_VERSION_CONFLICT 409`, `DUPLICATE_SUBMISSION 409`,
  `INVALID_TRANSITION 422`, `RATE_LIMITED 429`, `AI_UNAVAILABLE 503`, `INTERNAL_ERROR 500`. The gateway adds `UPSTREAM_UNAVAILABLE 503`,
  `UPSTREAM_TIMEOUT 504`, `UPSTREAM_ERROR 502`.
- Pagination: `page` (0-based), `size` 1..100 default 20, `sort=field,dir` from a whitelist.
- Concurrency and idempotency: `version` in update/assign/transition bodies; `Idempotency-Key` required on `POST /tickets`.

| Group | Endpoints | Minimum role |
|---|---|---|
| Auth | `POST /auth/login`, `/auth/register-organization` (public); `GET /auth/me`, `POST /auth/logout` | any |
| Tickets | `GET/POST /tickets`, `GET/PUT /tickets/{id}`, `POST .../assign`, `POST .../transition`, `GET/POST .../comments`, `GET .../activity` | CUSTOMER (own tickets), staff for assign/edit |
| Categories | `GET /ticket-categories`; `POST/PUT/DELETE` | read any, write ADMIN |
| Users and tenant | `GET /users` (AGENT+), `POST/PUT /users`, `GET/PUT /tenant/settings`, `GET /audit-events` | ADMIN (except list) |
| Workflow and SLA | `GET /workflows`, `POST /workflows`, `/validate`, `/{id}/activate`, `GET/PUT /sla-policies` | read MANAGER+, write ADMIN |
| Analytics | `GET /analytics/dashboard?days=1..365` | MANAGER+ |
| KB | `/articles`, `/article-categories` | CUSTOMER sees PUBLISHED+PUBLIC only |
| AI | `POST /tickets/{id}/ai/analyze`, `GET .../ai/suggestions`, `POST /ai/suggestions/{id}/approve|reject` | AGENT+ |
| Notifications | `GET /notifications`, `POST /notifications/{id}/read`, `GET /notifications/unread-count` | any (own) |
| Ops | `/actuator/health[/readiness]`, `/actuator/prometheus`, `/swagger-ui.html`, `/v3/api-docs` | public in core (see security guide) |

---

## 5. Data ownership and schema

### 5.1 Ownership
| Data | Owner | Notes |
|---|---|---|
| organization, app_user, revoked_token, audit_event | core | `organization` and `revoked_token` are not tenant-filtered by RLS (login and revocation need them before a tenant is known) |
| ticket, ticket_comment, ticket_activity, ticket_attachment, ticket_category, ticket_counter, idempotency_key | core | tenant-owned, RLS (PG) |
| workflow, sla_policy | core | tenant-owned, RLS (PG) |
| outbox_event | core | cross-tenant by design (relay claims across tenants), no RLS |
| kb_*, ai_suggestion, document_store | core (KB/AI modules) | tenant-owned, RLS (V1011) |
| notif_processed_event, notif_notification, notif_delivery | notification-service | own Flyway history `notif_schema_history`; no RLS |

### 5.2 Schemas (from `core-service/src/main/resources/db/migration`)
All ids are `uuid`; all timestamps `timestamp with time zone`. "FK" lists composite tenant-safe keys.

| Table (migration) | Columns of note | Constraints and indexes |
|---|---|---|
| `organization` (V1) | id, slug (unique, lowercase check), name, timezone, business_hours_start/end, business_days, default_language, email_provider, slack_webhook_url, ai_provider | PK id, `uq_organization_slug` |
| `app_user` (V2) | tenant_id, email (lowercase check), display_name, password_hash, roles (csv), active, last_login_at | unique `(tenant_id, email)`, unique `(tenant_id, id)` for composite FKs, `ix_app_user_tenant_active` |
| `revoked_token` (V3) | jti PK, expires_at, revoked_at | `ix_revoked_token_expires` |
| `audit_event` (V4) | tenant_id, occurred_at, actor_id, actor_email, action, entity_type, entity_id, details (4000), correlation_id | 3 indexes led by tenant_id; PG trigger blocks UPDATE/DELETE (V1003) |
| `ticket_category` (V5) | name, type (SUPPORT/IT_SERVICE), default_priority | unique `(tenant_id, name)`, unique `(tenant_id, id)` |
| `ticket_counter` (V6) | tenant_id PK, next_number | atomic `UPDATE ... next_number + 1` in its own tiny transaction before the create transaction (`TicketNumberAllocator`) |
| `ticket` (V7) | number, type, title(200), description(8000), status, priority, severity, category_id, tags (csv), requester_id, assignee_id, due/response/resolved timestamps, sla_status, sla_paused_at, sla_checked_at, breach_notified, escalation_level, reopen_count, version | FKs `(tenant_id, requester_id)`, `(tenant_id, assignee_id)`, `(tenant_id, category_id)`; unique `(tenant_id, number)`; check constraints on every enum |
| `ticket_attachment` (V8) | file_name, content_type, size_bytes (check <= 26 214 400) | FK `(tenant_id, ticket_id)` |
| `ticket_comment` (V9) | author_id, body(8000), visibility, ai_generated | FKs ticket and author (tenant-safe) |
| `ticket_activity` (V10) | actor_id, actor_name, type, details | FK ticket; 2 indexes |
| `idempotency_key` (V11) | user_id, idem_key, request_hash, response_status, response_body (20000) | unique `(tenant_id, user_id, idem_key)` |
| `workflow` (V12) | name, version, active, definition (JSON text, 20000) | unique `(tenant_id, version)` |
| `sla_policy` (V13) | priority, first_response_minutes, resolution_minutes, escalation_after_percent | unique `(tenant_id, priority)`, check `resolution >= first response` |
| `outbox_event` (V14) | event_type, payload (JSON, 20000), status PENDING/PROCESSING/SENT/FAILED, attempts, next_attempt_at, locked_by, locked_until, last_error, sent_at | `ix_outbox_poll (status, next_attempt_at)`, `ix_outbox_tenant` |
| ticket indexes (V15) | 8 composite indexes, all led by `tenant_id`: `(status, priority, created_at)`, `(created_at)`, `(assignee_id, status)`, `(requester_id, created_at)`, `(sla_status)`, `(category_id)`, `(resolved_at)`, `(status, sla_checked_at)` | |
| `kb_category`, `kb_article`, `kb_article_ticket` (V20) | article: title, slug, summary, body(15000), visibility, status, author_id, published_at, version | unique `(tenant_id, slug)`, tenant-safe FKs, `ix_kb_article_list (tenant_id, status, visibility, updated_at)` |
| `document_store` (V21) | PK `(tenant_id, collection, doc_key)`, doc_json (32000) | JPA implementation of `DocumentStore` |
| `ai_suggestion` (V22) | ticket_id, status PENDING_REVIEW/APPROVED/EDITED_APPROVED/REJECTED, provider, summary, suggested category/priority, confidence, explanation, recommended_articles, suggested_reply, created_by, decided_by/at, decision_reason, posted_comment_id | tenant-safe FKs, 2 indexes |

PostgreSQL-only (`db/migration-pg`, docker profile, **not executed**): V1001 partial index `ix_ticket_open_queue` on non-final tickets, GIN
full-text `ix_ticket_fts`, trigram `ix_ticket_title_trgm`; V1002 RLS; V1003 audit append-only trigger; V1010 KB full-text and trigram;
V1011 RLS for KB/AI/document tables. ERD: `docs/diagrams/12-database-erd.mmd`.

Notification schema (`V1__notif_init.sql`): `notif_processed_event(event_id PK, tenant_id, event_type, status, attempts, last_error, ...)`,
`notif_notification(id, tenant_id, user_id, event_id, type, title, body, ticket_id, is_read, ...)` with unique `(event_id, user_id)` and inbox
index `(tenant_id, user_id, is_read, created_at)`, `notif_delivery(... channel, status, attempts ...)` with unique `(event_id, user_id, channel)`.

---

## 6. Event contracts

Envelope: `platform-events/.../DomainEvent.java` is a record `(UUID eventId, String type, int schemaVersion, UUID tenantId, String correlationId,
Instant occurredAt, Map<String,String> payload)`. `OutboxService.SCHEMA_VERSION = 1`. The payload is a flat string map. The type is the
RabbitMQ routing key. Constants: exchange `cx.events`, DLX `cx.events.dlx`, queues `cx.notifications`, `cx.notifications.retry`,
`cx.notifications.dlq`.

| Type | Emitted by | Recipients (`recipientUserIds`) | Extra payload keys |
|---|---|---|---|
| `ticket.created` | `TicketService.create` | up to 20 AGENT-role users plus assignee, minus actor | base keys |
| `ticket.assigned` | assign, workflow routing | the assignee | `assigneeId, assigneeName` |
| `ticket.status.changed` | transition | requester and assignee, minus actor | `fromStatus, toStatus, reason` |
| `ticket.commented` | `addComment` | requester only if PUBLIC, plus assignee | `visibility, commentSnippet` (120 chars) |
| `ticket.escalated` | SLA job, workflow ESCALATE | managers (up to 20) plus assignee | `escalationLevel, reason` |
| `ticket.sla.breached` | SLA job | managers plus assignee | `slaType` |
| `ticket.workflow.notified` | workflow NOTIFY (extra type) | workflow-selected users | `message` |
| `ai.suggestion.decided` | `AiDecisionService` on approve or reject | the ticket's assignee, unless the assignee is the decider (empty list if unassigned) | `suggestionId, decision` (`APPROVED`, `EDITED_APPROVED`, `REJECTED`), `edited`, `provider`; no ticket text beyond the base title |

Base keys: `ticketId, ticketNumber, title, priority, status, actorId, actorName, recipientUserIds` (comma list of UUIDs).
Guarantees: at-least-once; consumers dedupe on `eventId`; no ordering guarantee; schema evolution by bumping `schemaVersion`
(additive keys do not require a bump because the map is open). RabbitMQ binding keys are `ticket.#` and `ai.#` (three-word types need `#`).
Sequence diagrams: `11-transactional-outbox.mmd`, `10-rabbitmq-topology.mmd`.

---

## 7. Cache design

| Cache name (metric label) | Key (after `t:{tenant}:`) | TTL | Invalidated by |
|---|---|---|---|
| `analytics` | `analytics:dashboard:{days}` | 60 s | `evictByPrefix("analytics:")` after commit of any ticket create/update/assign/transition/comment |
| `categories` | `categories...` | 5 min | category writes |
| `sla` | SLA policies | 5 min | policy update |
| `settings` | tenant calendar settings | 5 min | settings update |
| `workflow` | `workflow:active` | 5 min | workflow activation |

Patterns: cache-aside with `getOrLoad`; per-node single-flight; TTL jitter on Redis; DTOs only; Redis errors counted in
`cx_cache_fallback_total{op=read|write|evict}` and bypassed for 5 s. Hit/miss counter `cx_cache_total{cache,result}`. Local numbers: section
2.4 and the performance guide. Diagram: `13-cache-aside.mmd`. Redis path `[CODE]`; Caffeine path `[RUN]`.

---

## 8. Tenant isolation, and how authentication, tenant resolution, authorization and data isolation differ

These are four separate mechanisms. Mixing them up is the usual source of multi-tenant bugs.

| Concern | Question answered | Mechanism here | Failure looks like |
|---|---|---|---|
| **Authentication** | Who is this caller? | `JwtAuthFilter` verifies the HS256 signature, issuer, expiry (5 s skew), required claims, and that `jti` is not revoked; builds `AuthUser` | 401 `UNAUTHENTICATED` |
| **Tenant resolution** | Which tenant is this caller in? | `TenantContext.set(tid)` from the verified token only. At login the slug picks whose credentials to check, then `tid` is issued in the token | Wrong or missing tenant: queries return nothing or `IllegalStateException` (500) |
| **Authorization** | What may this caller do? | Role hierarchy `ADMIN > MANAGER > AGENT`, `CUSTOMER` outside it; `@PreAuthorize` on privileged endpoints; service-level rules (customers see own tickets, no INTERNAL comments, reopen only) | 403 `FORBIDDEN`, or 404 for objects of another requester |
| **Data isolation** | Can a bug still leak another tenant's rows? | Hibernate tenant filter, explicit `tenantId` predicates, composite FKs, `@PrePersist` guard, tenant-keyed cache, optional RLS | 404 (not 403) for cross-tenant ids; RLS returns zero rows |

Key distinctions: a valid token for tenant A authenticates fine and resolves to tenant A; it passing authorization for `GET /tickets/{id}`
says nothing about whether ticket `{id}` belongs to A. That last step is data isolation (`findByIdAndTenantId`). Tenant resolution must not
come from the same channel the attacker controls (path, header, body); here it comes only from the signed token.

Diagram: `05-tenant-isolation.mmd`, `14-auth-flow.mmd`, `18-request-lifecycle.mmd`.

### 8.1 RLS evaluation
- **How it works.** Policy `tenant_isolation` on 11 core tables (V1002) and 5 more (V1011): `using` and `with check` both `tenant_id = cx_current_tenant()`.
  `cx_current_tenant()` is `nullif(current_setting('app.tenant_id', true), '')::uuid`. `FORCE ROW LEVEL SECURITY` makes it apply to the table owner.
  `TenantAwareJpaTransactionManager.doBegin` runs `select set_config('app.tenant_id', ?, true)` after the transaction starts.
- **Is it enabled?** Only when the `docker` profile is active (or `cx.tenancy.pg-rls=true`), and only on PostgreSQL. In `local` it is off: H2 has no RLS and the
  migration files are not even loaded. `[CODE]`, never executed. The proving test `PostgresRlsDockerIT` did not run.
- **Pooling caveat.** `SET LOCAL` semantics (third argument `true`) clear the value at transaction end, so a pooled connection returns clean.
  This is safe with Hikari and with PgBouncer transaction pooling. A session-level setting is not safe with either.
- **Other caveats.** (1) Superusers and `BYPASSRLS` roles skip policies. (2) Anything running outside a transaction sees no tenant and returns zero
  rows (fail closed, but a silent empty result can look like a data-loss bug). (3) System jobs must bind a tenant per unit of work (`SlaMonitorJob`
  does). (4) `outbox_event`, `organization`, `revoked_token` are exempt. (5) The notification tables are exempt. (6) Mongo has no equivalent. (7) Cost of the extra
  statement per transaction is unmeasured.
- **Verdict.** A sound second layer on paper, worth about as much as its first execution. Run `-Pdocker-tests` on a Docker machine before relying on it.

---

## 9. Consistency models, idempotency and concurrency control

| Concern | Model | Mechanism | Evidence |
|---|---|---|---|
| Ticket write plus event | Atomic (one local ACID transaction) | outbox row inserted in the business transaction (`Propagation.MANDATORY`) | `OutboxTest` `[RUN]` |
| Event delivery | At-least-once, unordered | relay lease plus retry; consumer dedupe on `eventId` | `OutboxTest`, `NotificationProcessorTest` `[RUN]`; broker `[CODE]` |
| Notification visibility | Eventually consistent (seconds) | relay poll 1 s plus consumer time | by design |
| Dashboard | Eventually consistent, at most 60 s stale, evicted on writes | cache-aside TTL plus eviction after commit | `CacheIntegrationTest` `[RUN]` |
| Reference data | Eventually consistent, up to 5 min on failed eviction | TTL | by design |
| Edits to one ticket | Optimistic (first writer wins, loser gets 409) | `@Version` plus request `version` | `TicketApiTest` `[RUN]` |
| Ticket numbers | Unique and increasing per tenant; **not gap-free** (a failed create burns its number) | atomic counter UPDATE in its own short transaction (`TicketNumberAllocator`); unique `(tenant_id, number)` | `TenancyTest` (10 parallel creates give 1..10), `TicketNumberAllocatorTest` `[RUN]` |
| Create retry | Exactly-once effect per key | `idempotency_key` unique constraint plus request hash | `TicketApiTest` `[RUN]` |
| Logout | Immediately effective on the node that handled it; on other nodes within the negative-cache TTL (default 3 s, 0 = strict) | `revoked_token` table + 3 s "not revoked" cache | `AuthApiTest`, `TokenRevocationCacheTest` `[RUN]` |
| SLA state | Eventually consistent (job every 60 s), idempotent | bulk update, `breach_notified` once | `SlaJobTest` `[RUN]` |

Idempotency summary: HTTP create (`Idempotency-Key`), event consumption (`eventId`), SLA flagging (monotonic `escalation_level`, once-only `breach_notified`),
cache fills (single-flight), outbox claim (compare-and-set). Not idempotent: comment creation, user creation (unique email guards it), assign
(no-ops when the assignee is unchanged).

---

## 10. Failure modes

| Failure | Detected by | Behaviour | Data risk | Recovery |
|---|---|---|---|---|
| Redis down | `cx_cache_fallback_total` rising, WARN log "Redis unavailable" | loader runs, 5 s breaker, requests slower not failing | none | restore Redis; entries refill |
| RabbitMQ down | `cx_outbox_retried_total` rising, `outbox_event` PENDING growing | relay retries with backoff 2 s..300 s, FAILED after 8 attempts | none while rows are not FAILED | restore broker; reset FAILED rows manually |
| Notification consumer down | queue `cx.notifications` depth grows | messages wait, durable | none | restart consumer |
| Consumer poison message | DLQ depth, `cx_notifications_events_total{result="dead"}` | straight to DLQ | message parked | inspect, fix, republish |
| PostgreSQL down | readiness fails, Hikari timeouts, 500s | requests fail; relay and jobs log warnings | none (no partial commits) | restore DB |
| Pool exhausted | `hikaricp_connections_pending > 0`, long acquire time | requests queue then time out | none | find the slow transaction (runbook 2) |
| AI provider timeout | `degraded: true` in responses, `AI_UNAVAILABLE` | HTTP 200 degraded, no reply text | none | provider recovery |
| Gateway down | external probes | all API access lost | none | replicas; gateway is tier-0 |
| Core node crash mid-relay | row stays PROCESSING | lease (60 s) expires, another node re-claims | duplicate delivery possible | consumers dedupe |
| Bad workflow definition | `cx_workflow_fallback_total` | default workflow used | none | fix and re-activate |
| Stale cache after missed eviction | users report stale dashboard | up to TTL | stale read only | wait or evict |
| Clock skew between nodes | JWT `exp` edge cases (5 s skew allowed) | sporadic 401 | none | NTP |
| JWT secret mismatch gateway vs core | all authenticated calls 401 at the gateway | | none | align `CX_JWT_SECRET` |
| Outbox table growth | table size, slow `ix_outbox_poll` | polling slows | none | add retention job (gap) |

Diagrams: `16-observability.mmd`, `10-rabbitmq-topology.mmd`.

---

## 11. Scaling bottlenecks (in likely order)

1. **DB connections per node (Hikari default 10).** The per-request `revoked_token` read is now cached for 3 s per `jti`. Mitigate further: PgBouncer, bigger pool with care.
2. **Per-tenant ticket counter row lock** serialises creates within a tenant (local p99 1 943 ms at concurrency 10, cause not isolated).
3. **Dashboard compute** folds up to 50 000 rows in the JVM on a cache miss (21.7 ms median at 3 753 tickets locally; grows linearly). Mitigate: rollup table from outbox events.
4. **Offset pagination and `count(*)`** per page. Mitigate: keyset, estimated counts.
5. **`LIKE '%q%'` search**. Mitigate: PG trigram/GIN (written, not executed), or a search engine.
6. **Outbox polling and unbounded growth.** Mitigate: retention job, CDC.
7. **SLA job** is sequential and unlocked: every node scans every tenant. Mitigate: leader lock, per-tenant partitioning.
8. **Single PostgreSQL primary.** Mitigate: read replicas for analytics, then partition by tenant.
9. **In-memory rate limit** is per instance (no global quota).
10. **Notification polling** from the SPAs every 30 s per tab.

---

## 12. Security boundaries

| Boundary | Control |
|---|---|
| Internet to gateway | TLS (outside this repo), edge JWT check, rate limit, correlation id sanitising |
| Gateway to core | Same secret; core re-validates everything; `X-Forwarded-For` replaced by the socket peer |
| Core to data stores | Credentials from environment, no defaults in the docker profile; PG role should not be superuser |
| Tenant to tenant | Section 8 |
| User to user within a tenant | Roles and requester scoping; INTERNAL comments hidden from customers |
| Service to service | Shared HS256 secret for the notification REST API; no mTLS |
| Browser | CORS allow-list (`http://localhost:5173`), no cookies so CSRF is disabled on purpose, nginx adds `X-Content-Type-Options`, `X-Frame-Options DENY`, `Referrer-Policy no-referrer` |
| Secrets | `env.example` has dummy values only; `.env` must not be committed |

Detail and OWASP API Top 10 mapping: `docs/security-hardening.md`.

---

## 13. Observability

- **Logs.** One key=value line per event: `ts level corr tenant user logger msg`, with MDC fields `correlationId`, `tenantId`, `userId`. The access log line
  (`http.access`) records method, path, status and duration only: never bodies, headers or query strings.
- **Metrics (Prometheus names).** `http_server_requests_seconds_*` (histogram buckets are **not** enabled by default, so the p95 panel stays empty until
  `management.metrics.distribution.percentiles-histogram.http.server.requests=true` is set), `hikaricp_connections_{active,idle,pending,max,min}`,
  `hikaricp_connections_acquire_seconds_*`, `jvm_memory_used_bytes`, `process_cpu_usage`; app metrics: `cx_cache_total{cache,result}`,
  `cx_cache_fallback_total{op}`, `cx_outbox_enqueued_total{type}`, `cx_outbox_published_total{type}`, `cx_outbox_retried_total{type}`,
  `cx_outbox_failed_total{type}`, `cx_tickets_total` (counter `cx.tickets.created`), `cx_auth_login_total{result}`, `cx_sla_job_runs_total`,
  `cx_sla_job_errors_total`, `cx_workflow_fallback_total{reason}`, `cx_notifications_events_total{result}`, `gateway_proxied_total{route,status}`,
  `gateway_rate_limited_total`, `gateway_upstream_errors_total{route,type}`.
- **Dashboards.** `infra/grafana/dashboards/cx-platform-overview.json` (request rate, 5xx, latency, Hikari, cache hit ratio, RabbitMQ queue depth,
  notification outcomes, gateway, JVM). Provisioned but **never started** (no Docker).
- **Health.** `/actuator/health`, `/actuator/health/readiness`, `/actuator/health/liveness`.
- **Not present.** Distributed tracing (OpenTelemetry), alert rules, SLO burn alerts, log shipping.

---

## 14. Deployment topology

`[CODE]` `infra/docker-compose.yml` (never executed): `postgres:16`, `mongo:7`, `redis:7` (password, AOF), `rabbitmq:3.13-management`,
`prom/prometheus:v2.54.1`, `grafana/grafana:11.2.2`; with profile `apps`: `core-service`, `notification-service`, `gateway`, `frontend` (nginx
unprivileged on 8080 mapped to 5173). Published ports bind to 127.0.0.1. Required secrets use `${VAR:?...}`. App images are runtime-only (pre-built jar,
non-root). Gateway healthcheck uses the management port 8090. The `local` profile needs none of this: `scripts/run-local.sh` starts core (H2),
gateway and the Vite dev server (script written, not executed end to end by its author).

Production would add: managed PostgreSQL with a separate runtime role, 2+ replicas of gateway and core behind a load balancer, TLS termination,
Redis with HA, RabbitMQ cluster or managed broker, a secret manager, and the missing items in section 15.

---

## 15. Alternatives considered (summary; detail in `ARCHITECTURE-DECISIONS.md`)

Full microservices; database or schema per tenant; opaque server-side sessions or an IdP; CDC instead of polling outbox; Kafka instead of RabbitMQ;
Spring Cloud Gateway; scripting workflows; `jsonb` instead of Mongo; Elasticsearch for search; keyset pagination.

---

## 16. Known gaps (honest list)

1. Docker-profile code paths were never executed: PostgreSQL migrations in `db/migration-pg`, RLS, Redis cache, RabbitMQ publisher/consumer topology, Mongo store, compose stack, Grafana dashboard.
2. (Closed) Embedded notifications are wired into the core jar and enabled in profile `local`; `EmbeddedNotificationsTest` proves create ticket -> outbox -> notification row -> `GET /notifications`. In `docker` the separate notification-service consumes RabbitMQ (never executed here).
3. No retention for `notif_processed_event` and `audit_event`; `outbox_event` (7 days, `OutboxPurgeJob`) and `idempotency_key` (24 h) are purged.
4. No tenant-level kill switch, quotas or per-tenant rate limits.
5. No refresh tokens or key rotation; symmetric signing key shared by three services.
6. SLA job has no leader lock; rate limits are per instance.
7. p95/p99 server-side latency histograms are off by default.
8. Actuator `/actuator/prometheus` and Swagger are open without authentication in core (see security guide).

---

## 17. KB and AI implementation check

Written after reading `core-service/src/main/java/com/example/cx/core/{kb,docstore,ai}`, migrations V20-V22 and V1010-V1011, `application*.yml` while another agent was still finishing that
code (file times up to 13:43 on 2026-10-17). The KB/AI tests have since been run: they are part of the 179-test core-service run (0 failures) and the 57-check smoke run recorded in `docs/core-service-validation.md`.

### 17.1 Knowledge base (`core/kb`)
- Tables `kb_category`, `kb_article`, `kb_article_ticket` (V20), tenant-owned with tenant-safe composite FKs and unique `(tenant_id, slug)`. Current content is relational; **every committed change writes one
  immutable snapshot to the `DocumentStore`** (collection `kb_article_version`, key `{articleId}:{8-digit version}`), read back by `GET /articles/{id}/versions` (staff only, bounded to 500).
- Visibility rule enforced in `KbService`, not the controller: a CUSTOMER sees only PUBLISHED + PUBLIC; filters can narrow but never widen; hidden ids are 404.
- Writes (create, update, publish, link ticket, create category) and version history need AGENT+ (`@PreAuthorize`); update uses an optional `version` check (409 on mismatch); all writes are audited (`KB_ARTICLE_*`).
- Cache (tenant-keyed, evicted after commit): published list pages 60 s (`kb:pub:` keys), categories 5 min (`kb:categories`), so `cx_cache_total{cache="kb"}`.
- Search is portable: SQL `LIKE` on up to 8 tokens (stop words removed, light suffix stripping), at most 300 candidates newest first, scored in the JVM by `KbSearchScorer` (title 5, summary 3, body up to 5, whole-phrase bonuses). It is deterministic
  and explainable, **not** a relevance model. Consequence: `totalElements` counts matches within the 300-candidate bound, and cost grows with article size. PostgreSQL-only GIN full-text and trigram indexes exist (V1010) but the portable path does not use them: **not executed**.
- Demo content: `KbDemoSeeder` (seeded tenants).

### 17.2 Document store (`core/docstore`) and where MongoDB fits
`DocumentStore` interface with three implementations: `JpaDocumentStore` (default, table `document_store`, joins the caller's transaction, 32 000 character cap), `InMemoryDocumentStore` (tests), `MongoDocumentStore` (docker profile).
Mongo layout: one collection `cx_documents`, every document `{tenantId, collection, key, doc{...}}`, every query filters `tenantId` first, 3 indexes created at startup (unique `(tenantId, collection, key)`, `(tenantId, collection, doc.provider)`,
`(tenantId, collection, doc.articleId, doc.version desc)`), one aggregation (`$match` then `$group`/`$avg`). PostgreSQL stays authoritative for articles, suggestion status and decisions. Mongo failures for AI metadata are tolerated (logged, `cx_ai_meta_failed_total`);
a failed KB snapshot write inside the article transaction would surface to the caller. With Mongo (unlike the JPA default) the snapshot is **not** atomic with the article transaction: an article change that rolls back after the snapshot was written leaves an orphan snapshot.
Never executed against Mongo (no Docker).

### 17.3 AI assist (`core/ai`)
- Flow: `POST /tickets/{id}/ai/analyze` (AGENT+) loads ticket, categories and up to 100 published KB candidates in a read transaction; per-tenant rate limit; sanitises title and description; calls the provider on a bounded pool
  **outside any DB transaction** with timeout, bounded retries and breaker; on success stores a `PENDING_REVIEW` row in `ai_suggestion` plus metadata in the document store plus an audit event; on any failure returns HTTP 200 `degraded: true` with a fixed fallback message and audits `AI_ANALYSIS_DEGRADED` with a reason.
- Providers: default `MOCK_RULE_BASED` (deterministic keyword rules, not ML); optional `HTTP` stub (https only, never executed). Fault injection `cx.ai.fault-mode` (NONE|FAIL|TIMEOUT) is test-only and refused under the docker profile.
- Decision (`AiDecisionService`): approve or reject only from `PENDING_REVIEW` using a compare-and-set UPDATE (concurrent or repeated decisions get 409); approve posts exactly one PUBLIC comment with `aiGenerated=true` via `TicketService.addComment`, in the same transaction as the audit row and the `ai.suggestion.decided` outbox event;
  reject posts nothing. Both evict the tenant's `analytics:` cache entries so `aiAcceptanceRatePercent` (computed by `AiAcceptanceMetrics`: accepted / decided in the window, 0 when no decisions) is fresh.
- Metrics: `cx_ai_analyze_total{result=ok|degraded|rate_limited}`, `cx_ai_meta_failed_total`.
- Knobs (all bounded in `AiSettings`): `cx.ai.timeout-ms=3000`, `max-retries=2` (cap 3), `total-deadline-ms=10000`, `rate-limit-per-minute=30` per tenant per node, `breaker-failures=5`, `breaker-open-ms=30000`.
- Gaps: nothing about answer quality can be said (no evaluation set, mock provider); the sanitiser is regex-based best effort; the breaker and rate limiter are per node; the HTTP provider path is unexecuted; no cost or token accounting.

### 17.4 Schema additions
V20 (KB), V21 (`document_store`), V22 (`ai_suggestion`), PostgreSQL-only V1010 (KB full-text and trigram), V1011 (RLS for `kb_category`, `kb_article`, `kb_article_ticket`, `ai_suggestion`, `document_store`). `docs/diagrams/09-ai-suggestion-flow.mmd` shows the flow.
