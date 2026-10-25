# Run and test guide

Commands below were copied from `scripts/run-local.sh`, `frontend/package.json`, `e2e/package.json`, `core-service/pom.xml`, the module READMEs, `perf/README.md`
and `docs/core-service-validation.md`. Each section says whether the command is **recorded as executed** by the project authors or **NOT executed**. The author of this
guide ran nothing; where a result is quoted, it comes from `docs/core-service-validation.md` (executed 2026-10-17 on Windows + Git Bash, GraalVM JDK 21.0.7, Maven 3.9.12, no Docker).
All credentials in this repository are dummy, local-only values.

## 1. Prerequisites

| Tool | Version used / required | Needed for |
|---|---|---|
| JDK | 21 (`java.version` 21 in `pom.xml`; authoring machine: `C:/Users/sahukari.rao/.jdks/graalvm-jdk-21.0.7`) | all backend builds and tests |
| Maven | 3.9.x (authoring machine: 3.9.12 from the Maven wrapper dists; no `mvnw` in the repo) | all backend builds and tests |
| Node.js + npm | Node 18+ (required by `perf/load-test.mjs`); the frontend uses Vite 5 / TypeScript 5.6, so a current LTS is the safe choice | frontend, e2e, smoke test, load test |
| Git Bash (Windows) or any POSIX shell | `scripts/run-local.sh` is a bash script | local runner |
| Docker + Compose | only for the docker profile and docker-tagged tests | NOT available on the authoring machine |
| k6 | optional, only for `perf/k6/*.js` | NOT installed on the authoring machine |

Set the JDK and Maven inline for your shell. Do **not** export `JAVA_HOME` globally (project convention); on the authoring machine:

```bash
JAVA_HOME=C:/Users/sahukari.rao/.jdks/graalvm-jdk-21.0.7
MVN="C:/Users/sahukari.rao/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn.cmd"
```

On any other machine use your own JDK 21 path and plain `mvn`. Use `JAVA_HOME="$JAVA_HOME" "$MVN" ...` for each command, as `scripts/run-local.sh` does.

### Environment file (`env.example`, not `.env.example`)

The root template is named `env.example` (no leading dot). The authoring environment's permission rules blocked writing a file called `.env.example`,
so the template was created without the dot (stated in the header of `env.example`). Use it exactly like the conventional file:

```bash
cp env.example .env       # then replace every CHANGE_ME value; .env is git-ignored (see .gitignore)
```

`.gitignore` ignores `.env` and `.env.*` but keeps `env.example`. `frontend/.env.example` (with a dot) does exist for the frontend only
(`VITE_API_BASE_URL`, optional `VITE_SHOW_DEMO_HINTS=true`). The local no-Docker run does not need a `.env` at all. `CX_JWT_SECRET` must be at least 32 characters
and identical for core-service, gateway and notification-service; `scripts/run-local.sh` supplies a dummy local-only default. Never commit real secrets.

## 2. Build

Backend (all modules in `pom.xml`: `platform-events`, `core-service`, `notification-service`, `gateway`, `dsa-lab`):

```bash
cd freshworks-cx-itsm-platform
JAVA_HOME="$JAVA_HOME" "$MVN" -B -q -DskipTests -pl core-service,gateway,notification-service -am package    # exactly what scripts/run-local.sh build runs
# or:  scripts/run-local.sh build
```

Produces `core-service/target/core-service-1.0.0.jar`, `gateway/target/gateway-1.0.0.jar` and `notification-service/target/notification-service-1.0.0-exec.jar` (standalone) plus a plain library jar.
Recorded as executed for core-service: `"$MVN" -pl core-service -am -DskipTests package` (`docs/core-service-validation.md` section 2).

Frontend:

```bash
cd frontend
npm ci
npm run build        # tsc --noEmit, then vite build -> dist/
```

`npm run build` is documented with a measured bundle report in `frontend/README.md`; the build command itself is not logged as a dated run.

