# Performance and Troubleshooting Guide

Scope: `core-service`, `notification-service`, `gateway`, `frontend` of this repository (a self-built learning project, not Freshworks code).

**Labels used in this document**
- **MEASURED**: I ran it on the authoring machine on 2026-10-17 and the numbers below are copied from the terminal output.
- **H2 ONLY**: run against H2 in PostgreSQL mode. Useful for plan shape and rows examined; says nothing about PostgreSQL timings.
- **NOT EXECUTED**: a command or procedure that needs Docker (PostgreSQL, Redis, RabbitMQ, MongoDB), a browser profiler, or a tool not installed here. Provided as instructions, never as results.

No number in this guide is a Freshworks metric, a production figure, or an SLO. Local numbers measure this code on one laptop only.

---

## 1. Environment of the measured runs

| Item | Value |
|---|---|
| Machine | Windows 11 laptop, Intel Core i5-1135G7 @ 2.40 GHz, 4 cores / 8 threads (RAM size not captured) |
| JDK | Oracle GraalVM 21.0.7+8 (`C:/Users/sahukari.rao/.jdks/graalvm-jdk-21.0.7`), default G1 GC, no `-Xmx`, no JVM tuning |
| Node (load generator) | v24.13.1, `perf/load-test.mjs` |
| App under test | `core-service` jar copied from `core-service/target/core-service-1.0.0.jar` (built 13:14, contains no KB/AI classes), profile `local` (H2 in memory, Caffeine cache, in-JVM events), started on port **8093** (not 8081) and stopped afterwards |
| Start command | `java -Dcx.notifications.embedded=true -jar core.jar --server.port=8093` (the `embedded` flag had no effect in these first runs: that jar contained no notification classes. Since the later fix the jar embeds the notification module and profile `local` enables it by default; the section 3.1.1 re-measurement used the new jar on port **8094**) |
| Data | seeded: tenant `acme-retail` 150 tickets, `globex-it` 100, `initech-health` 60. Ticket-create runs add rows to `acme-retail`, so later runs see larger tenants (stated per table) |
| Topology | load generator and server on the **same machine** over loopback. No gateway, no network latency, no TLS. The load generator competes with the server for CPU |
| Background work running during tests | outbox relay (every 1 s), SLA job (every 60 s), Hikari pool of 10, Tomcat defaults |
| Docker | not installed: no PostgreSQL, Redis, RabbitMQ, MongoDB |

Caveats that apply to every number below: H2 in memory is far faster than a networked PostgreSQL; Caffeine is in-process while Redis adds a network hop;
single run each (no repetitions except where stated) so run-to-run variance is unknown except for the ticket-create series, which showed large variance; the first
scenario ran about 25 s after JVM start, so JIT warm-up is only partly excluded by the 3 s warm-up window.

---

## 2. How to reproduce (copy-paste)

```bash
# 1. build (from the repo root; paths per docs/ARCH-CONSTRAINTS.md)
export JAVA_HOME=C:/Users/sahukari.rao/.jdks/graalvm-jdk-21.0.7
MVN="C:/Users/sahukari.rao/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn.cmd"
"$MVN" -pl core-service -am -DskipTests package

# 2. run a throwaway instance on a NON-default port (profile local is the default)
"$JAVA_HOME/bin/java.exe" -jar core-service/target/core-service-1.0.0.jar --server.port=8093 &

# 3. load test (no installs; Node 18+). Dashboard and mixed need a MANAGER login:
BASE_URL=http://localhost:8093 SCENARIO=ticket-list   CONCURRENCY=10 DURATION_S=20 WARMUP_S=3 node perf/load-test.mjs
BASE_URL=http://localhost:8093 SCENARIO=dashboard     CONCURRENCY=10 DURATION_S=20 WARMUP_S=3 EMAIL=manager@acme.test node perf/load-test.mjs
BASE_URL=http://localhost:8093 SCENARIO=ticket-create CONCURRENCY=10 DURATION_S=20 WARMUP_S=3 EMAIL=manager@acme.test node perf/load-test.mjs
BASE_URL=http://localhost:8093 SCENARIO=mixed         CONCURRENCY=10 DURATION_S=20 WARMUP_S=3 EMAIL=manager@acme.test node perf/load-test.mjs

# 4. cache hit vs miss for one dashboard request (leaves synthetic tickets behind)
BASE_URL=http://localhost:8093 node perf/dashboard-cache-probe.mjs

# 5. H2 plan evidence (needs the H2 jar from ~/.m2; prints index choice and rows examined)
"$JAVA_HOME/bin/java.exe" -cp ~/.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar perf/sql/H2Explain.java .

# 6. stop the instance by PID afterwards (do not leave it running)
```
Notes: a default `agent1@acme.test` login is used for `ticket-list`; `dashboard`/`mixed` need `EMAIL=manager@acme.test` because the dashboard needs MANAGER+ (an agent gets 403, which
the tool counts as errors). The tool exits 1 if any request failed. `perf/dashboard-cache-probe.mjs` and `perf/sql/H2Explain.java` were added in this documentation pass;
the probe is the same logic as an ad-hoc script that produced the numbers in section 4 (the committed file was syntax-checked only). The k6 scripts in `perf/k6/` were **not executed** (k6 not installed).

Through the gateway (port 8080) the per-user token bucket (default burst 100, 20/s) will return 429; raise `GATEWAY_RATE_CAPACITY` and `GATEWAY_RATE_REFILL_PER_SEC` for throughput tests. Login is
limited separately by core's `LoginRateLimiter`: in profile `local` it is 100 burst, 60 per minute.

---

## 3. MEASURED: load-test results (core-service direct, profile local, concurrency 10 unless stated)

Command shape: `node perf/load-test.mjs` with the environment shown in section 2; window 20 s after 3 s warm-up (15 s for the c=1 and the follow-up create series).

| # | Scenario | Tenant size at start | Requests | Errors | RPS | p50 ms | p95 ms | p99 ms | max ms |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | `ticket-list` (`GET /tickets?page=0&size=20`), agent | 150 | 9 516 | 0 | 475.5 | 14.0 | 58.4 | 91.3 | 240.4 |
| 2 | `dashboard` (`GET /analytics/dashboard?days=30`), manager | 150 | 28 061 | 0 | 1 402.1 | 5.1 | 17.9 | 37.6 | 160.5 |
| 3 | `ticket-create` (POST with unique `Idempotency-Key`), manager | 150 | 2 085 | 0 | 104.0 | 28.9 | 200.6 | 1 943.5 | 7 707.1 |
| 4 | `mixed` (70% list, 20% dashboard, 10% create), manager | about 2 235 | 11 849 | 0 | 592.1 | 11.9 | 49.2 | 82.1 | 174.6 |
| 5 | `ticket-list`, **concurrency 1**, agent | about 3 400 | 3 292 | 0 | 219.4 | 4.2 | 6.6 | 8.3 | 17.1 |

Status counts: all 200 (list, dashboard, mixed read part) or 201 (create). Zero 4xx/5xx and no network errors in any run.

Reading the table:
- Row 5 vs row 1 (the tenant had grown from 150 to about 3 400 tickets between them; with a page size of 20 that is unlikely to matter much, but it was not isolated): with one in-flight request the median is 4.2 ms; at 10 in-flight it is 14.0 ms. Throughput rose 2.2x (219 to 475 RPS) for 10x the concurrency, so the box (4 cores shared with the
  load generator) was already close to saturation. Latency growth under concurrency is queuing, not slower code.
