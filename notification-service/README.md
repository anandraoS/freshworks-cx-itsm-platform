# notification-service

Spring Boot 3.3.5 / Java 21, package `com.example.cx.notification`. Two modes from the same code:

* **Embedded library** (local profile): core-service depends on the plain jar and `application-local.yml` sets `cx.notifications.embedded=true`. `NotificationAutoConfiguration` registers the
  whole module; `InProcessEventListener` implements `com.example.cx.events.EventListener` (platform-events) so core's in-memory dispatcher can call
  `onEvent(DomainEvent)`. Retries run in-process with the same `RetryPolicy`; exhausted events are persisted as `DEAD` and never thrown to the caller.
* **Standalone app** (`:8082`, docker profile): RabbitMQ consumer on queue `cx.notifications` (bound to topic exchange `cx.events` with keys `ticket.*`,
  `ai.*`; durable, persistent, manual acks, configurable prefetch/concurrency). Failure -> republish to `cx.notifications.retry` (TTL queue, bounded attempts
  tracked in a header, falls back to `x-death` count) -> back to the main queue; attempts exhausted or poison message -> reject to `cx.notifications.dlq`
  via DLX `cx.events.dlx` (status `DEAD`).

Design points: `NotificationProcessor` is a plain class (no broker needed) and idempotent via a `processed_event` unique `eventId`; templates for
ticket.created / assigned / status changed / commented / escalated / sla breached / ai suggestion decided; simulated channels IN_APP and EMAIL (EMAIL only
records SENT and logs); delivery status PENDING/SENT/FAILED/DEAD; `tenant_id` on every `notif_` table; Flyway location `db/notification-migration` with its
own history table `notif_schema_history`; H2 (PostgreSQL mode) locally.

**Configuration files.** The standalone app reads `notification-service.yml` / `notification-service-docker.yml` (selected by `spring.config.name` in `NotificationServiceApplication.main`; surefire sets the same property),
not `application*.yml`, so the library jar never competes with a host application's config. The docker file has **no fallback credentials**: `CX_DB_URL`, `CX_DB_USER`, `CX_DB_PASSWORD`,
`CX_RABBIT_HOST`, `CX_RABBIT_PORT` (default 5672), `CX_RABBIT_USER`, `CX_RABBIT_PASSWORD`, `CX_JWT_SECRET` (same names as core-service and `infra/docker-compose.yml`); startup fails when one is missing. Not executed here (no Docker).

**Tenant checks on events.** The broker/dispatcher is trusted for authenticity, but (1) a payload `tenantId` that differs from the envelope `tenantId` is rejected as poison (`NonRetryableException`), and (2) recipients are
checked against core's `app_user` table (`JdbcRecipientDirectory`, same database in embedded mode and in compose): ids that are not users of the event's tenant are dropped and counted (`recipient_rejected`).
If `app_user` is not reachable (standalone service with its own empty database) the directory abstains and only check (1) applies; `cx.notifications.validate-recipients=false` turns (2) off. The PostgreSQL path of (2)
(binds `app.tenant_id` per transaction because `app_user` is under RLS) was **not executed** (no Docker). Tests: `RecipientValidationTest`, `EmbeddedNotificationsTest` (core).

REST (contract `docs/API-CONTRACT.md`), tenant and user always from the JWT (`tid`, `sub`; HS256 secret `cx.security.jwt.secret`, dummy default):
`GET /api/v1/notifications?unread=true&page&size`, `POST /api/v1/notifications/{id}/read`, `GET /api/v1/notifications/unread-count`.

```
mvn -pl notification-service -am test      # 46 tests; Docker-tagged IT excluded
mvn -pl notification-service -am package   # target/notification-service-1.0.0-exec.jar (standalone) + plain jar (library)
```

`NotificationRabbitDockerIT` (Testcontainers RabbitMQ, tag `docker`) requires Docker and has NOT been executed here. Broker behaviour is otherwise covered
by `NotificationMessageHandlerTest` with a fake channel (ack, duplicate, poison, retry publish, retry exhaustion to DLQ, unconfirmed publish, consumer
failure and recovery).