## 3. Run locally (no Docker)

Profile `local`: H2 in-memory (PostgreSQL mode), Caffeine cache, in-process event relay, in-memory/JPA document store, embedded notifications, seeded demo data.

### One command

```bash
scripts/run-local.sh          # same as: scripts/run-local.sh up   (Ctrl+C stops everything)
```

It builds the jars if they are missing, then starts core-service on :8081 with `-Dcx.notifications.embedded=true`, the gateway on :8080 with
`-Droutes.core.uri=http://localhost:8081`, and (if `frontend/node_modules` exists) the Vite dev server on :5173 with `VITE_API_BASE_URL=http://localhost:8080`.
Logs go to `.run/core.log`, `.run/gateway.log`, `.run/frontend.log`.

**Status:** the script header says it "was written but NOT executed end-to-end by its author". Its component commands (core jar on profile local, smoke test) were executed
separately per `docs/core-service-validation.md`, so if the script misbehaves, run the three processes by hand:

```bash
# the SAME secret (>= 32 chars) must reach core and gateway; the built-in defaults differ (see note below)
export CX_JWT_SECRET='local-demo-only-secret-change-me-0123456789'   # per-shell variable, dummy value
# terminal 1: core (build first, section 2)
"$JAVA_HOME/bin/java" -Dcx.notifications.embedded=true -Dserver.port=8081 -jar core-service/target/core-service-1.0.0.jar
# terminal 2: gateway (CX_JWT_SECRET exported as above)
"$JAVA_HOME/bin/java" -Dserver.port=8080 -Droutes.core.uri=http://localhost:8081 -jar gateway/target/gateway-1.0.0.jar
# terminal 3: frontend
cd frontend && VITE_API_BASE_URL=http://localhost:8080 npm run dev -- --port 5173
```

(The core run recorded in `docs/core-service-validation.md` used `java -jar target/core-service-1.0.0.jar --server.port=8081`; startup took about 10.6 s and the seeder created 150/100/60 tickets.
The built-in dummy defaults for `CX_JWT_SECRET` differ between `core-service/src/main/resources/application.yml` and `gateway/src/main/resources/application.yml`, so starting both without the variable makes the gateway reject core-issued tokens; `scripts/run-local.sh` avoids this by exporting one value for both.)

### Ports and URLs

| What | URL / port |
|---|---|
| Frontend (Vite dev) | http://localhost:5173 |
| Gateway (the only URL the frontend talks to) | http://localhost:8080 |
| core-service | http://localhost:8081 |
| notification-service standalone (docker profile only; embedded in core locally) | :8082 |
| Gateway management (actuator, internal) | :8090 (`GATEWAY_MANAGEMENT_PORT`) |
| Prometheus / Grafana (docker stack only) | :9090 / :3000 |
| PostgreSQL / MongoDB / Redis / RabbitMQ AMQP / RabbitMQ UI (docker stack only) | 5432 / 27017 / 6379 / 5672 / 15672 |

Swagger UI: http://localhost:8081/swagger-ui.html (configured in `core-service/src/main/resources/application.yml`) and, through the gateway, http://localhost:8080/swagger-ui.html (the gateway
lists `/swagger-ui*` and `/v3/api-docs*` as public routes per `gateway/README.md`). OpenAPI JSON: `/v3/api-docs`. Health: http://localhost:8081/actuator/health. Metrics:
http://localhost:8081/actuator/prometheus (exposed endpoints: `health,info,prometheus,metrics`).

### Seed logins (from `docs/API-CONTRACT.md`)

Password for every seeded user: `Passw0rd!demo` (dummy demo value). Login body: `POST /api/v1/auth/login` `{"tenantSlug","email","password"}`.

| Tenant slug | Users |
|---|---|
| `acme-retail` (150 tickets) | `admin@acme.test`, `manager@acme.test`, `agent1@acme.test`, `agent2@acme.test`, `customer1@acme.test` |
| `globex-it` (100 tickets) | `admin@globex.test`, `agent1@globex.test`, `customer1@globex.test` |
| `initech-health` (60 tickets) | `admin@initech.test`, `agent1@initech.test` |

