# Part M. Observability and production operations

Observability is the ability to understand what a running system is doing from the outside, using the signals it emits. This part covers what the platform emits today, how to read those signals during an incident, what is missing, and how an engineer behaves when production is on fire. It ends with nine worked incident walkthroughs built on the runbooks in `PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md`.

> Honesty rules. Implemented and executed: structured log lines with correlation id, tenant and user; Micrometer metrics exposed on `/actuator/prometheus` (read in the measured load runs); `PlatformEndpointsTest` checks for `cx_cache_total{result}`, health probes, correlation-id behaviour and redaction. Written but never started: the Prometheus scrape config, the Grafana dashboard `infra/grafana/dashboards/cx-platform-overview.json` and the Docker Compose stack (`infra/README.md`: "Verification status: NOT RUN"). Not implemented: distributed tracing (OpenTelemetry), alert rules, log aggregation, percentile histograms (disabled by default), an on-call process. The incident walkthroughs are illustrative scenarios built on the runbooks, not records of real incidents. No figure here is a Freshworks metric or a production SLO.

## M.1 Observability overview: signals, questions and what exists

### What it is and why it exists

Monitoring answers known questions ("is the error rate above 1 percent?"). Observability lets you ask new questions about unknown failures ("why did tenant X's creates slow down at 14:05?") using logs, metrics and traces. The three signals complement each other:

| Signal | Answers | Cost | Cardinality tolerance |
|---|---|---|---|
| Metrics | Is something wrong, how much, since when? | Low (numbers over time) | Low: avoid per-user or per-ticket labels |
| Logs | What exactly happened to this request? | Medium to high (volume) | High |
| Traces | Where did the time go across services? | Medium (sampling) | High |

A fourth signal, profiles, answers "which code is burning CPU or holding locks" (section M.9). Audit events are a fifth, business-level signal stored in the database (`audit_event`).

![Figure: Observability data flow from services to Prometheus, Grafana and logs](docs/diagrams/png/16-observability.png)

### Status for this project

| Signal | Status |
|---|---|
| Structured logs with `corr`, `tenant`, `user` | Implemented (core; gateway has its own correlation filter) |
| Correlation id header, error body, audit row, event header | Implemented |
| Micrometer metrics, Prometheus endpoint | Implemented and read during load tests |
| Prometheus scrape config, Grafana dashboard | Written, NOT RUN |
| Alert rules | Not implemented (only described in `docs/diagrams/16-observability.mmd`) |
| Distributed tracing | Not implemented |
| Log shipping and search | Not implemented (console logs only) |
| Profiling | JFR commands documented, NOT EXECUTED |

### Interview Q&A

**Q1. Monitoring versus observability: what is the practical difference?**
Monitoring is dashboards and alerts for failure modes you predicted. Observability is having enough high-cardinality, correlated data (a correlation id that joins a user complaint, a log line, an audit row and an event) to investigate failures you did not predict. In this platform, the correlation id is the join key.

**Q2. You can only invest in one signal first. Which?**
Metrics with alerts, because they tell you something is wrong before users do; then structured logs with a correlation id to find why; then traces when you have enough services that one request's path is no longer obvious. The platform is at the second stage: three deployables, one request path, and manual correlation through ids.

**Q3. What is the danger of high-cardinality metric labels?**
Each unique label combination is a separate time series stored in memory by Prometheus. Labelling by `tenantId` or `ticketId` explodes series count. Put the tenant in logs, not in metric labels, unless the tenant count is small and bounded. The project metrics use only low-cardinality labels (`result`, `cache`, `route`, `status`, `type`).

**Q4. What would you add first for a real production launch?**
Percentile histograms, five to eight alert rules tied to SLOs, log shipping to a searchable store with retention, and OpenTelemetry tracing. In that order of cost to benefit.

## M.2 Structured logging

### What it is and why it exists

A structured log line has parseable fields instead of free text, so a log platform can filter and aggregate (all lines for one tenant, count of WARN by logger) instead of grepping prose. It exists because at scale nobody reads logs; machines query them.

### How it works internally

SLF4J is the API; Logback is the implementation Spring Boot uses by default. The Mapped Diagnostic Context (MDC) is a thread-local map; values put into it are available to the log pattern as `%X{key}`. Filters set the MDC at request start and must clear it in a `finally`, otherwise values leak to the next request that reuses the pooled thread.

### How THIS PROJECT configures it

In `core-service/src/main/resources/application.yml`:

```yaml
logging:
  pattern:
    console: "ts=%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX,UTC} level=%-5level corr=%X{correlationId:-} tenant=%X{tenantId:-} user=%X{userId:-} logger=%logger{32} msg=\"%replace(%msg){'\"','\\\\\"'}\"%n%wEx"
  level:
    root: INFO
    org.hibernate.SQL: WARN
```

A real line looks like: `ts=2026-10-17T10:15:02.114Z level=INFO corr=3f1c... tenant=8a2e... user=77b1... logger=http.access msg="GET /api/v1/tickets -> 200 in 14ms"`. It is logfmt-style (key=value), which most log platforms parse without custom rules, with quotes in messages escaped so a message cannot forge extra fields. Stack traces follow via `%wEx`.

Who writes the MDC:

- `CorrelationIdFilter` (core, `@Order(HIGHEST_PRECEDENCE)`) puts `correlationId`, and writes one access line per request with only method, path, status and duration. It never logs bodies, headers or query strings.
- `JwtAuthFilter` puts `userId`; `TenantContext.bind` puts `tenantId`; both are cleared in `finally` blocks, and `CorrelationIdFilter` clears `TenantContext` and the whole MDC as the outermost safety net.

### Why this design was selected

logfmt-style output is readable by a human at a terminal and parseable by Loki, Splunk or ELK without JSON encoder dependencies. JSON logging (for example `logstash-logback-encoder`) is the common alternative and is a one-line decision later. Quoting and escaping were done in the pattern so no extra library is needed.

### What gets logged and what must never be

Logged: method, path, status, duration, ids, WARN lines on resilience events (`Redis unavailable (read|write|evict); serving without cache for 5s`, `AI provider attempt ...` with exception class only, `Outbox event ... moved to FAILED`, `Duplicate event <id> ignored`). Never logged: request bodies, headers, tokens, passwords, query strings, PII. The validation record notes a log check for `passw0rd|bearer|eyJ` returned 0 and `PlatformEndpointsTest` includes redaction. `org.hibernate.SQL` is kept at WARN so statement bodies and parameters are not logged; enabling parameter logging in a shared environment would leak PII.

### Implemented versus not implemented

Implemented: key=value pattern, MDC propagation, access line, redaction rules, bounded correlation id input. Not implemented: JSON output, log sampling, async appenders, centralised shipping, per-environment log level control at runtime (Actuator `loggers` endpoint is not exposed: only `health,info,prometheus,metrics` are in the exposure list), retention policy.

### Performance

Console logging is synchronous by default; heavy logging at INFO on a hot path costs CPU and can block on a slow stdout pipe. One access line per request is acceptable. Avoid string concatenation in log calls (use placeholders), and never log inside tight loops. An async appender with a bounded queue is the next step if profiling shows logging time.

### Security

Treat logs as sensitive data stores. Control access, set retention, never log secrets, and prevent log injection: untrusted input must not reach the log unescaped. The correlation-id regex `^[A-Za-z0-9._-]{1,64}$` makes sure a client-supplied header cannot add newlines or JSON, and `PlatformEndpointsTest` proves an invalid value is replaced.

### Common mistakes

Logging exceptions twice (once where caught and again where rethrown), logging at ERROR for expected client errors (alert fatigue), forgetting to clear the MDC on pooled threads, logging whole objects with `toString` that include secrets, and different field names in different services.

### Debugging with logs

Always begin with the correlation id from the failing response (`X-Correlation-Id` header or error body `correlationId`). Then `grep corr=<id> core.log gateway.log`. Compare `tenant=` to the expected tenant; a mismatch is a severity 1 signal (Runbook 8).

### Alternatives and trade-offs

JSON logs (richer types, bigger lines, harder to read raw), OpenTelemetry logs (link logs to traces automatically), vendor agents. Logfmt now, JSON plus trace id later is the natural path.

### Interview Q&A