- Row 2 vs row 1: the dashboard path is served almost entirely from the cache (see section 4), so it is about 3x the throughput of the uncached list path. This is an effect of caching, not
  of the dashboard being "cheaper" to compute (a miss is slower than a list page: section 4).
- Row 3: create is the only scenario with a heavy tail. See section 3.1.

### 3.1 Ticket-create tail latency: what was measured and what was not proven

Follow-up series on one freshly started instance (150 tickets at the start of the first row; the four rows ran back to back in this order, so later rows see a larger tenant; same command, 15 s window):

| Concurrency | Requests | RPS | p50 ms | p95 ms | p99 ms | max ms |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 802 | 53.5 | 17.5 | 28.5 | 34.5 | 44.9 |
| 10 | 1 670 | 110.9 | 31.1 | 401.6 | 1 082.0 | 2 828.7 |
| 10 (repeat) | 2 214 | 147.3 | 24.3 | 313.3 | 815.5 | 1 660.1 |
| 20 | 3 246 | 215.3 | 58.1 | 117.2 | 843.6 | 4 405.0 |

Observations: the tail appears only with concurrency (c=1 max 44.9 ms) and varies a lot between identical runs (p99 1 082 vs 816 ms), so single numbers must not be quoted as a benchmark.

Metrics read from `/actuator/prometheus` after this series:
- `jvm_gc_pause_seconds_max` = 0.010 s and total young-GC pause 0.451 s over 101 pauses: **GC does not explain multi-second tails**.
- `hikaricp_connections_acquire_seconds_max` = 0.106 s and `hikaricp_connections_timeout_total` = 0: the pool was **not** exhausted; threads got connections quickly.
- `hikaricp_connections_usage_seconds_max` = 6.077 s: some transaction held its connection for six seconds. Time spent *inside* a transaction while latency is high points to waiting on a lock or on
  the DB, not on the pool.

**Hypothesis (from the first pass): the per-tenant `ticket_counter` row lock.** Every create ran `select ... for update` on the tenant's counter row
(`TicketCounterRepository.lockByTenantId`) and kept it until the create transaction committed (ticket, activity, audit, outbox and idempotency rows), so all
creates of one tenant queued behind each other.

#### 3.1.1 Experiment (confirmed) and fix, re-measured

Setup (second pass): the rebuilt `core-service` jar (now with the embedded notification module and the outbox purge job; profile `local`, H2, Caffeine) on port
**8094**, started and stopped by PID; `perf/load-test.mjs` with `SCENARIO=ticket-create`, 15 s window after 3 s warm-up, the same laptop, load generator on the same machine.
Different tenants have different counter rows, so spreading the same number of clients over three tenants removes exactly the suspected contention and nothing else.

BEFORE the fix (code still taking the lock inside the create transaction; one fresh JVM, rows run back to back, so the JIT warmed up as it went; runs vary a lot):

| Clients | Tenants | Requests | RPS | p50 ms | p95 ms | p99 ms | max ms |
|---|---|---:|---:|---:|---:|---:|---:|
| 1 | acme | 818 | 54.5 | 16.5 | 31.4 | 49.2 | 59.5 |
| 10 | acme | 2 233 | 148.5 | 21.8 | 282.1 | 1 032.6 | 3 471.8 |
| 10 (repeat) | acme | 2 975 | 197.8 | 19.0 | 214.8 | 577.7 | 1 613.3 |
| 20 | acme | 4 143 | 275.2 | 45.8 | 66.8 | 100.8 | 12 753.2 |
| 4+3+3 = 10 | acme / globex / initech in parallel | 2 906 / 2 775 / 2 777 | 193.6 / 184.9 / 185.1 | 16.9 / 13.6 / 13.2 | 45.2 / 33.1 / 35.1 | 68.6 / 51.3 / 53.7 | 174.1 / 203.3 / 86.0 |
| 4+3+3 = 10 (repeat) | same | 2 980 / 2 899 / 3 049 | 198.3 / 193.1 / 203.2 | 15.8 / 12.8 / 12.6 | 48.5 / 32.2 / 29.9 | 71.4 / 51.2 / 44.5 | 155.7 / 140.9 / 83.2 |
| 10 (control, warmer JVM) | acme | 5 473 | 364.4 | 10.8 | 123.9 | 261.3 | 506.4 |
| 4 | acme | 2 318 | 154.2 | 17.9 | 65.1 | 139.2 | 311.8 |
| 10 | acme | 3 002 | 199.7 | 18.7 | 225.7 | 476.9 | 980.4 |
| 20 | acme | 6 132 | 407.8 | 30.3 | 46.9 | 69.3 | 12 939.6 |
| 7+7+6 = 20 | acme / globex / initech in parallel | 3 712 / 3 680 / 3 665 | 246.9 / 244.9 / 244.1 | 24.3 / 24.3 / 21.2 | 59.8 / 61.1 / 50.9 | 86.1 / 87.2 / 74.6 | 126.5 / 150.0 / 177.5 |

Reading: with the tenants separated, the clients have p99 of about 45-87 ms and max at most about 205 ms; ten clients on one tenant have p99 of 260-1 030 ms and max up to 3.5 s, and at 20 clients one request
stalled for 12.8-12.9 s in both single-tenant runs while the median stayed at 30-46 ms (an unlucky waiter that is not served in FIFO order, consistent with row-lock waiting; the individual stalled request
was not traced). Aggregate throughput also rose when the clients were spread. **Conclusion: the per-tenant counter lock is the dominant cause of the create tail on this setup (confirmed by the differential
experiment, not by a lock trace).** Other costs (GC, pool) were already ruled out above.

Fix: `TicketNumberAllocator` allocates the number with one atomic `UPDATE ticket_counter SET next_number = next_number + 1` in its **own tiny transaction before** the business transaction starts
(`TicketCommandService.create`), so the row lock is held for well under a millisecond instead of until commit. Per-tenant uniqueness is kept (the UPDATE serialises allocations, and
`(tenant_id, number)` stays unique in `ticket`). **Trade-off: numbers are no longer gap-free**: a create that fails after allocation (validation error, idempotency-key race) burns its number. The allocator must be called
while no transaction is open (otherwise it would need a second pooled connection per request); `TicketCommandService` is the only caller.

AFTER the fix (new jar, same procedure, fresh JVM, port 8094):

| Clients | Tenants | Requests | RPS | p50 ms | p95 ms | p99 ms | max ms |
|---|---|---:|---:|---:|---:|---:|---:|
| 1 | acme | 918 | 61.2 | 15.6 | 23.5 | 30.1 | 40.6 |
| 10 | acme | 4 510 | 300.3 | 28.5 | 65.6 | 83.7 | 151.4 |
| 10 (repeat) | acme | 5 296 | 352.6 | 23.7 | 59.1 | 80.7 | 219.9 |
| 20 | acme | 6 135 | 408.3 | 44.7 | 95.5 | 132.9 | 229.6 |
| 20 (repeat) | acme | 7 234 | 481.7 | 37.7 | 77.8 | 107.5 | 186.3 |
| 4+3+3 = 10 | acme / globex / initech in parallel | 2 995 / 2 221 / 2 435 | 199.5 / 147.7 / 162.3 | 17.3 / 16.9 / 16.3 | 39.0 / 40.1 / 33.5 | 82.9 / 92.5 / 79.2 | 208.6 / 254.0 / 236.2 |