H2 is in-memory: all data resets on every restart.

### Smoke test (recorded: 57 of 57 checks passed on profile local)

```bash
node core-service/scripts/smoke-test.mjs                      # default base URL http://localhost:8081
node core-service/scripts/smoke-test.mjs http://localhost:8080  # optionally through the gateway (not recorded)
```

## 4. Run the docker profile (NOT executed here)

**Nothing in this section has been run.** Docker is not installed on the authoring machine; `infra/README.md` states "Verification status: NOT RUN" (only YAML/JSON syntax was parsed).
Treat these as instructions, not results.

```bash
cp env.example .env      # edit every CHANGE_ME value first; compose refuses to start when required secrets are missing
docker compose -f infra/docker-compose.yml --env-file .env up -d                              # postgres, mongo, redis, rabbitmq, prometheus, grafana
scripts/run-local.sh build                                                                   # produces the jars the app images copy
docker compose -f infra/docker-compose.yml --env-file .env --profile apps up -d --build      # + core, notification, gateway, frontend
```

Notes from `infra/README.md`: published ports bind to 127.0.0.1; the gateway actuator is on the internal port 8090 (not published); the Grafana latency panel needs
`management.metrics.distribution.percentiles-histogram.http.server.requests=true`; `VITE_API_BASE_URL` is baked into the frontend image at build time.
Still unverified: the PostgreSQL run of `db/migration` + `db/migration-pg` (RLS, partial/GIN/trigram indexes, audit trigger), Redis, RabbitMQ confirms, MongoDB and the `docker` profile boot.

## 5. Run each test suite

| Suite | Command (from repo root unless noted) | Recorded result |
|---|---|---|
| core-service (JUnit 5, MockMvc, H2; docker-tagged tests excluded) | `JAVA_HOME="$JAVA_HOME" "$MVN" -pl core-service -am test` | executed 2026-10-17: 179 run, 0 failures/errors/skipped, BUILD SUCCESS (`docs/core-service-validation.md`) |
| gateway | `"$MVN" -pl gateway -am test` | executed 2026-10-17: 14 run, 0 failures (whole-backend run) |
| notification-service | `"$MVN" -pl notification-service -am test` | executed 2026-10-17: 46 run, 0 failures; Docker IT excluded |
| dsa-lab | `"$MVN" -pl dsa-lab -am test` | executed 2026-10-17: 53 run, 0 failures |
| Whole backend | `"$MVN" -pl platform-events,notification-service,core-service,gateway,dsa-lab -am test` | executed 2026-10-17: BUILD SUCCESS; core 179, notification 46, gateway 14, dsa-lab 53, platform-events 0 tests = 292, 0 failures |
| Docker-tagged ITs (`PostgresRlsDockerIT`, `RabbitRedisDockerIT`, `MongoDocumentStoreDockerIT`, and `NotificationRabbitDockerIT` in its module) | `"$MVN" -pl core-service -am test -Pdocker-tests` (profile `docker-tests` in `core-service/pom.xml` clears the `docker` tag exclusion; needs Docker for Testcontainers) | **NOT executed** (7 core tests compiled only) |
| Frontend unit tests (Vitest + React Testing Library, jsdom) | `cd frontend && npm ci && npm test` | executed 2026-10-17: 86 of 86 passed (10 files) |
| Frontend static checks | `cd frontend && npm run typecheck` and `npm run lint` (ESLint, zero warnings allowed) | executed 2026-10-17: `npx tsc --noEmit` and `npm run lint` clean |
| Playwright e2e (17 tests, 11 spec files) | see section 6 | executed 2026-10-17: 17 run, 17 passed (needs the fault-injected second stack for 1 of them; otherwise 16 passed, 1 skipped); details in `docs/e2e-validation.md` |

