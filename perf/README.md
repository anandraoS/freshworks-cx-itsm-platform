# perf

Two ways to load test a running stack. Neither is part of the unit-test build.

## `load-test.mjs` (no install needed, Node 18+)

```
# start core-service first (scripts/run-local.sh), then:
node perf/load-test.mjs
BASE_URL=http://localhost:8081 SCENARIO=mixed CONCURRENCY=20 DURATION_S=30 node perf/load-test.mjs
```

Scenarios: `ticket-list` (default), `dashboard`, `ticket-create`, `mixed` (70/20/10). Output: requests, errors, RPS, status counts and p50/p95/p99/max
latency in ms (warm-up excluded). Defaults target core directly (`:8081`), logging in as the seeded `agent1@acme.test` (dummy demo credentials from
`docs/API-CONTRACT.md`). Exit code is 1 if any request failed (non-2xx/3xx or network error).

Verification status: first exercised against a throwaway Node stub server (949 requests, 0 errors in a 3 s window). It was later run against a real
core-service (profile local, H2, port 8093) for all four scenarios: results, machine and caveats are in `PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md` section 3.
`dashboard-cache-probe.mjs` (cache hit vs miss) and `sql/H2Explain.java` (H2-only plan evidence) were added alongside; see the same guide.

## k6 (`perf/k6/*.js`)

`k6 run perf/k6/login.js`, `ticket-list.js`, `ticket-create.js`, `dashboard.js`. Env: `BASE_URL` (default gateway `http://localhost:8080`), `TENANT`,
`AGENT_EMAIL`, `MANAGER_EMAIL`, `PASSWORD`. Thresholds are defined in each script's `options` (p95/p99 latency, error rate, check rate).

Verification status: `where k6` found nothing in the authoring environment, so the k6 scripts were NOT EXECUTED and not syntax-checked by k6.
Install k6 (https://grafana.com/docs/k6/) and run them yourself.

## Reading results

* The gateway rate-limits per user (token bucket, default burst 100, 20/s). A load test through `:8080` will hit 429s above that; set
  `GATEWAY_RATE_CAPACITY` / `GATEWAY_RATE_REFILL_PER_SEC` higher for throughput tests or point `BASE_URL` at core. Login is limited separately by
  core's `LoginRateLimiter`.
* The local profile uses H2 + in-memory cache, so absolute numbers say little about PostgreSQL/Redis behaviour. Compare scenarios relative to each
  other, and re-run with the docker profile for realistic figures.
* Thresholds in the k6 scripts are starting points, not SLOs.