**Q1. Why clear the MDC in a `finally` block?**
Servlet containers reuse threads. If the MDC is not cleared, the next request on that thread logs with the previous request's tenant and user, which makes incident investigation misleading and, in a multi-tenant system, can look like a data leak.

**Q2. Where do MDC values get lost?**
When work moves to another thread: `@Async`, executors, `CompletableFuture`, reactive pipelines (the gateway is WebFlux, where thread-local propagation does not work and context must be carried explicitly), and message listeners. Capture the values and re-set them in the new thread, or use a context-propagating executor.

**Q3. How would you find all log lines for one customer complaint?**
Ask for the correlation id (browser DevTools shows `X-Correlation-Id`; the error toast could display it). If there is none, search by `user=` and time window, then narrow by `logger` and level.

**Q4. What goes into an access log line and what does not?**
Method, path template or path, status, duration, ids. Not: bodies, auth headers, query strings (can hold tokens or PII), full user agents with identifiers.

## M.3 Correlation ids

### What it is and why it exists

A correlation id is an identifier attached to a request when it enters the system and carried through every hop, log line, error body and event. It exists so that one user action can be reassembled from fragments scattered across services.

### How it works in this project

1. Frontend: `frontend/src/lib/correlation.ts` can generate an id for requests.
2. Gateway: has its own `CorrelationIdFilter` that accepts a valid inbound id or generates one, forwards it, and includes it in gateway-written error bodies (`GatewayUpstreamDownTest` asserts `correlationId` equals the supplied `down-1`).
3. Core: `CorrelationIdFilter` validates the inbound `X-Correlation-Id`, or generates a UUID, puts it in the MDC and echoes it as a response header.
4. Core error responses: `ErrorWriter` and `GlobalExceptionHandler` include `correlationId` in the body.
5. Audit: `AuditService.record` stores `MDC.get(correlationId)` in `audit_event.correlation_id`.
6. Events: `OutboxService.publish` copies the id into the `DomainEvent`, so `OutboxTest` can assert that a "propagated correlation id" arrives; the RabbitMQ publisher sets it as a message header and `NotificationMessageHandler` reads `message.getMessageProperties().getCorrelationId()`.

So one id joins: the browser network tab, gateway log, core log, error body, audit row, outbox event and notification consumer log. This is a poor man's trace: no timing tree, but good for "what happened to this request".

### Why selected

It is cheap, framework-independent and works through asynchronous hops that tracing libraries sometimes lose. It also costs nothing at runtime.

### Implemented versus not implemented

Implemented: generation, validation, propagation to logs, errors, audit, events. Not implemented: W3C `traceparent` support, span timing, sampling, propagation through the AI provider call (nothing sensitive should be sent there anyway), automatic propagation through every executor.

### Security and performance

Validation (`SAFE` pattern, 1 to 64 characters) prevents log injection and header abuse; a UUID is 36 characters, accepted. The id is not a secret and is not authentication, so never treat its presence as proof of anything. Overhead is negligible.

### Common mistakes

Accepting any inbound string, generating a new id at each hop (breaking the chain), and not returning the id to the user so support cannot ask for it.

### Debugging

Cross-service search: `grep -h "corr=<id>" *.log | sort`. If an id appears in the gateway but not in core, the request died at the gateway (401 from secret mismatch, 429, 503).

### Interview Q&A

**Q1. Correlation id versus trace id?**
A correlation id is one opaque value you propagate yourself. A trace id belongs to a trace: a tree of spans with timings and parent links, propagated by a library using a standard header (W3C `traceparent`). You can use the trace id as your correlation id once tracing is added; the project could simply adopt it in the MDC.

**Q2. Should the server trust a client-supplied id?**
Accept it for convenience, validate strictly, and never use it for security decisions. Be aware two clients could send the same id; do not use it as a unique key.

**Q3. How does the id cross the message broker?**
The outbox row stores it in the event; the publisher sets the AMQP `correlationId` property (and a header); the consumer reads it and puts it into its MDC when logging. Retries re-publish the same event with the same id.

**Q4. What if the thread pool loses the MDC?**
Wrap executors (`TaskDecorator` in Spring) to copy MDC maps, or pass the id explicitly in method arguments for long-lived jobs. The SLA job runs outside HTTP, so it creates its own job-level id.

## M.4 Metrics

### What it is and why it exists

A metric is a numeric measurement aggregated over time: a counter that only goes up, a gauge that goes up and down, a timer or histogram of durations. Metrics are the cheapest way to observe trends and to trigger alerts.

### How it works internally

Micrometer is a vendor-neutral facade (like SLF4J for metrics). Application code registers meters in a `MeterRegistry`; the Prometheus registry renders them in the Prometheus text format at `/actuator/prometheus`; the Prometheus server scrapes that endpoint every `scrape_interval` (15 s in `infra/prometheus/prometheus.yml`) and stores time series. Micrometer names use dots and are converted: counters become `<name>_total`, timers become `_seconds_count`, `_seconds_sum`, `_seconds_max`, and, only if histograms are enabled, `_seconds_bucket`.

### How THIS PROJECT configures it

- Dependencies: `spring-boot-starter-actuator` and `micrometer-registry-prometheus` (core `pom.xml`).
- Exposure: `management.endpoints.web.exposure.include: health,info,prometheus,metrics`; health details are `never`, probes enabled.
- Common tag: `management.metrics.tags.application: core-service` (gateway and notification set their own), which is what the dashboard variable `$application` filters by.
- The gateway exposes actuator on an internal management port 8090 that is not published to the host; Prometheus reaches it by service name.

### The real metric catalogue

| Area | Prometheus name | Type and labels | Emitted by | Use |
|---|---|---|---|---|
| HTTP | `http_server_requests_seconds_count/_sum/_max` | timer; `uri`, `status`, `method` | Spring Boot | rate, errors, average latency, max latency |
| HTTP percentiles | `http_server_requests_seconds_bucket` | histogram; only with `management.metrics.distribution.percentiles-histogram.http.server.requests=true` | Spring Boot | p95 panel; NOT enabled |
| DB pool | `hikaricp_connections_active/idle/pending/max/min`, `_acquire_seconds_*`, `_usage_seconds_*`, `_timeout_total` | gauges, timers | HikariCP | saturation |
| Cache | `cx_cache_total{cache,result}`, `cx_cache_fallback_total{op}` | counters; `cache` in analytics, categories, sla, settings, workflow, kb; `result` hit or miss | `CacheService` | hit ratio, Redis outage |
| Outbox | `cx_outbox_enqueued_total`, `_published_total`, `_retried_total`, `_failed_total`, `_purged_total` | counters by event `type` | `OutboxService`, `OutboxRelay`, purge job | event pipeline health |
| Tickets | `cx_tickets_created_total` (the runbook table abbreviates this as `cx_tickets_total`) | counter | `TicketService` | business throughput |
| Auth | `cx_auth_login_total{result}` | counter | `AuthService` | credential stuffing, login health |
| SLA job | `cx_sla_job_runs_total`, `cx_sla_job_errors_total` | counters | `SlaMonitorJob` | job liveness |
| Workflow | `cx_workflow_fallback_total{reason}` | counter | `WorkflowService` | corrupted workflow fallback |
| AI | `cx_ai_analyze_total{result=ok\|degraded\|rate_limited}`, `cx_ai_meta_failed_total` | counters | `AiAssistService` | degradation ratio |
| Notifications | `cx_notifications_events_total{result}` with `processed`, `duplicate`, `no_recipients`, `failed`, `dead` | counter | notification-service | consumer outcomes |
| Gateway | `gateway_proxied_total{route,status}`, `gateway_rate_limited_total`, `gateway_upstream_errors_total{route,type}` | counters | gateway | edge errors |
| RabbitMQ | `rabbitmq_queue_messages_ready`, `rabbitmq_queue_messages_unacked` | gauges | RabbitMQ plugin on port 15692 | backlog |
| JVM | `jvm_memory_used_bytes{area}`, `jvm_gc_pause_seconds_*`, `process_cpu_usage` | gauges, timers | Micrometer JVM binders | memory, GC, CPU |