All runs: 0 errors, all responses 201; the server log of the "after" run had no ERROR lines. Single-tenant c=10 p99 went from 477-1 033 ms to 81-84 ms and max from 0.98-3.5 s to 0.15-0.22 s; single-tenant c=20 max from
12.8-12.9 s to 0.19-0.23 s, while its p99 changed from 69-101 ms to 108-133 ms (slightly worse: more requests now run truly in parallel and compete for the 4 cores and the Hikari pool of 10, so the median and p95 are higher too).
Caveats: single laptop, load generator on the same machine, H2 in memory (a PostgreSQL row lock behaves similarly in kind, but the numbers would differ; **not measured on PostgreSQL**), 1-2 runs per row, so the exact
percentiles are indicative; the "before" and "after" series ran on different JVM instances. `TicketNumberAllocatorTest` (3 tests) and the unchanged `TenancyTest` (10 parallel creates give exactly 1..10) check correctness; the
gap behaviour is asserted in `aFailedCreateBurnsANumberButNumbersStayUniqueAndIncreasing`.

---

## 4. MEASURED: cached vs uncached dashboard

The dashboard (`AnalyticsService.dashboard`) is cached per tenant for 60 s (`cache` label `analytics`) and evicted after the commit of any ticket write. The probe
(`perf/dashboard-cache-probe.mjs` logic) times single requests: HIT = repeated GETs inside the TTL; MISS = create a ticket (evicts) then GET once.

| Run | JVM state | Tickets in tenant | First call | HIT median (min-max) | MISS median (min-max) |
|---|---|---:|---:|---|---|
| A | fresh JVM, about 25 s after start | 150 | 119.5 ms (cold JIT and cache miss) | n=30: 11.02 ms (9.30-16.21) | n=15: 26.81 ms (18.53-47.46) |
| B | warm (after roughly 50 000 earlier requests) | 3 753 | n/a (cache already warm) | n=30: **3.23 ms** (2.36-5.16) | n=15: **21.73 ms** (19.72-23.61) |

Takeaways (single instance, H2, Caffeine, loopback):
- A hit is 6.7x faster than a miss on the warm JVM (3.23 vs 21.73 ms medians). Cold JVM numbers are noisier and slower for both.
- A miss runs about 9 queries (counted from `AnalyticsService.compute`, not measured) and folds up to 50 000 projected rows in memory; the 21.7 ms was at 3 753 tickets. It grows with tenant size (not measured beyond this).
- Under the write-heavy load of section 3, the cache counters (`cx_cache_total{cache="analytics"}`) read **31 983 hits and 1 697 misses = 95.0% hit ratio**, because the create/mixed scenarios evict the
  tenant's analytics entries on every write. A read-mostly tenant would be higher; a write-heavy one lower.
- With Redis (docker profile) a hit adds a network round trip and JSON decode; a Redis outage turns every request into a miss plus counted fallbacks. **NOT EXECUTED**.

Cache label names you will see in `cx_cache_total{cache=...}`: `analytics`, `categories`, `sla`, `settings`, `workflow`, and (KB module, not in the measured jar) `kb`.
Other caches in the same run (from `/actuator/prometheus`): `settings` 3 925 hits / 3 misses, `sla` 8 289 / 3, `workflow` 3 617 / 1, `categories` 5 / 1: reference data is effectively always warm.

---

## 5. Profiling how-to

### 5.1 Already executed
- **Actuator metrics during a run** (MEASURED): `GET /actuator/prometheus` gives HTTP, Hikari, GC, JVM and `cx_*` metrics (used above). Per-endpoint average latency is `http_server_requests_seconds_sum / _count`;
  `_max` is available; **percentile histograms are off by default** (see section 8 to enable).
- **N+1 guard** (MEASURED, section 6).

### 5.2 CPU and lock profiling with JFR (NOT EXECUTED)
```bash
java -XX:StartFlightRecording=duration=60s,filename=cx.jfr,settings=profile -jar core-service/target/core-service-1.0.0.jar --server.port=8093
# run the load test, then:
jfr print --events jdk.JavaMonitorEnter,jdk.ThreadPark cx.jfr | head      # lock waits
jfr summary cx.jfr ; open cx.jfr in JDK Mission Control (Method Profiling, Lock Instances, GC)
```
Look for: top frames in Hibernate flush/dirty checking, JSON serialisation, BCrypt (login is intentionally CPU heavy at cost 10), threads blocked in the JDBC driver on a row lock (the `ticket_counter` lock was removed from the create transaction, section 3.1.1; look at other rows now).
Thread dump of a live process: `jcmd <pid> Thread.print`, count `HikariPool` waiters and threads in `RUNNABLE` inside the driver.

### 5.3 SQL statement visibility (NOT EXECUTED here, standard Spring/Hibernate switches)
- Statement counts: `-Dspring.jpa.properties.hibernate.generate_statistics=true` (already `true` in the test profile, `false` otherwise) and read `SessionFactory.getStatistics()`; this is what `NPlusOneGuardTest` does.
- Statement text: `-Dlogging.level.org.hibernate.SQL=DEBUG` (the default is WARN, bodies and params are not logged). Do not enable parameter logging in shared environments (PII).
- PostgreSQL: `pg_stat_statements` (section 7).

### 5.4 Frontend profiling (NOT EXECUTED)
Chrome DevTools Performance and Network panels (throttle to "Fast 4G"), React DevTools Profiler (record a ticket-list filter change), Lighthouse on the built app (`npm run build && npm run preview`).
Bundle composition: `npx vite-bundle-visualizer` (not installed here). Section 9 has the bundle sizes that *were* measured.

### 5.5 Gateway
Counters `gateway_proxied_total{route,status}`, `gateway_rate_limited_total`, `gateway_upstream_errors_total{route,type}` on the management port (`:8090/actuator/prometheus`). The gateway was **not** load tested in this pass.

---

## 6. MEASURED: N+1 evidence

`NPlusOneGuardTest` (`core-service/src/test/java/com/example/cx/core/tickets/NPlusOneGuardTest.java`) creates 30 tickets (with attachments, categories and a mix of requesters/assignees) and
counts JDBC statements prepared by Hibernate (`Statistics.getPrepareStatementCount()`) for `GET /tickets?size=2` and `GET /tickets?size=30`.

I re-ran it in an isolated copy of the sources (the real tree was being edited by another agent) with one `System.out.println` added to print the counts:

```
NPLUS1 small(size=2)=6 large(size=30)=6
```
Command: `mvn -o -pl core-service -am test -Dtest=NPlusOneGuardTest` in the copy; build and test passed. **15x more rows, the same 6 statements.** The 6 are: page query, count query, actor lookup, users `IN`, categories `IN`,
attachments `IN`. The test also asserts `<= 8` and that a single-ticket GET costs `<= 6`.

Why it holds: entities store ids as plain columns (no lazy associations) and `TicketMapper.toDtos` resolves names with one batched query per kind. For contrast, a naive mapper doing one user lookup
for requester, one for assignee and one category lookup per row would issue `1 + 3N` statements: 91 for N=30 (arithmetic, hypothetical, not measured). The H2 plans in section 7 show why the
count matters less than the plan of each statement, though.

Detecting a regression in production: compare `rate(http_server_requests_seconds_count)` against DB statement counts (PostgreSQL `pg_stat_statements.calls`) per endpoint; a ratio that grows with page size is the signature.

