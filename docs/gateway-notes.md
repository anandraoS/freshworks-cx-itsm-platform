# Gateway notes: when a gateway helps, and what it costs

## What this project's gateway does

`gateway/` is a deliberately small Spring Boot WebFlux reverse proxy (port 8080). In order, per request:
correlation id (accept a well-formed `X-Correlation-Id` or generate one) -> edge JWT check (signature + expiry, HS256 shared secret, public routes
exempt) -> in-memory token-bucket rate limit (per user, or per socket address for anonymous routes) -> route and stream to the upstream.
`/api/v1/notifications/**` goes to `routes.notifications.uri`, everything else under `/api/v1/**` to `routes.core.uri`. Upstream down gives 503,
timeout gives 504, rate limited gives 429, all in the contract error schema. Actuator (health, readiness, Prometheus) is on a separate management port.

## Spring Cloud Gateway vs a hand-written WebFlux proxy (decision)

Chosen: **plain WebFlux proxy**. Reasons: (1) three routes and four cross-cutting concerns do not need a route DSL; (2) fewer moving parts and no
Spring Cloud BOM/Boot version coupling (Spring Cloud releases lag Spring Boot; pinning to Boot 3.3.5 needs the matching 2023.0.x train); (3) the
behaviour is ~300 lines that are fully unit-testable with JDK `HttpServer` stubs; (4) interviews benefit from being able to explain every line.
Trade-off: we own streaming, header hygiene, timeouts and error mapping, and we lack Spring Cloud Gateway's retries, circuit breaker filter,
Redis rate limiter, websocket proxying and dynamic route refresh. If the route count or filter variety grows, switch to Spring Cloud Gateway rather
than growing this proxy.

## When a gateway helps

* **One public entry point.** Clients (the React and Angular SPAs) know one origin; services can move, split or be renamed behind it. Notification-service was split out
  of core without any frontend change: only a route property moved.
* **Cross-cutting policy in one place**: TLS termination, correlation ids, coarse authentication, rate limiting, request size/time limits, CORS, security headers.
* **Protecting services from abuse** before it consumes threads, DB connections or licensed resources (cheap 401/429 at the edge).
* **Observability**: a uniform access metric and trace root for every call.
* **Gradual migration** (strangler pattern): route some paths to the new service, others to the monolith.

## What it costs

* **Another hop and another failure domain.** Adds latency (small), and if the gateway is down everything is down, so it needs replicas, health checks
  and capacity planning like any tier-0 service.
* **Duplicated logic and drift.** Edge JWT validation must stay in sync with core (same secret/algorithm). Here the gateway only checks signature and
  expiry; revocation (jti), roles and tenant rules stay authoritative in core. Never rely on the gateway as the only authorisation layer.
* **Per-instance state.** The token bucket is in memory: N replicas means an effective limit of N times the configured one, and a restart resets it.
  A truly global limit needs Redis (or an edge/CDN limiter). Likewise a single instance is fine locally, not a fleet-wide quota.
* **Operational surface**: more config, more dashboards, one more thing to deploy and secure (it holds the shared secret). Header trust is a classic bug
  source: this gateway does not trust a client-supplied `X-Forwarded-For` for rate limiting and replaces it upstream.
* **Business logic creep.** Gateways attract "just one more rule". Keep domain decisions (permissions, validation, tenant logic) in services.

## Rules of thumb

* One service plus a load balancer/ingress that already does TLS: skip a custom gateway.
* Several services, one SPA, need for uniform edge policy: a gateway pays for itself.
* Need a managed feature set (OAuth2 relay, JWKS, WAF, quotas per API key): use a managed or off-the-shelf gateway (cloud API gateway, Kong, Envoy,
  Spring Cloud Gateway) instead of extending this one.
* Service-to-service traffic does not go through the edge gateway; use internal networking, mTLS or a mesh as needed.

## Failure modes covered by tests (JDK HttpServer stubs)

routing by path prefix, body/status/header passthrough, correlation id generation/propagation/sanitising, 401 for missing/invalid/expired/wrong-secret
tokens, public routes without a token, 429 with `Retry-After` and independent per-user buckets, 503 when the upstream is down, JSON 404 for unknown
paths, health/readiness/Prometheus on the management port and not on the public port. Not covered: slow-upstream timeout (504) and large streaming bodies.
