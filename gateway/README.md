# gateway

Lean Spring Boot 3.3.5 WebFlux reverse proxy on :8080 (design rationale and trade-offs: `docs/gateway-notes.md`).

* Routes: `/api/v1/notifications/**` -> `routes.notifications.uri` (default = `routes.core.uri`, because notifications are embedded in core locally; docker
  sets `NOTIFICATIONS_URI=http://notification-service:8082`); other `/api/v1/**`, Swagger and OpenAPI paths -> `routes.core.uri` (`CORE_URI`, default `http://localhost:8081`).
* Public (no token): `POST /api/v1/auth/login`, `POST /api/v1/auth/register-organization`, `/swagger-ui*`, `/v3/api-docs*`, `/actuator/health*`, `OPTIONS`.
  Everything else needs `Authorization: Bearer <HS256 JWT>` verified against `cx.security.jwt.secret` (`CX_JWT_SECRET`, the same dummy `dev-only-NOT-A-REAL-SECRET...` default as core-service and the standalone notification-service, so a local gateway and core accept each other's tokens; for local use only).
* `X-Correlation-Id` accepted if `[A-Za-z0-9._-]{1,64}`, otherwise generated; returned on every response and forwarded upstream.
* Rate limit: in-memory token bucket (`GATEWAY_RATE_CAPACITY` default 100, `GATEWAY_RATE_REFILL_PER_SEC` default 20) keyed by JWT subject, else by socket
  peer address. 429 + `Retry-After`, error code `RATE_LIMITED`. Per instance only.
* Errors use the contract schema: 401 `UNAUTHENTICATED`, 429 `RATE_LIMITED`, 503 `UPSTREAM_UNAVAILABLE`, 504 `UPSTREAM_TIMEOUT`, 404 `NOT_FOUND`.
* Actuator on management port `GATEWAY_MANAGEMENT_PORT` (default 8090): `/actuator/health`, `/actuator/health/readiness`, `/actuator/prometheus`
  (custom meters `gateway_proxied_total`, `gateway_rate_limited_total`, `gateway_upstream_errors_total`).

```
mvn -pl gateway -am test          # 14 tests, JDK HttpServer stubs
mvn -pl gateway -am package       # target/gateway-1.0.0.jar
```