---

## 7. SQL: slow versus optimised

### 7.1 H2 ONLY: plan evidence (MEASURED with `perf/sql/H2Explain.java`)

Data: the real Flyway migrations V1..V15 loaded into H2 2.2.224 (PostgreSQL mode); 3 tenants with 50 000 / 20 000 / 5 000 tickets. "scanCount" is the number of rows H2 examined (`EXPLAIN ANALYZE`).
Wall-clock timing was **discarded**: at this size it was dominated by parsing and H2's result cache and was not reliable.

| Query | Index chosen by H2 | Rows examined | Reading |
|---|---|---:|---|
| Q1 list page: `tenant_id = ? and status = 'OPEN' order by created_at desc, id desc limit 20` | `ix_ticket_sla_scan (tenant_id, status, sla_checked_at)` | **14 287** | Uses an index for the filter, but all of the tenant's OPEN rows are read and then sorted: no index provides the `created_at` order |
| Q1 on a copy of the table without indexes | table scan | **75 001** | What the missing-index case costs: 5.2x more rows examined |
| Q2 `status = 'OPEN'` **without** the tenant predicate | table scan | **75 001** | Every `tenant_id`-led index is useless without a tenant predicate: the "tenant first" rule is also a performance rule |
| Q3 `lower(title) like '%jammed ticket 4999%'` (the portable search form) | tenant index only | **50 001** | Scans all of the tenant's rows; a leading wildcard cannot use a btree |
| Q4 offset page: `... limit 20 offset 40000` | tenant index | **50 001** | Reads and discards 40 000 rows |
| Q4b keyset page at the same position: `(created_at, id) < (last seen)` | `ix_ticket_tenant_created` | **10 001** | 5x fewer rows examined (H2 still reads more than 20: its row-value range handling is limited) |
| Q5 open count by priority (dashboard) | `ix_ticket_tenant_status_prio_created` | 50 001 | `status not in (...)` is a range of two excluded values, so the whole tenant index range is read |
| Q1 again with an extra **candidate** index `(tenant_id, status, created_at desc, id desc)` | H2 still chose `ix_ticket_sla_scan` | 14 287 | **No improvement shown on H2**: its planner did not pick the new index. This neither proves nor disproves the idea for PostgreSQL |

Conclusions that are safe to draw: indexes lead with `tenant_id` and must be used with a tenant predicate (Q2); contains-search and deep offsets scale with tenant size (Q3, Q4);
a keyset form examines fewer rows (Q4b). Conclusions that are **not** safe: any speed-up figure, or that the candidate index helps (needs PostgreSQL, below).

### 7.2 PostgreSQL EXPLAIN ANALYZE: commands and what to look for (**NOT EXECUTED**)

Run these on a PostgreSQL 16 instance loaded with realistic data, as the application role, with a tenant bound (so RLS applies exactly as in the app):

```sql
BEGIN;
SELECT set_config('app.tenant_id', '<tenant-uuid>', true);   -- same call TenantAwareJpaTransactionManager makes

-- Q1: ticket list (the hot path)
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM ticket
WHERE tenant_id = '<tenant-uuid>' AND status = 'OPEN'
ORDER BY created_at DESC, id DESC LIMIT 20;
ROLLBACK;
```
What to look for: `Index Scan` or `Index Only Scan` (not `Seq Scan`) on a `tenant_id`-led index; a `Sort` node with `Sort Method: top-N heapsort` and a large `Rows Removed by Filter` means the index does not match the ordering (the H2 result suggests
this); compare `Buffers: shared hit/read` and `Execution Time`; check that RLS adds `Filter: (tenant_id = cx_current_tenant())` and that `cx_current_tenant()` being `STABLE` lets the planner treat it as a constant.
Candidate improvement to test (not part of the migrations): `CREATE INDEX CONCURRENTLY ix_ticket_tenant_status_created ON ticket (tenant_id, status, created_at DESC, id DESC);` then re-run and compare `Execution Time` and buffers;
also test the existing partial index `ix_ticket_open_queue (tenant_id, priority, created_at) WHERE status NOT IN ('RESOLVED','CLOSED')` (V1001) for the open-queue query.

```sql
-- Q3: contains-search, before and after the trigram index from V1001 (needs pg_trgm)
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM ticket
WHERE tenant_id = '<tenant-uuid>' AND lower(title) LIKE '%jammed%';
```
Expected shape to confirm: without `ix_ticket_title_trgm` a `Seq Scan` or large tenant index range; with it a `Bitmap Index Scan on ix_ticket_title_trgm` then `Bitmap Heap Scan`. Note the application's portable query also ORs `lower(description) LIKE`, which the
trigram index on `title` does **not** cover, so the OR may force a scan anyway: verify before promising an improvement.

```sql
-- Q4 vs Q4b: offset versus keyset on a large tenant
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM ticket WHERE tenant_id = '<tenant-uuid>'
ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 40000;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM ticket WHERE tenant_id = '<tenant-uuid>'
AND (created_at, id) < ('<last_created_at>', '<last_id>') ORDER BY created_at DESC, id DESC LIMIT 20;
```
Look for: `Rows Removed`/`actual rows` on the index scan node (offset reads 40 020 index entries), versus about 20 for keyset using `ix_ticket_tenant_created`.

```sql
-- Per-request overhead: revoked_token lookup and outbox poll
EXPLAIN (ANALYZE, BUFFERS) SELECT 1 FROM revoked_token WHERE jti = '<jti>';          -- expect Index Only Scan on pk_revoked_token
EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM outbox_event WHERE status = 'PENDING' AND next_attempt_at <= now()
ORDER BY created_at, id LIMIT 50;                                                   -- expect ix_outbox_poll; watch for Sort and for a growing table
```

Cross-cutting diagnostics (all **NOT EXECUTED**): `CREATE EXTENSION pg_stat_statements;` then
`SELECT calls, mean_exec_time, rows, left(query,120) FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 20;`;
unused indexes: `SELECT relname, indexrelname, idx_scan FROM pg_stat_user_indexes WHERE idx_scan = 0;`;
bloat/vacuum: `SELECT relname, n_dead_tup, last_autovacuum FROM pg_stat_user_tables ORDER BY n_dead_tup DESC LIMIT 10;` (outbox and idempotency tables churn).

---

## 8. Making latency observable (config changes proposed, not applied)

- Enable server-side percentiles for the p95 Grafana panel: `management.metrics.distribution.percentiles-histogram.http.server.requests=true` (the panel is empty without it; stated in `infra/README.md`).
- Name the Hikari pool and set explicit sizes: `spring.datasource.hikari.maximum-pool-size`, `connection-timeout`, and a `leak-detection-threshold` in non-production.
- Done: `OutboxPurgeJob` deletes SENT `outbox_event` rows older than `cx.outbox.retention-days` (7) hourly in bounded batches (`cx.outbox.purge-*`).

---

## 9. Frontend bundle sizes

From `frontend/README.md` ("measured" by the frontend author with `npm run build`); I cross-checked the files in `frontend/dist/assets` (built 12:37) and the raw sizes match to the byte-rounding (react 164 459 B,
forms 93 856 B, query 41 544 B, index 32 383 B). I did **not** re-run `npm run build` in this pass.