Useful: run one core test class with `"$MVN" -pl core-service -am test -Dtest=TicketApiTest -Dsurefire.failIfNoSpecifiedTests=false`; reports are in `core-service/target/surefire-reports/`.

## 6. Playwright end-to-end tests

Specs: `e2e/tests/login.spec.ts`, `create-ticket.spec.ts`, `assign-transition.spec.ts`, `customer-restrictions.spec.ts`, `tenant-isolation.spec.ts`, `ai-approve.spec.ts`, `ai-reject.spec.ts`, `ai-degraded.spec.ts`, `kb.spec.ts`, `notifications.spec.ts`, `dashboard-admin.spec.ts` (helpers in `helpers.ts`).
`e2e/playwright.config.ts` starts only the Vite dev server (`npm run dev -- --host localhost --port 5173 --strictPort` in `frontend/`, reusing one if already running); the backend
must already be running (gateway :8080 -> core :8081, profile `local`, seed data), and the frontend must be pointed at the gateway.

```bash
# 1. backend up (section 3):  scripts/run-local.sh      (or the manual three terminals; frontend dev server may be left to Playwright)
# 2. in another terminal:
cd e2e
npm ci
npx playwright install chromium     # downloads the browser; required on a fresh machine (config uses the Chromium project only)
npm run test:list                   # lists tests without running them
npm test                            # = playwright test (workers 1, retries 0, 60 s timeout)
npm run typecheck                   # tsc --noEmit -p .
```

If the frontend is not already running with `VITE_API_BASE_URL=http://localhost:8080`, the Vite default applies (also `http://localhost:8080` per `frontend/README.md`). Failure traces are retained in `e2e/test-results/`.
`ai-degraded.spec.ts` is skipped unless `E2E_FAULT_BASE_URL` points at a second stack whose core runs with `-Dcx.ai.fault-mode=FAIL` (see `docs/e2e-validation.md` for the exact ports/commands).

**Status: executed 2026-10-17 on Windows, no Docker: 17 run, 17 passed, 0 failed (with the fault stack); 16 passed + 1 skipped without it.** The first run had 2 failures (one wrong selector, one cold Vite start timeout); no app code needed fixing. Full details: `docs/e2e-validation.md`. Screenshots: `docs/screenshots/`.

## 6b. Angular UI (`frontend-angular/`)

Second UI (Angular 20, standalone components, signals) against the same API; details in `frontend-angular/README.md` and `docs/frontend-angular-notes.md`.
Origin `http://localhost:4200` is in the core CORS allow-list by default.

```bash
cd frontend-angular
npm ci
npm start                 # ng serve on :4200, apiBaseUrl http://localhost:8080 (gateway)
npm run lint              # angular-eslint
CHROME_BIN="C:/Program Files/Google/Chrome/Application/chrome.exe" npm test    # Karma + Jasmine, headless Chrome, no watch
npm run build             # production build -> dist/frontend-angular
```

Real-backend smoke (login -> dashboard -> ticket -> create ticket, plus comments, assign/transition, AI, admin pages, KB, role guards, 401 handling),
talking straight to core on :8085 so no gateway is needed:

```bash
# terminal 1 (core jar built as in section 2)
java -jar core-service/target/core-service-1.0.0.jar --spring.profiles.active=local --server.port=8085
# terminal 2
cd frontend-angular && npm run start:e2e          # ng serve --configuration e2e (apiBaseUrl http://localhost:8085)
# terminal 3
cd frontend-angular && CHROME_BIN="C:/Program Files/Google/Chrome/Application/chrome.exe" npm run e2e:smoke
```

The smoke uses `puppeteer-core` from `tools/render/node_modules`. **Status: executed 2026-10-17 on Windows, no Docker: 25 of 25 steps passed; lint clean; 83 of 83 Karma tests passed; production build succeeded.**

## 7. Load tests

Neither is part of the unit-test build; start core first.