Executed evidence: during the load runs the metric endpoint was read directly. For example, under the write-heavy load the analytics cache counters read 31 983 hits and 1 697 misses (95.0 percent hit ratio), and `PlatformEndpointsTest` asserts `cx_cache_total{result}` is exposed.

### Why selected

Prometheus pull model with Micrometer is the Spring Boot default; pull makes scrape failure visible as `up == 0`; PromQL is powerful for rates and quantiles. Business-level counters (`cx_ai_analyze_total`, `cx_outbox_*`) answer questions infrastructure metrics cannot.

### Useful PromQL for this platform

```promql
# request rate per service
sum by (application) (rate(http_server_requests_seconds_count[1m]))
# 5xx ratio per service
sum by (application) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  / sum by (application) (rate(http_server_requests_seconds_count[5m]))
# average latency of one endpoint
sum(rate(http_server_requests_seconds_sum{uri="/api/v1/tickets"}[5m]))
  / sum(rate(http_server_requests_seconds_count{uri="/api/v1/tickets"}[5m]))
# p95 (requires percentiles-histogram on)
histogram_quantile(0.95, sum by (le, application) (rate(http_server_requests_seconds_bucket[5m])))
# cache hit ratio
sum by (cache) (rate(cx_cache_total{result="hit"}[5m])) / sum by (cache) (rate(cx_cache_total[5m]))
# AI degraded ratio
increase(cx_ai_analyze_total{result="degraded"}[15m]) / increase(cx_ai_analyze_total[15m])
# outbox is publishing as fast as it is enqueueing
rate(cx_outbox_enqueued_total[5m]) - rate(cx_outbox_published_total[5m])
```

### Implemented versus not implemented