| Chunk | Size | gzip |
|---|---:|---:|
| react (react, react-dom, router) | 164.5 kB | 53.7 kB |
| forms (react-hook-form, resolvers, zod) | 93.9 kB | 26.4 kB |
| query (TanStack Query) | 41.5 kB | 12.5 kB |
| index (shell, login, UI kit) | 32.4 kB | 11.6 kB |
| TicketDetailPage | 15.4 kB | 5.0 kB |
| WorkflowsPage | 8.1 kB | 2.8 kB |
| DashboardPage | 5.7 kB | 2.3 kB |
| other pages | 0.3 to 5.3 kB each | under 2.3 kB |
| CSS | 12.3 kB | 3.4 kB |

First visit to `/login` loads react + query + forms + index, about 104 kB gzip. Every page is `React.lazy`. Static assets are served with `Cache-Control: public, immutable` and `expires 1y` by `infra/docker/nginx.conf`
(hashed file names), `index.html` is not cached long. Not measured: real-device load time, Largest Contentful Paint, runtime render cost.

---

# Troubleshooting runbooks

Conventions: metrics are Prometheus names as emitted by the code (Micrometer dots become underscores; counters get `_total`). `core` metrics live on `:8081/actuator/prometheus`, the gateway on its management port `:8090`.
Commands that need the docker stack are **NOT EXECUTED** by me; commands against the local app (`/actuator/*`) were exercised where stated. `curl` examples assume you set `TOKEN` yourself; never paste real tokens into tickets or chats.

Quick map of metric names:

| Area | Metric (Prometheus) | Source |
|---|---|---|
| HTTP | `http_server_requests_seconds_count|sum|max{uri,status,method}` | Spring Boot |
| DB pool | `hikaricp_connections_active|idle|pending|max|min`, `hikaricp_connections_acquire_seconds_*`, `hikaricp_connections_usage_seconds_*`, `hikaricp_connections_timeout_total` | Hikari |
| Cache | `cx_cache_total{cache,result}`, `cx_cache_fallback_total{op}` | `CacheService` |
| Outbox | `cx_outbox_enqueued_total{type}`, `cx_outbox_published_total{type}`, `cx_outbox_retried_total{type}`, `cx_outbox_failed_total{type}` | `OutboxService`, `OutboxRelay` |
| Tickets | `cx_tickets_total` | `TicketService` |
| Auth | `cx_auth_login_total{result}` | `AuthService` |
| SLA job | `cx_sla_job_runs_total`, `cx_sla_job_errors_total` | `SlaMonitorJob` |
| Workflow | `cx_workflow_fallback_total{reason}` | `WorkflowService` |
| AI | `cx_ai_analyze_total{result=ok|degraded|rate_limited}`, `cx_ai_meta_failed_total` | `AiAssistService` |
| Notifications | `cx_notifications_events_total{result}` (results: `processed`, `duplicate`, `no_recipients`, `failed`, `dead`) | notification-service |
| Gateway | `gateway_proxied_total{route,status}`, `gateway_rate_limited_total`, `gateway_upstream_errors_total{route,type}` | gateway |
| RabbitMQ | `rabbitmq_queue_messages_ready`, `rabbitmq_queue_messages_unacked` (plugin on `:15692`) | RabbitMQ |
| JVM | `jvm_memory_used_bytes`, `jvm_gc_pause_seconds_*`, `process_cpu_usage` | Micrometer |

Logs: every line carries `corr=<correlationId> tenant=<id> user=<id>`. The correlation id is also returned in the `X-Correlation-Id` response header and in the error body's `correlationId`, and stored in `audit_event.correlation_id`
and in event headers. Start every investigation by getting the correlation id from the user's failing response.

## Runbook 1: Slow API

**Symptoms.** Users report a slow screen; `http_server_requests_seconds_sum/_count` for a `uri` rises; the gateway reports 504 (`gateway_upstream_errors_total{type="timeout"}`, upstream response timeout is 30 s).
**Likely root causes** (check in this order): (1) DB pool queuing (`hikaricp_connections_pending > 0`, rising `hikaricp_connections_acquire_seconds_*`): Runbook 2. (2) A missing tenant predicate or index, so a scan
(section 7). (3) Cache misses: `cx_cache_total{result="miss"}` rate up, or `cx_cache_fallback_total` up (Redis down, Runbook 4). (4) Lock waits on the per-tenant `ticket_counter` row (creates only; since section 3.1.1 the lock is held only for the tiny allocation transaction, so a recurrence points to a stuck transaction or a slow DB). (5) A very large tenant
making the dashboard miss expensive (50 000-row fold). (6) N+1 regression (section 6). (7) GC or CPU saturation (`jvm_gc_pause_seconds_max`, `process_cpu_usage`). (8) Slow AI provider holding request threads (Runbook 9).
**Diagnose.**
```bash
curl -s localhost:8081/actuator/prometheus | grep -E 'http_server_requests_seconds_(sum|count|max).*uri="/api/v1/tickets"'   # average = sum/count; max
curl -s localhost:8081/actuator/prometheus | grep -E 'hikaricp_connections_(active|pending|max)|jvm_gc_pause_seconds_max|process_cpu_usage'
jcmd <core-pid> Thread.print | grep -c 'HikariPool'          # threads parked waiting for a connection
```
Then on PostgreSQL (**NOT EXECUTED**): `pg_stat_statements` top by `total_exec_time`; `EXPLAIN (ANALYZE, BUFFERS)` of the offending statement as in section 7.2; `SELECT pid, wait_event_type, wait_event, state, left(query,100) FROM pg_stat_activity WHERE datname = current_database() AND state <> 'idle';`.
Use the correlation id to find the exact request in the logs (`corr=`) and its `http.access` duration.
**Remediate.** Add or fix the index and re-check the plan; add the tenant predicate; raise TTL or add caching for read-mostly data; reduce page size; for create hotspots numbering is already off the long-held row lock (`TicketNumberAllocator`, gaps possible); scale core horizontally only after the DB path is healthy.
**Prevent.** Keep `NPlusOneGuardTest` green; enable HTTP percentile histograms (section 8) and alert on p95; review query plans for every new list endpoint; keep tenant-first indexes.

## Runbook 2: Database connection exhaustion

**Symptoms.** 500/503 with "Connection is not available, request timed out" in the logs; `hikaricp_connections_active == hikaricp_connections_max` (default max 10 per node), `hikaricp_connections_pending > 0`, `hikaricp_connections_timeout_total` increasing; latency cliff on every endpoint.
**Root causes.** (1) Long transactions (`hikaricp_connections_usage_seconds_max` large; measured 6.1 s in the create series, section 3.1): lock waits holding a connection. (2) Slow queries multiplying hold time. (3) Pool too small for traffic by Little's law
(`concurrency = RPS x DB seconds per request`, `docs/HLD.md` section 2.3). (4) Connection leak from code that opens a connection outside a transaction (the repo has none known). (5) Many nodes times pool size exceeding PostgreSQL `max_connections`. (6) A slow external call inside a transaction
(the AI provider call is deliberately outside any transaction; keep it that way). (7) The per-request `revoked_token` lookup, now cached as "not revoked" for 3 s per `jti` (`cx.security.revocation-negative-cache-seconds`), so a valid token costs about one read per 3 s per node; set the property to 0 if that read is suspected to hide a problem.
**Diagnose.**
```bash
curl -s localhost:8081/actuator/prometheus | grep -E 'hikaricp_connections_(active|idle|pending|max|timeout_total|acquire_seconds_max|usage_seconds_max)'
```
PostgreSQL (**NOT EXECUTED**): `SELECT state, count(*) FROM pg_stat_activity GROUP BY 1;` `SELECT pid, now()-xact_start AS xact_age, wait_event_type, wait_event, left(query,120) FROM pg_stat_activity WHERE xact_start IS NOT NULL ORDER BY xact_age DESC LIMIT 10;`
`SELECT * FROM pg_locks WHERE NOT granted;` `SHOW max_connections;`. Look for `idle in transaction` sessions and one blocker (`pg_blocking_pids(pid)`).
**Remediate.** Find and fix the blocking transaction first (cancel with `pg_cancel_backend(pid)` only as an emergency); then tune `spring.datasource.hikari.maximum-pool-size` modestly, because a larger pool against a saturated database makes it worse; put PgBouncer in transaction mode in front (compatible with the `SET LOCAL` RLS design);
(the short negative cache for token revocation is implemented, 3 s); shorten transactions.
**Prevent.** Alert on `hikaricp_connections_pending > 0 for 2m` and on `usage_seconds_max`; set a statement and lock timeout at the database role level; leak detection in staging; load test with `perf/load-test.mjs` against the docker profile (not yet done).