```bash
# no install needed (Node 18+); defaults: BASE_URL=http://localhost:8081, TENANT=acme-retail, EMAIL=agent1@acme.test, PASSWORD=<seed password>, scenario ticket-list
node perf/load-test.mjs
BASE_URL=http://localhost:8081 SCENARIO=mixed CONCURRENCY=20 DURATION_S=30 node perf/load-test.mjs      # scenarios: ticket-list | dashboard | ticket-create | mixed (70/20/10)
node perf/dashboard-cache-probe.mjs                                                                       # env: BASE_URL TENANT EMAIL PASSWORD HITS MISSES
```

`load-test.mjs`: recorded as run against a real core-service on profile local (H2, port 8093) for all four scenarios; numbers and caveats are in `PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md` section 3.
It exits 1 if any request failed. `dashboard-cache-probe.mjs`: syntax-checked only as a committed file (the guide says an ad-hoc equivalent produced its numbers).

```bash
# k6 (NOT executed: k6 was not installed on the authoring machine)
k6 run perf/k6/login.js        # also ticket-list.js, ticket-create.js, dashboard.js
```

k6 env: `BASE_URL` (default gateway `http://localhost:8080`), `TENANT`, `AGENT_EMAIL`, `MANAGER_EMAIL`, `PASSWORD`. Through the gateway, the per-user token bucket (default burst 100, 20/s)
returns 429 above that; raise `GATEWAY_RATE_CAPACITY` / `GATEWAY_RATE_REFILL_PER_SEC` for throughput tests or point `BASE_URL` at core. H2 numbers are not representative of PostgreSQL.

## 8. Which tests need Docker

| Test / suite | Needs Docker? | Default behaviour | Executed? |
|---|---|---|---|
| All 179 core-service tests in `core-service/src/test/java/com/example/cx/core/` excluding `docker/` and `MongoDocumentStoreDockerIT` | no (H2) | run by `mvn test` | yes (recorded) |
| `core-service/src/test/java/com/example/cx/core/docker/PostgresRlsDockerIT.java` (2 tests: migrations incl. `migration-pg`, RLS, `SET LOCAL`, audit trigger) | yes (Testcontainers PostgreSQL) | excluded (tag `docker`) | **no** |
| `core-service/src/test/java/com/example/cx/core/docker/RabbitRedisDockerIT.java` (2 tests: confirms/persistence/correlation header; Redis hit then outage) | yes (RabbitMQ, Redis) | excluded | **no** |
| `core-service/src/test/java/com/example/cx/core/docstore/MongoDocumentStoreDockerIT.java` (3 tests, `mongo:7.0`) | yes | excluded | **no** |
| `notification-service/src/test/java/com/example/cx/notification/rabbit/NotificationRabbitDockerIT.java` | yes (RabbitMQ) | excluded | **no** |
| Gateway, notification-service (other tests), dsa-lab | no | run by `mvn test` | per module READMEs |
| Vitest (`frontend/src/test/`) | no | `npm test` | executed 2026-10-17: 86/86 |
| Karma (`frontend-angular/src/**/*.spec.ts`) | no (needs headless Chrome) | `npm test` | executed 2026-10-17: 83/83 |
| Angular smoke (`frontend-angular/e2e/smoke.mjs`) | no Docker; needs running core + `ng serve` | `npm run e2e:smoke` | executed 2026-10-17: 25/25 steps |
| Playwright (`e2e/tests/`) | no Docker, but needs the running local stack and a browser | `npm test` in `e2e/` | executed 2026-10-17: 17/17 passed (`docs/e2e-validation.md`) |
| Load tests (`perf/`) | no (target running core); k6 needs the k6 binary | manual | `load-test.mjs` yes (local H2); k6 no |
| `docker compose` stack | yes | manual | **no** |

## 9. Troubleshooting setup issues

