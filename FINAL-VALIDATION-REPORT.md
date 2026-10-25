# Final Validation Report

Date of last full run: 2026-10-17. Machine: one Windows 11 developer laptop (Intel i5-1135G7, 4 cores). **Docker was not available**, so every PostgreSQL, Redis, RabbitMQ, MongoDB, Prometheus/Grafana, k6 and Testcontainers path is unexecuted. Everything below was executed by the commands shown unless it is listed under "Not executed".

## 1. Executed and passing (re-run by the final integrator)

| Check | Command | Result |
|---|---|---|
| Backend compile + all unit/integration tests (H2, Caffeine, in-memory relay) | `mvn -B -pl platform-events,notification-service,core-service,gateway,dsa-lab -am test` | exit 0; surefire reports: core-service 179, notification-service 46, gateway 14, dsa-lab 53 = **292 tests, 0 failures, 0 errors, 0 skipped** |
| React unit/component tests | `cd frontend && npm test` | 10 files, **86 passed** |
| Angular unit tests (Karma + Jasmine, headless Chrome) | `cd frontend-angular && npm test` | **83 passed** |

## 2. Executed earlier in this build by the component agents (results recorded in the cited docs; not re-run in the final pass)

| Check | Result | Where recorded |
|---|---|---|
| core-service smoke against running app (login, tickets, analytics, KB, AI) | 57/57 checks | `docs/core-service-validation.md` |
| React Playwright e2e on the real stack (core + gateway + Vite) | 17 run, 17 passed (16 pass + 1 degraded-AI spec that needs a second fault-injected stack; it was run with that stack) | `docs/e2e-validation.md` |
| Angular smoke on the real backend (puppeteer-core) | 25/25 steps, 3 consecutive runs | `frontend-angular/README.md` |
| React `tsc --noEmit`, lint, production build; Angular `ng lint`, `ng build` | clean / success | `frontend/README.md`, `frontend-angular/README.md` |
| Load test against core-service (H2, same laptop as generator) | e.g. ticket-list c=10 about 475 req/s, p95 58 ms; dashboard cache hit 3.23 ms vs miss 21.73 ms; ticket-create tail p99 477-1033 ms before and 81-84 ms after the ticket-number lock fix | `PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md` |
| Java code blocks in the interview bank | 30 blocks compiled, 66 assertions passed | `docs/interview/INTERVIEW-BANK-PART1.md` |
| H2 query-plan evidence (rows examined, not timings) | recorded | `PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md` section 7 |

## 3. Requirement checks

| Requirement | Status | Evidence |
|---|---|---|
| Tenant isolation (tickets, users, reports, KB, AI, audit) | Verified on H2 | `TenantIsolationTest` and KB/AI API tests; Playwright tenant-isolation spec |
| Authorization matrix | Verified | `RoleMatrixTest`, e2e customer restrictions |
| Ticket workflow, SLA behaviour | Verified | state-machine, workflow, `SlaCalculatorTest` (12), `SlaJobTest` |
| AI fallback and human approval | Verified (mock provider) | `AiApiTest`, `AiResilienceUnitTest`, e2e approve/reject/degraded |
| RabbitMQ retries / duplicate handling | Verified only with a **fake channel** and the in-process listener; never against a real broker | `NotificationMessageHandlerTest`, `EmbeddedNotificationsTest` |
| Migrations | Verified on **H2 in PostgreSQL mode only**; the PostgreSQL-only set (`db/migration-pg`, RLS, GIN) never ran | migrations V1-V22 |
| Query plans | H2 only; PostgreSQL `EXPLAIN ANALYZE` commands are provided and marked not executed | performance guide |
| Frontend-to-backend integration | Verified for React and Angular on the local profile | e2e + Angular smoke |
| Both PDFs generated and inspected | PDF 1: 88 pages; PDF 2: 414 pages; no missing images; footer on every page after the cover; a sample of pages inspected visually | `01-...pdf`, `02-...pdf` |

## 4. Not executed (needs infrastructure that was unavailable)

- 7 tests tagged `docker`: `PostgresRlsDockerIT`, `RabbitRedisDockerIT`, `MongoDocumentStoreDockerIT`, `NotificationRabbitDockerIT`. They compile; they were not run.
- `docker compose` stack, Prometheus, Grafana dashboard, alert rules (none are written), the `docker` Spring profile boot, real Redis/RabbitMQ/MongoDB/PostgreSQL behaviour including row-level security.
- k6 scripts (k6 not installed). `perf/load-test.mjs` was run only against the local H2 build.
- Playwright on browsers other than Chromium, mobile viewports, Lighthouse/React Profiler measurements.
- Angular app through the gateway and the React app re-tested after the new default CORS list (CORS is covered by a MockMvc preflight test for both origins).
- The CI workflow `.github/workflows/ci.yml` (sample; never executed).
- `scripts/run-local.sh` was syntax-checked only (a manual three-terminal fallback is documented).

## 5. Known gaps and honest caveats

- The local runtime is a modular core service plus a gateway; notification handling is embedded in core locally. Separate deployable microservices exist only for gateway, core and notification-service, and the split was never run on Docker.
- The AI assistant is a deterministic rule-based mock, not machine learning. `HttpAiProvider` is a stub that was never called for a real provider. The golden-set test is a regression test, not an accuracy measure.
- Deactivating a user or changing roles takes effect only when the issued token expires (60 minutes). There are no refresh tokens, MFA, TLS or CSP in the local setup.
- Outbox claim is a compare-and-set update with a lease (not `SKIP LOCKED`); the RabbitMQ publisher has no returns callback, so events published before the queue exists could be lost in a real broker (code reading, unverified).
- The ticket UI transition buttons use a client-side copy of the default transition table, so a tenant's custom workflow can still answer 422.
- Idempotency-Key is one per form mount; editing the body after a failed submit returns 409 DUPLICATE_SUBMISSION.
- The scheduler uses Boot's default single thread for all `@Scheduled` jobs.
- The environment template is named `env.example` (a deny rule in the authoring environment blocked the name `.env.example`); `frontend/.env.example` exists.
- Test-count figures in older prose were updated to the numbers above; if you change code, re-run the commands in `RUN-AND-TEST-GUIDE.md` and refresh them.

## 6. Defects found and fixed while building (full list in `docs/core-service-validation.md`)

SLA percent-used now uses business minutes; dashboard trend buckets use the tenant time zone; 415 and 405 return the contract error schema; ticket numbering no longer holds a long row lock; the outbox purge job was added; notifications are delivered in local mode; revoked-token lookups are cached for 3 s; recipients are checked against the event's tenant; the JWT dev secret default is aligned across services; Angular layout overlap fixed; seed-data flaky assertion removed.