## Runbook 3: Growing RabbitMQ backlog

**Symptoms.** `rabbitmq_queue_messages_ready{queue="cx.notifications"}` rising, or `messages_unacked` pinned at prefetch x consumers; notifications arrive late; `cx_outbox_published_total` rate exceeds `cx_notifications_events_total{result="processed"}` rate. (All **NOT EXECUTED**: no broker here.)
**Root causes.** (1) Consumer down or not connected (`consumers = 0`). (2) Consumers slow: channel send blocking, DB slow (they share the PostgreSQL instance with core), `concurrency 2` / `max-concurrency 4` / prefetch 10 too low for the rate. (3) Retry storm: persistent failures cycle through `cx.notifications.retry` (TTL 5 s default) up to 5 attempts; watch `cx_notifications_events_total{result="failed"}`. (4) Poison messages piling in `cx.notifications.dlq`.
(5) A burst producer (SLA job escalating many tickets; workflow NOTIFY loops are prevented by the one-run-per-trigger rule). (6) Publisher side: if the backlog is in `outbox_event` instead (PENDING rows), the relay or broker is the problem: Runbook 3b below.
**Diagnose.**
```bash
rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
rabbitmqctl list_consumers
# Management UI http://127.0.0.1:15672 -> Queues -> cx.notifications / .retry / .dlq (peek messages with "Get messages", Ack mode: Reject requeue true)
curl -s localhost:8082/actuator/prometheus | grep cx_notifications_events_total
```
Core side (SQL, **NOT EXECUTED**): `SELECT status, count(*), min(created_at) FROM outbox_event GROUP BY status;` and `SELECT event_type, attempts, left(last_error,100) FROM outbox_event WHERE status IN ('PENDING','FAILED') ORDER BY created_at LIMIT 20;`
**Remediate.** Restore or scale consumers; raise `cx.notifications.rabbit.concurrency/max-concurrency/prefetch`; if the cause is a bad event, fix and re-drive from the DLQ (shovel or republish) after the fix; for `FAILED` outbox rows set `status='PENDING', attempts=0, next_attempt_at=now()` for specific ids once the cause is fixed (manual, there is no replay endpoint).
**Prevent.** Alert on queue depth and on DLQ depth > 0; keep consumers idempotent; load test event bursts; define max queue length/TTL policy deliberately.
### 3b. Outbox rows stuck (core side)
`cx_outbox_enqueued_total` rises but `cx_outbox_published_total` does not, `cx_outbox_retried_total` rises: the relay cannot publish (broker down, confirm timeouts of 5 s, serialisation failure: permanent `FAILED` with "unreadable payload"). Log lines: `Outbox event ... moved to FAILED`, `Outbox poll failed`.
In the **local** profile the relay always "succeeds" even with no listeners (`LocalEventPublisher`), so a missing consumer is invisible; confirm a listener exists (`docs/HLD.md` section 3 note).

## Runbook 4: Redis unavailable

**Symptoms.** Latency up on cached endpoints (dashboard, categories, SLA, settings, workflow reads become database reads); WARN log `Redis unavailable (read|write|evict); serving without cache for 5s`; `cx_cache_fallback_total{op}` increasing; `cx_cache_total{result="miss"}` dominating; `hikaricp_connections_active` up because more queries run. Requests should **not** fail (design). **NOT EXECUTED**: tested only with a mocked template (`CacheServiceTest`) and a docker IT that did not run.
**Root causes.** Redis down, wrong `CX_REDIS_PASSWORD` (auth errors), network/DNS, timeout 500 ms (`spring.data.redis.timeout`) too tight under load, memory eviction (`maxmemory`), a very large `SCAN` from `evictByPrefix` blocking.
**Diagnose.**
```bash
curl -s localhost:8081/actuator/prometheus | grep -E 'cx_cache_(total|fallback_total)'
redis-cli -h 127.0.0.1 -a "$REDIS_PASSWORD" --no-auth-warning ping ; redis-cli ... info memory ; redis-cli ... --scan --pattern 't:*:analytics:*' | head
docker compose -f infra/docker-compose.yml logs redis | tail
```
Note `management.health.redis.enabled` is false in the local profile; in docker the Redis health indicator is part of readiness.
**Remediate.** Restore Redis; nothing to replay: entries refill on demand. If the database is struggling meanwhile, temporarily reduce load (rate limit, scale core down is wrong, scale DB up). Watch for a thundering herd when Redis returns: single-flight is per node, TTL jitter is +-10%.
**Prevent.** Alert on `rate(cx_cache_fallback_total[5m]) > 0`; size the DB for cold-cache load (measured locally: a dashboard miss costs about 6.7x a hit); never store authoritative state in Redis (nothing is); keep TTLs short enough that a missed eviction is tolerable (60 s dashboard).

## Runbook 5: Duplicate asynchronous processing

**Symptoms.** A user receives the same notification twice; two comments or two tickets appear after a retry; `cx_notifications_events_total{result="duplicate"}` increases (that is the *protection working*); logs show the same `eventId` processed again.
**Root causes.** (1) At-least-once delivery is by design: the relay publishes and crashes before marking SENT, a lease expires (60 s) while a slow publish is still running, or RabbitMQ redelivers an unacked message. (2) A consumer without the `eventId` claim. (3) **Comment creation is not idempotent**: a client retry of `POST /tickets/{id}/comments` creates a second comment (only ticket creation takes an `Idempotency-Key`). (4) The idempotency key was reused with a different body (409 `DUPLICATE_SUBMISSION`, not a duplicate) or older than 24 h (purged, so a late retry creates a new ticket).
(5) Two delivery rows differ by channel (`IN_APP` and `EMAIL` for the same event are two notifications by design).
**Diagnose.**
```sql
-- notification-service tables (NOT EXECUTED here)
SELECT event_id, status, attempts, last_error FROM notif_processed_event WHERE event_id = '<id>';
SELECT channel, status, attempts FROM notif_delivery WHERE event_id = '<id>';
SELECT count(*) FROM notif_notification WHERE event_id = '<id>' GROUP BY user_id HAVING count(*) > 1;   -- must be empty (unique key)
SELECT id, status, attempts, sent_at FROM outbox_event WHERE id = '<id>';                                  -- core side
```
Grep logs for `corr=<id>` and `Duplicate event <id> ignored`. Compare `event_id` in `notif_notification` with `outbox_event.id`.
**Remediate.** If the unique keys held, there is nothing to fix (the second delivery was a no-op). If a true duplicate row exists, a consumer bypassed the claim: fix the code, then delete the extra rows by hand. For duplicate comments from client retries, make the client send an idempotency token
(not available yet) or de-duplicate in the UI by disabling the button while pending (done for ticket create only).
**Prevent.** Keep every consumer idempotent on `eventId`; add `Idempotency-Key` support to comment creation; add a purge for `notif_processed_event` older than the max redelivery window; test redelivery explicitly (done: `NotificationProcessorTest`).