Implemented: the meters above. Not implemented: percentile histograms (the dashboard's p95 panel is empty until enabled, stated in `infra/README.md` and section 8 of the guide), per-endpoint SLO histogram buckets, custom business gauges (open tickets per tenant, outbox PENDING count, oldest pending age), exemplars linking metrics to traces, a metrics test for every counter.

A gap worth naming: there is no gauge for "oldest PENDING outbox event age". The existing counters let you infer a stuck relay (enqueued rises, published does not), but an age gauge would alert directly and is cheap to add.

### Performance

Counters and timers are atomic updates; negligible overhead. Histograms with many buckets multiply series (buckets times label combinations). Never use unbounded labels such as raw URL paths with ids: Spring uses the URI template (`/api/v1/tickets/{id}`), which keeps `uri` bounded.

### Security

`/actuator/prometheus` and `/actuator/metrics` expose internals. The security hardening document lists protecting or disabling them on public ports. The gateway keeps actuator on an internal management port (8090) not published to the host. No `env`, `heapdump` or `loggers` endpoints are exposed (`PlatformEndpointsTest` asserts no env and no heapdump).

### Common mistakes

Alerting on averages (hides tail latency), high-cardinality labels, counters that reset being read as gauges, measuring in the wrong unit, missing `rate()` on counters, and dashboards no one owns.

### Debugging with metrics

Read `/actuator/prometheus` with `curl | grep`. During a measured run this showed `hikaricp_connections_usage_seconds_max` of 6.1 s, which pointed at long transactions holding connections and led to the per-tenant ticket counter lock being found and removed. That is the full loop: metric symptom, hypothesis, controlled experiment (same load spread over three tenants), fix, re-measure.

### Alternatives

StatsD or Datadog agents (push), OpenTelemetry metrics (OTLP push), CloudWatch, VictoriaMetrics or Mimir for long-term storage.

### Interview Q&A

**Q1. Why is p95 better than average for latency alerts?**
Averages hide the tail. In the measured create series the median stayed near 29 ms while p99 reached 1 943 ms and max 7 707 ms. An average alert would have stayed green while one in a hundred users waited seconds.

**Q2. How do you avoid cardinality explosions?**
Bound label values, use URI templates, put identifiers in logs, drop labels at scrape time if needed, and review every new metric's labels.

**Q3. Counter, gauge, or histogram for queue depth?**
Gauge: it goes up and down. Counters for events, histograms for durations and sizes.

**Q4. A counter drops to zero after a deploy. Is that a problem?**
No. Counters reset on process restart; `rate()` and `increase()` handle resets. It would be a problem only if you read raw values.

## M.5 Distributed tracing (concept; not implemented)

### What it is and why it exists

A trace is the end-to-end record of one request as a tree of spans, each with a start, a duration, attributes and a parent. Tracing answers "which hop was slow?" when a request crosses a gateway, a core service, a database, a cache and a broker.

### How it works internally

A tracer creates a root span at the edge, propagates the trace context in headers (W3C `traceparent`: version, trace id, parent span id, flags), each service creates child spans, and exporters send spans to a backend (Jaeger, Tempo, Zipkin or a vendor) through the OpenTelemetry protocol. Sampling decides which traces are kept: head sampling at the start (cheap), tail sampling after seeing the whole trace (keeps errors and slow ones).

### Status in this project

**OpenTelemetry is not implemented.** The documentation says so explicitly: "No service mesh, no mTLS between services, no distributed tracing backend (correlation id in logs and headers instead)" and `docs/HLD.md` lists a distributed tracing backend as out of scope. There is no tracer dependency, no span instrumentation and no collector in `infra/docker-compose.yml`.

### What adding it would look like

1. Add Micrometer Tracing with the OpenTelemetry bridge (`micrometer-tracing-bridge-otel`) and an OTLP exporter to core, gateway and notification-service. Spring Boot 3 auto-instruments servlet requests, RestClient and RabbitMQ templates.
2. Put the trace id in the MDC (it replaces or equals `correlationId`), so existing log lines gain it for free.
3. Deploy an OpenTelemetry Collector and a backend (Tempo or Jaeger) in `infra/docker-compose.yml`, plus a Grafana data source.
4. Sampling: 100 percent in development, a small head-sample or tail-sample in production keeping all error and slow traces.
5. Tenant id as a span attribute (traces tolerate high cardinality) but never PII or bodies.
6. Link metrics to traces with exemplars.

### Specific value for this platform

The slowest user-visible path, ticket create, spans: gateway filter, JWT validation (with the revocation lookup), tenant binding, number allocation transaction, business transaction (ticket, activity, audit, outbox, idempotency rows), cache eviction after commit. A trace would show the 6.1 s connection hold directly. Asynchronous paths (outbox relay, broker, consumer) would appear as linked traces, since the relay runs later, which is where tracing needs span links rather than parent-child.

### Performance and security

Tracing overhead is a few percent at moderate sampling. Security: spans must not carry request bodies, tokens or PII; tenant ids are acceptable. Collectors should authenticate exporters.

### Common mistakes

Sampling everything in production (cost), losing context across thread pools and message boundaries, naming spans with dynamic values (explosion), and tracing without a plan to use it.

### Alternatives

Correlation ids only (current), service mesh telemetry (Istio, Linkerd; the project has no mesh), vendor APM agents (Datadog, New Relic), eBPF-based tracing.

### Interview Q&A

**Q1. Your system has no tracing. A request is slow. What do you do?**
Use the correlation id to find the access log line duration at each hop, compare gateway and core durations, check `hikaricp_connections_pending` and cache metrics, and run JFR or a thread dump if it persists. That is exactly Runbook 1. It works but is manual, which is the case for tracing.

**Q2. How does trace context cross RabbitMQ?**
As message headers (`traceparent`). The consumer starts a span linked to the producer's span. With the transactional outbox, the context must be stored in the outbox row because the relay runs later in a different thread, otherwise the chain breaks at the database. The platform already stores the correlation id there; the trace context would be stored beside it.

**Q3. Head or tail sampling?**
Head sampling is simple and cheap but may drop the interesting traces. Tail sampling keeps all errors and slow traces but needs a collector holding spans in memory. A mix is common.

**Q4. Why not simply use the correlation id forever?**
It has no timing and no hierarchy, so you cannot see which span owned the delay. It remains valuable as the human-friendly handle.

## M.6 Prometheus and Grafana in `infra/`

### What it is and why it exists

Prometheus scrapes and stores metrics and evaluates alert rules; Grafana visualises them. Together they turn the raw `/actuator/prometheus` text into dashboards and alerts.

### What is in the repository

| File | Contents |
|---|---|
| `infra/prometheus/prometheus.yml` | 15 s scrape and evaluation interval; jobs `core-service` (`core-service:8081`), `notification-service` (`:8082`), `gateway` (`gateway:8090`, the management port), `rabbitmq` (`rabbitmq:15692/metrics`), `prometheus`; each with a `service` label |
| `infra/docker-compose.yml` | images `prom/prometheus:v2.54.1`, `grafana/grafana:11.2.2`, healthchecks, ports bound to 127.0.0.1, secrets via `${VAR:?...}` |
| `infra/grafana/provisioning/datasources/prometheus.yml` | Prometheus data source (uid `prometheus`, non-editable, default) |
| `infra/grafana/provisioning/dashboards/dashboards.yml` | auto-loads dashboards from the dashboards directory |
| `infra/grafana/dashboards/cx-platform-overview.json` | dashboard "CX Platform Overview" with an `$application` variable |

The dashboard's rows and panels (queries read from the JSON): HTTP (request rate by service, 5xx error rate, average latency, p95 latency, max latency per URI top 10), Database pool (active, idle, pending, max; connection acquire time), Cache (hit ratio by cache, lookups by result), Queues and notifications (queue depth ready and unacked, notification outcomes), Gateway (proxied by route and status, rate limited, upstream errors), JVM (heap used, process CPU).

### How it works internally

Prometheus keeps a time-series database on local disk (blocks of 2 hours compacted), pulls over HTTP, and evaluates PromQL. Grafana queries Prometheus through the proxy data source; provisioning files make the stack reproducible from code (no click-ops).

### Why selected

Open source, no licensing, the Spring Boot default, provisionable as code, and a good fit for a Compose stack on a developer machine.

### Execution status

**NOT RUN.** `infra/README.md`: "Docker is not installed in the authoring environment. Only YAML/JSON syntax was checked." The dashboard has been parsed as JSON; it has never been rendered. When the first run happens, expect some panels to be empty (p95 until histograms are enabled; `cx_notifications_events_total` unless the notification service runs standalone), and a scrape target to show `DOWN` if services run on the host: edit targets to `host.docker.internal:<port>` as `infra/README.md` explains.

### Running it (on a Docker machine)

```bash
cp env.example .env       # dummy values; edit the CHANGE_ME ones
docker compose -f infra/docker-compose.yml --env-file .env up -d
docker compose -f infra/docker-compose.yml --env-file .env --profile apps up -d --build   # + services
# Prometheus: http://127.0.0.1:9090/targets   Grafana: http://127.0.0.1:3000
```

### Implemented versus not implemented

Implemented as files: scrape config, provisioning, one dashboard. Not implemented: alert rule files (`rule_files:` is absent from `prometheus.yml`), Alertmanager, recording rules, long-term storage, dashboards per tenant or per SLO, dashboard tests.

### Performance

At 15 s scrape and a handful of targets, load is trivial. Pitfalls arise at scale: series count (memory), long-range queries (use recording rules), retention (default 15 days).

### Security

Bind to localhost (done), change the Grafana admin password (compose requires it via environment), do not expose Prometheus publicly (no auth by default), restrict dashboards that show tenant-level data, and scrape over a private network only.

### Common mistakes

Dashboards with 40 panels no one reads, no dashboard for the on-call's first five minutes, panels without units, queries that return nothing silently (use `or vector(0)` carefully), and treating a dashboard as the alert.

### Debugging

Prometheus Targets page for scrape errors; Status, TSDB for cardinality; query the metric name in the Explore view before blaming the panel; check the `application` label matches `$application` options.

### Alternatives

Datadog, New Relic, Grafana Cloud, CloudWatch, Elastic Observability, VictoriaMetrics.

### Interview Q&A

**Q1. Why does the gateway use a separate management port?**
So Prometheus can scrape actuator without exposing it on the public port; the management port stays on the internal network. It is a simple, strong default.

**Q2. How would you design the first dashboard an on-call opens?**
The four golden signals per service at the top (traffic, errors, latency, saturation), then dependencies (database pool, cache, queue depth), then business counters. That is the structure of `cx-platform-overview.json`.

**Q3. Pull or push metrics?**
Pull makes a down target visible and keeps config central; push suits short-lived jobs (Pushgateway) and some network setups. The platform's scheduled jobs live inside long-running services, so pull works.

**Q4. The p95 panel is empty. Why?**
Percentile histograms are disabled by default. Enable `management.metrics.distribution.percentiles-histogram.http.server.requests=true` (and consider `slo` buckets), then redeploy.

## M.7 Alerting

### What it is and why it exists

An alert is an automated notification that a condition requiring human action exists. Good alerts are symptom-based (users are affected), actionable (a runbook exists) and rare. They exist so humans do not stare at dashboards.

### How it works internally

Prometheus evaluates alert rules each `evaluation_interval`; a rule with `for: 5m` must be true continuously before it fires (pending to firing); Alertmanager groups, deduplicates, silences and routes to pager, chat or e-mail.

### Status

**Not implemented.** The repo has no rule file and no Alertmanager. The observability diagram lists the intended alerts (p95, 5xx rate, queue depth, pool saturation). The runbooks each end with a "Prevent" line naming an alert. Below is a proposed rule file, **not applied and not tested**:

```yaml
groups:
  - name: cx-platform
    rules:
      - alert: HighErrorRatio
        expr: sum by (application) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
              / sum by (application) (rate(http_server_requests_seconds_count[5m])) > 0.02
        for: 5m
        labels: {severity: page}
        annotations: {runbook: "PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md#runbook-1-slow-api"}
      - alert: SlowRequestsP95
        expr: histogram_quantile(0.95, sum by (le, application) (rate(http_server_requests_seconds_bucket[5m]))) > 1
        for: 10m
        labels: {severity: ticket}
      - alert: DbPoolSaturated
        expr: hikaricp_connections_pending > 0
        for: 2m
        labels: {severity: page}
      - alert: RedisFallbacks
        expr: rate(cx_cache_fallback_total[5m]) > 0
        for: 5m
        labels: {severity: ticket}
      - alert: OutboxNotPublishing
        expr: rate(cx_outbox_enqueued_total[10m]) > 0 and rate(cx_outbox_published_total[10m]) == 0
        for: 10m
        labels: {severity: page}
      - alert: DeadLetterQueueNotEmpty
        expr: rabbitmq_queue_messages_ready{queue="cx.notifications.dlq"} > 0
        for: 5m
        labels: {severity: ticket}
      - alert: AiDegradedRatioHigh
        expr: increase(cx_ai_analyze_total{result="degraded"}[15m]) / increase(cx_ai_analyze_total[15m]) > 0.3
        for: 15m
        labels: {severity: ticket}
      - alert: TargetDown
        expr: up == 0
        for: 2m
        labels: {severity: page}
```

Notice the design: pages for user-visible damage and silent data-pipeline failures; tickets for degraded but non-critical conditions; every alert has a `for:` clause to ignore blips.

### Why this approach

Symptom-based alerts (errors, latency) plus a few cause-based alerts for failures that are invisible to users until late (outbox stuck, DLQ). Burn-rate alerts (M.8) are the better version of the first two.

### Performance and security

Rule evaluation is cheap. Do not put secrets in annotations; do not send tenant data in alert text; secure the Alertmanager webhook endpoints.

### Common mistakes

Alerting on causes (CPU 80 percent) instead of symptoms, no runbook link, thresholds copied from blogs, no `for:`, paging for things that can wait until morning, and never reviewing which alerts fired and were ignored.

### Debugging an alert that did not fire

Check rule syntax with `promtool check rules`, evaluate the expression in the Prometheus UI, check the `for` duration, check Alertmanager routing and silences.

### Alternatives

Grafana-managed alerts, Datadog monitors, cloud-native alarms, and on-call tools such as PagerDuty or Opsgenie for routing and escalation.

### Interview Q&A

**Q1. What makes an alert good?**
It fires only when a human must act, within minutes, with a link to what to do. Every page should be a new problem that needs judgment; automate the rest.

**Q2. Why `for: 5m`?**
To filter transient spikes and avoid flapping; the trade-off is slower detection by that interval. Pick it relative to your SLO window.

**Q3. How do you reduce alert fatigue?**
Delete or downgrade alerts nobody acts on, group related alerts, review the alert log weekly, and tie pages to SLO burn.

**Q4. Which alert would you write first for the outbox?**
Enqueued rising while published is flat for 10 minutes, because a stuck relay silently delays every notification while all HTTP metrics stay green.

## M.8 SLIs, SLOs and error budgets (with a worked example)

### What they are and why they exist

- SLI (service level indicator): a measured ratio describing user-visible behaviour, such as "good requests divided by total requests".
- SLO (objective): the target for an SLI over a window, such as 99.5 percent over 30 days.
- SLA (agreement): a contractual promise with penalties, usually looser than the SLO.
- Error budget: `1 - SLO`, the amount of unreliability you are allowed. It turns reliability into a spendable resource and a negotiation tool between feature velocity and stability.

The local numbers in this project are not SLOs: `perf/README.md` states "Thresholds in the k6 scripts are starting points, not SLOs," and the guide says no number is a production figure. The example below is **illustrative**.

### Illustrative worked example (invented targets)

Service: ticket API (`/api/v1/tickets` reads and writes) behind the gateway.

SLIs:

1. Availability SLI = requests with status below 500 divided by all requests (excluding 429 caused by clients and 4xx).
2. Latency SLI = requests served in under 500 ms divided by all requests (needs histogram buckets, so `percentiles-histogram` or an SLO bucket at 0.5 s must be enabled).
3. Freshness SLI for notifications = events delivered to IN_APP within 60 s divided by events published.

SLOs over a rolling 30 days: availability 99.9 percent; latency 99 percent under 500 ms; notification freshness 99 percent.

Error budget arithmetic for availability: 30 days is 43 200 minutes. At 99.9 percent the budget is 0.1 percent, so 43.2 minutes of full outage-equivalent. In request terms, with 20 million requests in the window, 20 000 bad requests are allowed.

Scenario: on day 12 a bad release raises the 5xx ratio to 5 percent for 20 minutes at 400 requests per second.
- Bad requests = 0.05 x 400 x 1 200 s = 24 000.
- That already exceeds the whole 20 000-request budget by 20 percent, so the SLO for the window is breached on one incident, and the team freezes risky releases for the rest of the window and prioritises reliability work (the budget policy).

Burn-rate alerting (multi-window) replaces fixed thresholds:
- Burn rate = (observed bad ratio) / (allowed bad ratio). At 5 percent bad and 0.1 percent allowed, burn rate is 50.
- A burn rate of 14.4 sustained for 1 hour consumes 2 percent of a 30-day budget (14.4 x 1 h / 720 h = 2 percent); this is the classic fast-burn page condition. A burn rate of 6 over 6 hours consumes 5 percent: a slower burn that pages or tickets depending on policy. A burn rate of 1 over 30 days consumes exactly the budget.
- Example rule shape for the fast burn (long window 1 h and short window 5 m, both above 14.4 x 0.001 = 0.0144):

```promql
(
  sum(rate(http_server_requests_seconds_count{status=~"5.."}[1h])) / sum(rate(http_server_requests_seconds_count[1h])) > 0.0144
)
and
(
  sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) > 0.0144
)
```

The short window makes the alert reset quickly once the problem is fixed; the long window prevents paging for brief spikes.

Latency SLO check against the measurements here, with the proper caveats: the local single-laptop results for `ticket-list` had p95 of 58.4 ms and p99 of 91.3 ms; `ticket-create` before the numbering fix had p99 of 1 943.5 ms. A "99 percent under 500 ms" SLO would have been violated by the create path under 10-client single-tenant load before the fix (p99 above 500 ms in several runs) and met after (p99 81 to 84 ms). This is a useful illustration of how the SLO would have guided the fix, though these laptop figures are not production data.

### Why error budgets work

They make the trade-off explicit: 100 percent is the wrong target (cost grows steeply, users cannot tell the difference beyond their own network's reliability). A budget lets product and engineering share one number.

### Implemented versus not implemented

Not implemented: SLO definitions, histogram buckets for latency SLIs, burn-rate rules, an SLO dashboard, a budget policy. Implemented prerequisites: HTTP metrics by `status` and `uri`, an outbox event pipeline whose created-to-sent timing could produce the freshness SLI, and runbooks naming alerts.

### Performance, security, mistakes

SLI queries over 30 days are expensive; use recording rules. Do not count health checks and synthetic traffic as user traffic. Common mistakes: SLOs set from what the system happens to do rather than what users need, too many SLOs, ignoring 4xx caused by server bugs (a validation regression returning 400s is a bad experience), and no consequences when the budget is exhausted.

### Debugging an SLO miss

Break the bad ratio down by `uri`, `status` and `application`; check deploy times; look at dependency saturation; compare against the incident log.

### Alternatives

Apdex, fixed-threshold alerts, customer-experience scores from real user monitoring in the browser.

### Interview Q&A

**Q1. Explain an error budget to a product manager.**
The SLO says the system may fail 0.1 percent of requests this month. That is about 43 minutes of full outage. We have used 60 percent already, so for the rest of the month, risky launches wait and reliability fixes go first. If we are far under budget, we can ship faster or run riskier experiments.

**Q2. Why multi-window burn-rate alerts?**
A single threshold either pages too late (long window) or too noisily (short window). Combining windows detects fast burns early and resets quickly.

**Q3. What SLIs would you define for notifications?**
Freshness (delivered within 60 s), success ratio (processed vs failed plus dead), and backlog age. Counts come from `cx_notifications_events_total{result}` and an outbox age gauge that does not yet exist.

**Q4. Should the 429s count as bad events?**
Rate-limited requests caused by a client exceeding its limit usually are excluded; 429s caused by an undersized limit hurting legitimate users should be counted. Decide and document.

## M.9 Profiling

### What it is and why it exists

Profiling measures where a program spends CPU time, allocates memory or waits on locks. Metrics tell you that something is slow; a profile tells you which code.

### JVM tools

| Tool | What it shows | Overhead | Notes |
|---|---|---|---|
| `jstack` or `jcmd <pid> Thread.print` | Snapshot of all thread stacks and lock owners | negligible | Take 3 dumps 5 to 10 s apart to see what is stuck rather than momentarily busy |
| JFR (Java Flight Recorder) | Low-overhead event recording: CPU samples, allocations, GC, lock contention, I/O | about 1 to 2 percent at `profile` settings | built into the JDK |
| JDK Mission Control | GUI for JFR files | n/a | Method Profiling, Lock Instances, GC |
| async-profiler | Sampling profiler using `AsyncGetCallTrace` and perf events, avoids safepoint bias; produces flame graphs; CPU, wall-clock, allocation and lock modes | low | not in the JDK; Linux and macOS (Windows support is limited) |
| `jmap -histo`, heap dumps | Live object counts by class; full heap for leak analysis | pause for dumps | heap dumps contain sensitive data: restrict access |
| `jcmd GC.heap_info`, GC logs (`-Xlog:gc*`) | Collector behaviour | negligible | |

### Commands documented in this project (NOT EXECUTED)

```bash
java -XX:StartFlightRecording=duration=60s,filename=cx.jfr,settings=profile -jar core-service/target/core-service-1.0.0.jar --server.port=8093
# run the load test, then:
jfr print --events jdk.JavaMonitorEnter,jdk.ThreadPark cx.jfr | head
jfr summary cx.jfr
jcmd <pid> Thread.print | grep -c HikariPool
```

What to look for, per `PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md` section 5.2: Hibernate flush and dirty checking, JSON serialisation, BCrypt (login is intentionally CPU heavy at cost 10), and threads blocked in the JDBC driver on a row lock. Honest status: the profiling steps in section 5.2 to 5.4 were not executed; what was executed is metric-based diagnosis and controlled experiments, including a differential experiment that confirmed the ticket-counter lock without a lock trace ("confirmed by the differential experiment, not by a lock trace").

### How to read a flame graph

The x axis is proportion of samples (not time order); the y axis is stack depth. A wide frame is expensive or contains expensive callees; look at the widest plateau at the top of stacks (self time). For wall-clock mode, wide frames in `park` or socket read show waiting.

### Statement-level visibility

`-Dspring.jpa.properties.hibernate.generate_statistics=true` (already on in the test profile; `NPlusOneGuardTest` uses it), `logging.level.org.hibernate.SQL=DEBUG` temporarily (never with parameter logging in shared environments), and `pg_stat_statements` plus `EXPLAIN (ANALYZE, BUFFERS)` on PostgreSQL (NOT EXECUTED).

### Browser profiling (NOT EXECUTED)

- Chrome DevTools Performance panel with CPU throttling 4x and network "Fast 4G"; read the flame chart for long tasks above 50 ms.
- Network panel: waterfall, request sizes, and the `X-Correlation-Id` response header to jump to server logs.
- React DevTools Profiler: record a filter change on the ticket list, enable "why did this render".
- Lighthouse on `npm run build && npm run preview` for Core Web Vitals (LCP, INP, CLS).
- Bundle analysis: `npx vite-bundle-visualizer`. Measured fact: first visit pulls about 104 kB gzip, lazy routes add 1 to 5 kB gzip each (guide section 9).

### Safety

Profile a replica or a canary first. Heap dumps and JFR files can contain secrets and PII (tokens in memory, tenant data); store them encrypted with short retention, and never attach them to a ticket. Do not leave `heapdump` or `env` actuator endpoints exposed (they are not exposed here).

### Common mistakes

Profiling a cold JVM (JIT not warmed: the measured first dashboard call was 119.5 ms vs 3.23 ms hit on a warm JVM), profiling the load generator on the same machine (the local runs shared four cores with the generator), optimising before measuring, trusting a single run, and ignoring GC.

### Debugging recipe: "CPU is 90 percent"

1. `top -H` or `jcmd Thread.print` to map hot native thread ids (hex) to Java threads.
2. JFR or async-profiler for 30 to 60 s in `cpu` mode.
3. Read top self-time frames; check for BCrypt storms (credential stuffing), JSON serialisation of huge pages, regex backtracking (the sanitiser uses regexes on user text; length is bounded for that reason), or GC.

### Alternatives

Commercial APMs (continuous profiling), Pyroscope or Parca (continuous profiling), VisualVM, YourKit, `perf`.

### Interview Q&A

**Q1. CPU is saturated and latency is up. How do you find the cause?**
Metrics first (which endpoint, which instance), then a short CPU profile on one instance, then read flame graph self-time, then confirm with a targeted experiment. Do not restart before capturing a thread dump or JFR, because restart destroys the evidence.

**Q2. Thread dump shows 200 threads in `HikariPool.getConnection`. What does it mean?**
The pool is exhausted: threads wait for a connection. Find the connections' holders (threads in `RUNNABLE` inside the JDBC driver, usually waiting on a lock), look at the oldest transactions in `pg_stat_activity`, and fix the blocker rather than enlarging the pool.

**Q3. Why prefer async-profiler over simple sampling?**
Safepoint bias: classic sampling profilers only observe threads at safepoints and skew toward certain code. Async-profiler samples via OS signals and gives truer CPU attribution; the trade-off is it is an external tool and platform support varies.

**Q4. How would you investigate a memory leak?**
Watch `jvm_memory_used_bytes{area="heap"}` after full GCs trending up, take two histograms (`jmap -histo:live`) apart in time, compare growing classes, then take a heap dump and find the retaining path in MAT. Handle the dump as sensitive data.

## M.10 Incident response process

### What it is and why it exists

Incident response is the agreed way a team detects, triages, mitigates, communicates about and learns from a production failure. A defined process turns panic into a checklist and shortens time to recovery.

![Figure: Incident timeline from alert to postmortem](docs/diagrams/png/pdf2M-incident-timeline.png)

### Roles

| Role | Responsibility |
|---|---|
| Incident commander (IC) | Owns coordination and decisions, assigns tasks, does not debug |
| Operations lead / responders | Investigate and apply mitigations |
| Communications lead | Status updates to stakeholders and customers on a fixed cadence |
| Scribe | Keeps the timeline (times, actions, hypotheses) |

In small teams one person can wear two hats, but not IC and primary debugger at once for a serious incident.

### Phases

1. Detect: alert, customer report, or a teammate noticing. (Here: not alert-driven yet; detection is manual.)
2. Triage: assign severity from impact, not cause. Example scale: SEV1 data exposure or total outage; SEV2 major feature degraded for many tenants; SEV3 partial degradation or single-tenant impact; SEV4 minor. A suspected cross-tenant exposure is automatically SEV1 (Runbook 8).
3. Mitigate first: the goal is to stop the pain, not find the root cause. Rollback, disable a flag, scale out, fail over, shed load, rate limit, switch `cx.ai.provider` back to the mock.
4. Diagnose with the runbooks: start with the correlation id and the metric quick map.
5. Communicate: first update within minutes, then at a regular cadence even if the message is "no change"; say what users experience, what you are doing, and when the next update is. Do not speculate on cause.
6. Resolve: confirm on the SLIs, not on "the deploy finished".
7. Review: blameless postmortem within about three days; action items with owners and dates.

### Practical rules

- One channel, one IC. Write hypotheses and actions down with timestamps.
- Change one thing at a time and record it.
- Preserve evidence before restarting: thread dump, JFR snapshot, logs for the correlation ids, queue state.
- Follow the principle of least privilege even in an emergency: break-glass access is logged and reviewed; never paste real tokens into chat.
- For suspected security incidents, involve the security team and preserve forensics; containment (revoke tokens, rotate `CX_JWT_SECRET`) before analysis when customer data is at risk.

### Implemented versus not implemented

Implemented: ten runbooks with symptoms, root causes, diagnostics, remediation and prevention. Not implemented: on-call rotation, paging, severity definitions, status page, incident tooling, drills. The process above is conceptual.

### Common mistakes

Debugging before mitigating, no single owner, silent heroics, changing multiple variables, closing the incident when the alert clears rather than when SLIs recover, and skipping the review.

### Interview Q&A

**Q1. First five minutes of a page. What do you do?**
Acknowledge, confirm impact on the user-facing SLI, declare severity, name an IC if SEV2 or higher, open a channel, then check what changed (deploys, flags, config, traffic) and whether a rollback is safe. Mitigation beats diagnosis.

**Q2. How do you communicate during an incident?**
Short factual updates on a fixed cadence: impact, status, next update time. No blame, no speculation, no jargon for customer-facing text.

**Q3. When do you roll back versus fix forward?**
Roll back when the change is the likely cause and rollback is safe (no irreversible migration). Fix forward when rollback is impossible or the fix is trivial and verified. Whichever you choose, say why in the channel.

**Q4. What makes you declare SEV1 for this platform?**
Evidence of cross-tenant exposure, a total outage of login or ticket creation for all tenants, or data loss. The first of these is treated as SEV1 until disproven.

## M.11 Incident walkthroughs: the nine required scenarios

These are illustrative, built on `PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md` runbooks 1 to 9. They are scenarios, not records of real incidents. Commands that need Docker, PostgreSQL, Redis or RabbitMQ were not executed; the metric names, log messages and code behaviours are real. Each walkthrough uses the same structure: scenario, signals, diagnosis, mitigation, root cause and prevention, with a short timeline.

### M.11.1 Slow API

Scenario. At 14:05 users report the ticket list is slow; support sends a correlation id from the browser network tab.

Signals. `http_server_requests_seconds_sum / _count` for `uri="/api/v1/tickets"` rises; the gateway shows `gateway_upstream_errors_total{type="timeout"}` for a few requests (upstream timeout is 30 s).

Diagnosis, in the order of the runbook: (1) check `hikaricp_connections_pending` (is the pool queuing?); (2) cache miss rate and `cx_cache_fallback_total` (is Redis the cause?); (3) the access log line for the correlation id (`http.access ... in 4200ms`); (4) a thread dump counting `HikariPool` waiters; (5) on PostgreSQL, `pg_stat_statements` top by total time and `EXPLAIN (ANALYZE, BUFFERS)` on the slowest list query. Suppose the plan shows a sequential scan on `ticket` for a new filter combination with `tenant_id` not leading the index.

Mitigation. Reduce page size or disable the new filter at the frontend; if one tenant is hammering, rate limit at the gateway.

Root cause. A new filter shipped without an index leading with `tenant_id`.

Prevention. `NPlusOneGuardTest` keeps statement counts flat; add an `EXPLAIN` review to the pull request checklist for list endpoints; enable percentile histograms and alert on p95; keep tenant-first indexes (guide section 7).

Timeline: T+0 report; T+7 confirm via metrics; T+15 mitigation; T+60 index migration (concurrent build); T+3 days postmortem.

### M.11.2 Database connection exhaustion

Scenario. 500 or 503 with "Connection is not available, request timed out after 30000ms".

Signals. `hikaricp_connections_active == hikaricp_connections_max` (10 by default per node), `hikaricp_connections_pending > 0`, `hikaricp_connections_timeout_total` increasing, a latency cliff on every endpoint.

Diagnosis. `hikaricp_connections_usage_seconds_max` shows long holds (6.1 s was observed in the create series). PostgreSQL (not executed): `pg_stat_activity` ordered by transaction age; `idle in transaction` sessions; `pg_locks WHERE NOT granted`; `pg_blocking_pids(pid)`. In the real measured case the cause was transactions waiting on the per-tenant `ticket_counter` row lock while holding a connection.

Mitigation. Find and remove the single blocker (cancel the transaction in an emergency with `pg_cancel_backend`); do not just raise the pool, which makes a saturated database worse; temporarily throttle writes at the gateway.

Root cause. Row lock held until commit by the number allocator; fixed by allocating the number in its own tiny transaction (`TicketNumberAllocator`), measured p99 improvement on H2 only.

Prevention. Alert on pending connections for 2 minutes and on usage max; statement and lock timeouts at the DB role; PgBouncer in transaction mode (compatible with `SET LOCAL`); keep external calls (the AI provider) outside transactions.

### M.11.3 Growing RabbitMQ backlog

Scenario. Users say notifications arrive late.

Signals. `rabbitmq_queue_messages_ready{queue="cx.notifications"}` rising, or `messages_unacked` pinned at prefetch x consumers; `rate(cx_outbox_published_total)` above `rate(cx_notifications_events_total{result="processed"})`.

Diagnosis. `rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers`: consumers is 0? Consumers slow (`concurrency 2`, `max-concurrency 4`, prefetch 10)? A retry storm cycling through `cx.notifications.retry` (5 s TTL, up to 5 attempts, watch `result="failed"`)? Poison messages in `cx.notifications.dlq`? If the backlog is in `outbox_event` PENDING rows instead, the relay or broker is the issue: Runbook 3b (`cx_outbox_enqueued_total` rising, `cx_outbox_published_total` flat, log line "Outbox poll failed" or "moved to FAILED").

Mitigation. Restore or scale consumers; raise `cx.notifications.rabbit.concurrency` and `prefetch`; fix the bad event and re-drive from the DLQ; for FAILED outbox rows reset status for specific ids once the cause is fixed (manual, there is no replay endpoint).

Root cause (example). The consumer's database was slow because it shares the PostgreSQL instance with core during a heavy SLA scan that escalated many tickets at once.

Prevention. Alert on queue depth and DLQ depth above zero; load test event bursts; bound SLA batch size (`SlaJobTest` shows a bounded batch with fair rotation).

Note: in the local profile the in-process relay always succeeds even without listeners, so this scenario only exists in the docker profile, which has never been run.

### M.11.4 Redis unavailable

Scenario. Dashboard and settings pages slow down after a Redis restart.

Signals. WARN `Redis unavailable (read|write|evict); serving without cache for 5s`; `cx_cache_fallback_total{op}` increasing; `cx_cache_total{result="miss"}` dominating; `hikaricp_connections_active` up because more queries reach the database. Requests should not fail by design.

Diagnosis. `redis-cli ... ping`, `info memory`, compose logs; check wrong `CX_REDIS_PASSWORD`, the 500 ms timeout, `maxmemory` eviction and a large `SCAN` from `evictByPrefix`.

Mitigation. Restore Redis; nothing to replay because entries refill on demand; protect the database from the cold-cache load (a dashboard miss costs roughly 6.7 times a hit on a warm JVM locally: 21.73 vs 3.23 ms); do not scale core down.

Evidence status. The resilience is verified only with a mocked template (`CacheServiceTest`) and a Docker IT that has not run: honest label, **NOT EXECUTED against real Redis**.

Prevention. Alert on `rate(cx_cache_fallback_total[5m]) > 0`; size the DB for cold-cache load; TTL jitter (plus or minus 10 percent) and per-node single-flight mitigate the thundering herd; keep authoritative state out of Redis.

### M.11.5 Duplicate asynchronous processing

Scenario. A user receives the same notification twice, or two comments appear after a flaky network.

Signals. `cx_notifications_events_total{result="duplicate"}` increases: that is protection working. Logs: `Duplicate event <id> ignored`.

Diagnosis. The system is at-least-once by design (ADR-007): the relay may publish and crash before marking SENT; a lease (60 s) may expire during a slow publish; the broker may redeliver. Consumers claim `eventId` in `notif_processed_event` with a unique key, so the second delivery becomes a no-op. SQL checks: `notif_processed_event`, `notif_delivery`, `notif_notification` grouped by user (must be empty of duplicates), `outbox_event.status`.

The important distinction: comment creation is not idempotent. A client retry of `POST /tickets/{id}/comments` creates a second comment because only ticket creation takes an `Idempotency-Key` (honest limitation in Runbook 5).

Mitigation. If unique keys held, nothing to fix. For true duplicate rows, a consumer bypassed the claim: fix and delete extras by hand. For duplicate comments, deduplicate manually and advise the client team.

Prevention. Idempotency key on comment creation (not implemented), purge `notif_processed_event` beyond the redelivery window, keep `NotificationProcessorTest`-style redelivery tests for every consumer.

### M.11.6 Frontend rendering delays

Scenario. The ticket list takes seconds to appear or typing in filters lags.

Signals. Browser Network panel: long request time or many refetches; Performance panel: long tasks; high tab CPU; `X-Correlation-Id` on the slow request.

Diagnosis. First rule out the API (Runbook 1). Then: chunk-load delay on first visit (about 104 kB gzip), refetch storms (`staleTime` 15 s, `refetchOnWindowFocus` off, notifications poll every 30 s per tab, mutations invalidate whole key prefixes), 100-row pages, unmemoised derived data, charts re-rendering on each dashboard refetch, a stale `index.html` pointing to removed hashed chunks (the error boundary catches render errors, but chunk-load handling was not verified).

Mitigation. Fix the slow endpoint; narrow invalidation keys; debounce filter input (verify rather than assume); `no-cache` for `index.html`, long cache for hashed `/assets` (already in `nginx.conf`).

Prevention. Review chunk growth in pull requests, add web-vitals reporting, keep polling intervals deliberate. Status: frontend profiling not executed.

### M.11.7 Authorization failures

Scenario. After a release, many users bounce to the login page and some agents get 403.

Signals. `cx_auth_login_total{result="failure"}` rising; 401 with gateway-written bodies vs core-written bodies; `gateway_proxied_total{status="401"}`.

Diagnosis, layer by layer. Call `/api/v1/auth/me` through the gateway (8080) and directly on core (8081): if direct works, the gateway has a different `CX_JWT_SECRET`. Decode the token payload locally and check `exp`, `tid`, `roles`, `jti`; check `revoked_token` for the `jti`; check clock skew over 5 s. For 403: role too low (roles are in the token, so a role change applies only at next login). Remember an unexpected 404 is the intended answer for another tenant's object.

Mitigation. Align `CX_JWT_SECRET` across services (all users re-login after rotation); re-login for expired or revoked tokens; fix the role via `PUT /users/{id}`; tune the login limiter if legitimate users are throttled.

Root cause (example). A configuration change rotated the secret in core but not in the gateway.

Prevention. `RoleMatrixTest` row for every new endpoint; a deployment check that all three services share the secret (config management, not a test); alert on login failure rate per tenant; refresh tokens and key rotation are not implemented.

### M.11.8 Incorrect tenant isolation (suspected exposure)

Scenario. A customer reports seeing another organisation's ticket title in a list.

Treat as SEV1 immediately.

Signals. A user report; possibly `tenant=` in logs not matching the entity's tenant; a dashboard count that does not match.

Diagnosis (guide Runbook 8). Reproduce with that user's token: the other tenant's id must give 404. Run the guard tests `TenantIsolationTest`, `TenancyTest`, `ArchitectureRulesTest`. Grep for bypasses of the Hibernate filter: `JdbcTemplate`, `createNativeQuery`, `nativeQuery = true`, `findById(`. Check cache keys follow `t:{tenantId}:...`. Check thread-context leakage: `TenantContext` and MDC cleared in `finally`. On PostgreSQL, verify RLS is enabled and the app role is not superuser or `BYPASSRLS`.

Containment. Revoke affected tokens (insert `jti` rows) or rotate `CX_JWT_SECRET` to force global re-login; disable the endpoint at the gateway if the leak path is known; preserve logs and audit events as evidence.

Remediation. Fix the missing predicate and add a regression test next to `TenantIsolationTest`; assess exposure (which tenants, which fields, how long) using audit and access logs; notify per the organisation's security and legal process (outside this repo).

Prevention. Keep `ArchitectureRulesTest` strict, enable RLS in production with a non-owner role, add a second-tenant 404 test for every new controller, run the Docker ITs in CI.

### M.11.9 AI provider timeouts

Scenario. Agents report the AI panel shows "AI assistance is temporarily unavailable" and waits several seconds.

Signals. `cx_ai_analyze_total{result="degraded"}` rising (HTTP 200 with `degraded: true`); possibly `result="rate_limited"` (429) for a busy tenant. Audit events `AI_ANALYSIS_DEGRADED` with `details.reason` in `timeout | provider_error | deadline | circuit_open | overloaded | interrupted`.

Diagnosis. The default provider is the deterministic mock and does not time out, so degradation means a configured `HTTP` provider is slow or unreachable, or fault injection is on. Defaults: 3 s per call, up to 2 retries (hard cap 3) with 50 to 200 ms pauses, 10 s total deadline, 30 requests per minute per tenant (per node), breaker opens after 5 consecutive failed analyses for 30 s then allows one trial. So the worst-case wait is about 3 x 3 s + 0.15 s = 9.15 s (arithmetic from the defaults). Check WARN lines `AI provider attempt`, worker thread dumps (`ai-provider` threads stuck in the HTTP client; 4 workers, 16 queued).

Mitigation. Switch `cx.ai.provider` back to the mock (immediate, keeps human approval working); fix the key or egress; lower `max-retries` to shorten waits. Agents can always work manually; nothing else depends on AI.

Prevention. Alert on the degraded ratio; keep the breaker on; keep human approval mandatory; review what data leaves before enabling a real provider (sanitiser redaction is best effort). Evidence status: degraded-path tests exist (`AiApiTest`, `AiResilienceUnitTest`, `AiBreakerAndProviderSelectionTest`) and are part of the 179-test core-service run; no real provider was ever called.

### Interview Q&A for the walkthroughs

**Q1. Pick one incident and describe your first three actions.**
For database exhaustion: check `hikaricp_connections_pending` and `usage_seconds_max` to confirm saturation and find long holds; query `pg_stat_activity` for the oldest transaction and its blocker; remove the blocker. I do not enlarge the pool first.

**Q2. The AI provider is down. Why is that a SEV3 and not a SEV1?**
The feature degrades by design: HTTP 200 with a fallback message, a circuit breaker, a rate limit, human approval, and nothing else depends on AI. Impact is limited to a convenience feature.

**Q3. How do you tell duplicate processing that is expected from a bug?**
Expected: a second delivery of the same `eventId` counted as `duplicate` with no extra rows (unique keys held). Bug: two rows for the same event and user, or two comments after a client retry (no idempotency key on comments).

**Q4. What evidence would you collect before restarting a stuck service?**
Three thread dumps 5 to 10 s apart, a short JFR recording, the relevant log slice by correlation ids, pool and queue metrics, and `pg_stat_activity` output. Then restart.

## M.12 Postmortems

### What it is and why it exists

A postmortem is a written, blameless analysis of an incident: what happened, why the system allowed it, what was done, and what will change. It exists to convert pain into durable improvement and to share learning.

### Principles

- Blameless: assume everyone acted reasonably with the information they had; ask how the system made the failure possible.
- Specific: timeline with timestamps, evidence, contributing factors rather than one root cause.
- Actionable: each action has an owner, a priority and a date, and addresses detection, mitigation or prevention.
- Reviewed: someone outside the incident team reads it; action items are tracked to completion.

### Template

| Section | Contents |
|---|---|
| Summary | One paragraph: impact, duration, trigger |
| Impact | Tenants, requests, SLO budget consumed |
| Timeline | UTC timestamps: detect, mitigate, resolve |
| Root cause and contributing factors | Technical and process |
| What went well / poorly / lucky | Honest assessment |
| Action items | Owner, due date, type (prevent, detect, mitigate) |
| Lessons | What others should learn |

### A worked (illustrative) postmortem: the ticket-create tail latency

This one is based on measured work in the repo, with the usual caveats (H2, laptop, load generator on the same machine).

- Summary: under 10 to 20 concurrent clients on one tenant, ticket creation showed p99 up to about 1 s and max up to 12.9 s while the median stayed 20 to 46 ms.
- Detection: load test (`perf/load-test.mjs`, scenario `ticket-create`), not production.
- Evidence: `hikaricp_connections_usage_seconds_max` of 6.1 s; GC and pool ruled out; a differential experiment spread the same clients over three tenants (different counter rows) and p99 fell to 45 to 87 ms, max at most about 205 ms.
- Root cause: `SELECT ... FOR UPDATE` on the per-tenant `ticket_counter` row inside the create transaction, so all same-tenant creates queued behind each other until commit.
- Fix: `TicketNumberAllocator` takes the number with one atomic `UPDATE ... next_number = next_number + 1` in its own tiny transaction before the business transaction. Trade-off accepted: numbers are no longer gap-free.
- Result: single-tenant c=10 p99 477 to 1 033 ms became 81 to 84 ms; max 0.98 to 3.5 s became 0.15 to 0.22 s. c=20 median and p95 rose slightly because more requests ran truly in parallel.
- What went well: a hypothesis was tested by a controlled experiment rather than assumed; the trade-off was documented in code and tests (`aFailedCreateBurnsANumberButNumbersStayUniqueAndIncreasing`).
- What was missing: no lock trace (confirmed by differential experiment only), no PostgreSQL measurement, no alert that would catch this in production.
- Actions: run the same experiment on PostgreSQL; add `hikaricp_connections_usage_seconds_max` alert; document the gap trade-off for product.

### Common mistakes

Naming individuals, stopping at a single "root cause" such as "human error", action items with no owner, postmortems that nobody reads, and not tracking whether actions shipped.

### Interview Q&A

**Q1. What does blameless mean in practice?**
You never write "X forgot to"; you write "the deploy process allowed a change without a rollback check." People then report mistakes early, which is the only way to learn from them.

**Q2. What makes a good action item?**
It changes the system (an alert, a test, a guardrail, an automation) rather than telling people to be careful; it has an owner and a due date.

**Q3. How do you keep the postmortem culture healthy?**
Leaders model it, reviews are short and focused on improvements, and completion of actions is tracked in a visible place.

**Q4. Who should attend the review?**
The responders, the owning team, a neutral facilitator, and someone from a dependent team; keep it small and time-boxed.

## M.13 Verification status summary for Part M

| Item | Status |
|---|---|
| Log pattern, MDC, correlation id behaviour | Implemented; `PlatformEndpointsTest` covers echo, validation, redaction |
| `/actuator/prometheus` metrics | Implemented; read during MEASURED runs |
| Prometheus config, Grafana dashboard, Compose stack | Written, NOT RUN (Docker unavailable) |
| Percentile histograms | Disabled; documented how to enable |
| Alert rules, Alertmanager, SLO rules | Not implemented; proposals above are untested |
| OpenTelemetry tracing | Not implemented |
| JFR, async-profiler, browser profiling | Commands documented, NOT EXECUTED |
| Incident walkthroughs | Illustrative, based on runbooks 1 to 9 |
| Docker-dependent diagnostics (rabbitmqctl, redis-cli, psql, pg_stat_activity) | NOT EXECUTED |