| Symptom | Likely cause and fix |
|---|---|
| `mvn: command not found` / wrong Java version | No `mvnw` in the repo. Point `MVN` at your Maven 3.9 (on Windows use `mvn.cmd`) and set `JAVA_HOME` to a JDK 21 inline (`java.version` is 21; JDK 17 fails to compile). |
| `scripts/run-local.sh` cannot find `mvn.cmd` or the JDK | Its defaults are the authoring machine's paths. Override inline: `JAVA_HOME=... MVN=... scripts/run-local.sh`. |
| `!! frontend/node_modules missing` message from the script | Run `npm ci` in `frontend/`, then re-run; the script skips the dev server otherwise. |
| Port already in use (8080, 8081, 5173) | Stop the other process or change `-Dserver.port` / `--port`; the Playwright config uses `--strictPort` for 5173 and will fail if it is taken by something that is not the right app. |
| Gateway returns 401 for every call, or core rejects gateway-forwarded tokens | `CX_JWT_SECRET` differs between processes, or is shorter than 32 characters. Use the same value for core, gateway and notification-service. |
| Gateway returns 429 `RATE_LIMITED` during load tests | Per-user token bucket (burst 100, 20/s). Raise `GATEWAY_RATE_CAPACITY` / `GATEWAY_RATE_REFILL_PER_SEC` or target core on :8081. Login is rate-limited separately. |
| Gateway returns 503 `UPSTREAM_UNAVAILABLE` | Core is not running or `routes.core.uri` / `CORE_URI` is wrong. Check `.run/core.log`. |
| Login says invalid credentials | Use the tenant slug **and** email, e.g. `acme-retail` + `agent1@acme.test`, password `Passw0rd!demo`. Emails are tenant-scoped; the same email does not work across tenants. |
| Data disappears after restart | Expected: H2 is in-memory on profile `local`. |
| Notifications bell empty / no notifications locally | Notifications are embedded in core only when `-Dcx.notifications.embedded=true` is set (the run script does this); otherwise the gateway routes `/api/v1/notifications/**` to a service that is not running. |
| Browser CORS error | The frontend must call the gateway (`VITE_API_BASE_URL=http://localhost:8080`) from origin `http://localhost:5173` (React) or `http://localhost:4200` (Angular); other origins are denied by the allow-list `cx.cors.allowed-origins` (env `CX_CORS_ALLOWED_ORIGINS`, comma separated), tested in `PlatformEndpointsTest`. |
| Playwright: "Executable doesn't exist" | Run `npx playwright install chromium` in `e2e/`. |
| Playwright tests fail on login or fixtures | The backend is not running with seed data on profile `local`, or the stack was restarted mid-run (state resets). Restart the backend and re-run. |
| `npm run build` fails in `tsc` | Build runs `tsc --noEmit` first; fix type errors, or run `npm run typecheck` to see them alone. |
| `npm run lint` fails | ESLint is configured with `--max-warnings=0`; warnings fail the command. |
| `mvn test` appears to skip Docker tests | By design: surefire `excludedGroups=docker`. Use `-Pdocker-tests` on a machine with Docker. |
| Compose refuses to start ("required variable ... is missing") | `${VAR:?...}` guards: copy `env.example` to `.env` and set every `CHANGE_ME` value. |
| Grafana latency panel empty | Enable `management.metrics.distribution.percentiles-histogram.http.server.requests=true` (see `infra/README.md`). |
| Prometheus cannot scrape host-run services | Edit `infra/prometheus/prometheus.yml` targets to `host.docker.internal:<port>` when services run on the host. |
| Hibernate `ddl-auto: validate` failure after editing an entity | Add a Flyway migration under `core-service/src/main/resources/db/migration/`; never edit an applied one. |
| Windows path problems in Git Bash | Use forward slashes and quote paths with spaces; call `mvn.cmd` explicitly. |

More runbooks (queue backlog, Redis outage, pool exhaustion, slow queries) are in `PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md`; its PostgreSQL commands are marked NOT EXECUTED.