## Runbook 6: Frontend rendering delays

**Symptoms.** Blank screen or spinner for seconds after navigation; typing in filters lags; the notification bell flickers; the dashboard takes long to appear; high CPU in the tab. (Frontend profiling here is **NOT EXECUTED**.)
**Root causes.** (1) Slow API (Runbook 1): the UI waits for server state; check the Network panel for the request and its `X-Correlation-Id`. (2) Chunk loading: first visit pulls about 104 kB gzip, each lazy route adds 1-5 kB gzip; a slow network or a stale CDN/`index.html` pointing to removed hashed chunks gives
chunk-load errors (the `ErrorBoundary` catches render errors; verify chunk-load errors are handled by a reload prompt: not verified). (3) Query refetch storms: `staleTime` 15 s, `refetchOnWindowFocus` is off, notifications poll every 30 s per tab, mutations invalidate whole key prefixes (`qk.tickets(tenant)`), so one comment refetches lists and detail.
(4) Large tables: page size is capped at 100 rows by the API, but rendering 100 rows with badges is the upper bound of DOM work. (5) Expensive re-renders from unmemoised derived data (the app uses `useMemo` sparingly by design). (6) The inline-SVG charts re-render on every dashboard refetch. (7) 429 responses from the gateway cause retries (one retry for non-4xx only; 429 is a 4xx and is not retried, so the user sees the error).
**Diagnose.** Browser DevTools Network (filter `api/v1`, look at the `X-Correlation-Id` response header), Performance panel with 4x CPU throttle, React DevTools Profiler ("why did this render"), Lighthouse. Build facts: `npm run build` prints chunk sizes; `npm run typecheck`, `npm test` for regressions.
**Remediate.** Fix the slow endpoint first; narrow invalidation keys; debounce filter input (verify it is debounced before assuming); virtualise long lists only if a real need appears; lazy-load heavy admin widgets; set long-cache headers for `/assets` (already in `nginx.conf`) and `no-cache` for `index.html`.
**Prevent.** Keep the bundle report in `frontend/README.md` current and review chunk growth in PRs; add a web-vitals report; keep `staleTime` and polling intervals deliberate.

## Runbook 7: Authorization failures

**Symptoms.** 401 `UNAUTHENTICATED` or 403 `FORBIDDEN` for users who should have access; `cx_auth_login_total{result="failure"}` rising; users bounced to `/login` ("session expired"); 429 `RATE_LIMITED` on login.
**Root causes.** 401: expired token (60 min TTL, no refresh), revoked token (logout: row in `revoked_token`), signature mismatch because gateway, core and notification-service have different `CX_JWT_SECRET` (the gateway returns 401 "Invalid or expired token" before core is reached), clock skew over 5 s, token from the other profile,
malformed `Authorization` header, wrong `tenantSlug` or email at login (one generic "Invalid credentials" message by design), deactivated user at re-login. 403: role too low (`@PreAuthorize("hasRole('AGENT')")` etc.; customer calling analytics/audit/AI), customer trying INTERNAL comments or non-reopen transitions.
Also: an **unexpected 404** is the intended answer for another tenant's or another customer's ticket (not 403).
**Diagnose.**
```bash
# which layer rejected it? gateway errors come from the gateway's JSON writer, core's from GlobalExceptionHandler/ErrorWriter; compare the correlationId in the body to the logs
curl -s -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/auth/me        # through gateway
curl -s -i -H "Authorization: Bearer $TOKEN" localhost:8081/api/v1/auth/me        # directly to core: if this works, the gateway secret is the problem
curl -s localhost:8081/actuator/prometheus | grep cx_auth_login_total
```
Decode the token payload locally (it is not secret, but do not share real tokens): check `exp`, `tid`, `roles`, `jti`. `SELECT * FROM revoked_token WHERE jti = '<jti>';` (**NOT EXECUTED** on PostgreSQL; on H2 the same table exists). Audit trail: `GET /api/v1/audit-events?entityType=USER` as ADMIN shows `LOGIN_FAILED`/`LOGIN_SUCCESS`/`LOGOUT`.
**Remediate.** Align `CX_JWT_SECRET` across services (all users re-login after a rotation); re-login for expired/revoked tokens; fix the user's role via `PUT /users/{id}` (ADMIN) and have them log in again (roles are in the token, so changes apply at next login); wait out or tune the login limiter (`CX_LOGIN_RATE_CAPACITY`, `CX_LOGIN_RATE_REFILL_PER_MIN`: capacity 10 and 5/min in docker profile).
**Prevent.** Role-matrix test (`RoleMatrixTest`) for every new endpoint; monitor login failures per tenant for credential stuffing; add refresh tokens and key rotation (not implemented).

## Runbook 8: Incorrect tenant isolation (suspected cross-tenant data exposure)

**Treat as severity 1.** Symptoms: a user reports seeing another organisation's ticket, user, article or notification; a count on the dashboard that does not match; an audit entry for an unknown tenant; a cache value that does not belong; logs where `tenant=` differs from the entity's tenant.
**Root causes (by layer).** (1) A repository or query without the tenant predicate (the Hibernate filter should still scope JPQL/Criteria, but `findById`, native SQL and `JdbcTemplate` bypass it). (2) A thread running with a stale `TenantContext` (thread pool reuse without clearing; `CorrelationIdFilter` and `JwtAuthFilter` clear in `finally`, an async executor would not inherit the context). (3) A cache key built without `CacheService.fullKey` (all keys must go through it). (4) A new controller
accepting a tenant or an entity id and not scoping it. (5) The notification-service or any component querying `notif_*` without `tenant_id` and `user_id`. (6) RLS not actually enabled (docker profile only; local has no RLS at all) or the app connecting as a superuser/owner without `FORCE`. (7) A shared JWT secret leak allowing forged `tid` tokens. (8) Mongo `DocumentStore` queries missing `tenantId` (the implementation always includes it).
(9) A frontend cache bleed (the tenant-first query keys prevent it; verify `qk.*` is used for every query).
**Diagnose.**
1. Get the correlation id and the exact entity id. Reproduce with that user's token: `GET /api/v1/tickets/{id}`; it must be 404 for another tenant (`TenantIsolationTest` asserts this for tickets, users, analytics, audit, settings, categories, workflows, SLA).
2. Run the guard tests: `mvn -pl core-service -am test -Dtest='TenantIsolationTest,TenancyTest,ArchitectureRulesTest'` (all pass; whole core-service suite 179/179, 0 failures, 2026-10-17).
3. Search for bypasses: `grep -rn "JdbcTemplate\|createNativeQuery\|nativeQuery\s*=\s*true" core-service/src/main/java`; `grep -rn "findById(" core-service/src/main/java` (each use must be followed by a tenant check or be tenant-keyed).
4. RLS check on PostgreSQL (**NOT EXECUTED**): `SELECT relname, relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname IN ('ticket','app_user','audit_event');` `SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user;` `SELECT policyname, tablename, qual FROM pg_policies;`
and the two-tenant leakage test `PostgresRlsDockerIT` via `-Pdocker-tests` on a Docker host.
5. Cache: keys are `t:{tenantId}:...`; on Redis `redis-cli --scan --pattern 't:*'` and verify the tenant segment equals the data's tenant.
**Remediate.** Contain: revoke affected tokens (insert `jti` rows or rotate `CX_JWT_SECRET` to force global re-login), disable the endpoint at the gateway if needed. Fix the missing predicate and add a regression test next to `TenantIsolationTest`. If a secret leaked, rotate it. Notify per your incident process (outside this repo).
**Prevent.** Keep `ArchitectureRulesTest` strict; add a test for every new controller that a second tenant gets 404; enable RLS in production and run the docker ITs in CI; connect the app with a non-owner, non-`BYPASSRLS` role; forbid `JdbcTemplate` outside transactions.

## Runbook 9: AI provider timeouts

**Symptoms.** `POST /tickets/{id}/ai/analyze` returns HTTP 200 with `degraded: true` and the fixed `fallbackMessage` ("AI assistance is temporarily unavailable..."); the UI shows the degraded state; `cx_ai_analyze_total{result="degraded"}` rising; agents wait several seconds before the fallback; 429 `RATE_LIMITED` (`result="rate_limited"`) for a busy tenant.
Note the default provider is the deterministic `MOCK_RULE_BASED`, which does not time out on its own; degradation means either a configured `HTTP` provider is slow/unreachable, or fault injection is on.
**How the code behaves (`core/ai/AiAssistService`, `AiSettings`).** Provider calls run on a bounded pool (4 workers, 16 queued; a full queue is `overloaded`), outside any DB transaction. Defaults: per-call timeout `cx.ai.timeout-ms=3000`, `cx.ai.max-retries=2` (hard cap 3) with a 50-200 ms pause, total deadline
`cx.ai.total-deadline-ms=10000`, per-tenant limit `cx.ai.rate-limit-per-minute=30` (per node), circuit breaker opens after `cx.ai.breaker-failures=5` consecutive failed analyses for `cx.ai.breaker-open-ms=30000`, then lets one trial call through. Worst-case wait for a user is therefore about 3 x 3 s + 0.15 s = 9.15 s (arithmetic from the defaults), within the 10 s deadline.
Every degraded analysis writes an audit event `AI_ANALYSIS_DEGRADED` with `reason` in `timeout | provider_error | deadline | circuit_open | overloaded | interrupted` and `attempts`.
**Root causes.** Remote endpoint slow or down (`cx.ai.provider=http`, `cx.ai.http.url` must be https), expired/invalid key (non-retryable), 429/5xx from the provider (retryable), network/DNS egress blocked, circuit open from earlier failures, worker pool saturated by many concurrent analyses, `cx.ai.fault-mode` set to `FAIL` or `TIMEOUT` (the app refuses to start with fault mode on in the docker profile).
**Diagnose.**
```bash
curl -s localhost:8081/actuator/prometheus | grep -E 'cx_ai_analyze_total|cx_ai_meta_failed_total'
# audit reasons (ADMIN token): GET /api/v1/audit-events?entityType=TICKET  -> action AI_ANALYSIS_DEGRADED, details.reason
grep 'AI provider attempt' core.log          # WARN lines per failed attempt (exception class only, no payload)
jcmd <core-pid> Thread.print | grep ai-provider   # worker threads stuck in the HTTP client
```
**Remediate.** Fix or fail over the provider; check the key and egress rules; raise the timeout only if the provider is healthy but slow (every extra second is a held request thread, though no DB connection); lower `max-retries` to shorten worst-case waits; switch back to the mock provider (`cx.ai.provider` unset) as an immediate fallback, which also keeps the human-approval flow working.
Agents can always continue manually: nothing else depends on AI.
**Prevent.** Alert on a degraded ratio, e.g. `increase(cx_ai_analyze_total{result="degraded"}[15m]) / increase(cx_ai_analyze_total[15m])`; keep the breaker enabled; never enable a real provider without reviewing what data leaves (input is sanitised and redacted by `AiSanitizer`, but regexes are a best effort, not a guarantee); keep human approval mandatory.
Status of evidence: tests for the degraded path exist in the tree (`AiResilienceUnitTest`, `AiBreakerAndProviderSelectionTest`, `AiApiTest`, using the fault injector); I did not run them and the load tests in section 3 used a jar built before the AI package existed. No real provider was ever called.
To exercise the degraded path locally (profile local only, never in docker): start with `--cx.ai.fault-mode=TIMEOUT` (or `FAIL`), call `POST /api/v1/tickets/{id}/ai/analyze` as an agent, expect HTTP 200, `degraded: true`, and after about 9 s with the defaults (3 attempts x 3 s), then the circuit opens after 5 consecutive failed analyses and answers come back immediately with reason `circuit_open`.

## Runbook 10: Gateway 429 / 502 / 503 / 504

**Symptoms.** Clients get `RATE_LIMITED` with `Retry-After`, `UPSTREAM_UNAVAILABLE` (503), `UPSTREAM_TIMEOUT` (504), `UPSTREAM_ERROR` (502). Metrics: `gateway_rate_limited_total`, `gateway_upstream_errors_total{route,type=timeout|unavailable}`, `gateway_proxied_total{status}`.
**Root causes.** 429: one user exceeds 100 burst / 20 per second (per instance; N replicas multiply the effective limit); a polling bug in the UI. 503: core/notification-service down, wrong `CORE_URI`/`NOTIFICATIONS_URI` (in the local profile notifications default to the core URI, which serves them itself through the embedded notification module, `cx.notifications.embedded=true`; under the docker profile they come from the separate notification-service). 504: core slower than 30 s (response timeout) or connect > 2 s. 401 at the gateway but fine at core: secret mismatch (Runbook 7).
**Diagnose.** `curl localhost:8090/actuator/prometheus | grep gateway_`; compare `curl localhost:8081/actuator/health/readiness` against the upstream; look at `corr=` in both services' logs. **Remediate.** Restore the upstream; tune `GATEWAY_RATE_CAPACITY`/`GATEWAY_RATE_REFILL_PER_SEC` for legitimate load; fix aggressive clients. **Prevent.** Alert on `gateway_upstream_errors_total`; add a test for the 504 path (not covered today).

---

## Appendix: what is NOT measured or NOT verified in this guide

- Any PostgreSQL, Redis, RabbitMQ or MongoDB behaviour or latency. Any use of the compose stack, Prometheus or Grafana.
- Gateway throughput and added latency. Notification-service throughput. End-to-end event latency (outbox to in-app notification).
- Behaviour above about 4 000 tickets per tenant. Several tenants at once was only tried with three tenants in section 3.1.1 (creates only). The effect of the embedded notification listener on end-to-end latency was not isolated (it ran in both the before and after series of 3.1.1).
- Real browser performance (no Lighthouse, no profiler). The k6 scripts.
- Run-to-run variance for scenarios 1-4 (single runs); the ticket-create series shows variance is large for creates.
