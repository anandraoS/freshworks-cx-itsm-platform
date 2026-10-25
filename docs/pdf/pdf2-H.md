# Part H. RabbitMQ and distributed systems

This part explains asynchronous messaging with RabbitMQ and the distributed-systems ideas around it (delivery guarantees, idempotency, ordering, the transactional outbox, eventual consistency, sagas, CAP, backpressure). It then shows precisely what this platform does, with real file paths, and where the code stops.

> **Honesty box.** No Docker was available, so **no RabbitMQ broker was ever started**. `RabbitEventPublisher`, `NotificationRabbitConfiguration`, the real queues, exchanges, bindings, publisher confirms, dead-lettering and TTL expiry were never exercised against a broker. What did run: the outbox with an in-JVM dispatcher (`LocalEventPublisher`, profile `local`, H2); the notification processor (idempotency, retries at the processor level) on H2; and `NotificationMessageHandlerTest`, which drives the consumer logic with a **mocked** AMQP `Channel`. The Testcontainers tests `RabbitRedisDockerIT` and `NotificationRabbitDockerIT` exist and were not run. Any broker command below is a **To-run command**.

Labels: **Project-implemented behaviour** means code in the repository; **Conceptual learning example** means a teaching example that is not in the repository.

## H.1 Why messaging, and what RabbitMQ is

### What it is

A message broker accepts messages from producers, stores them, and delivers them to consumers. RabbitMQ implements AMQP 0-9-1: producers publish to **exchanges**, exchanges route to **queues** using **bindings**, consumers read from queues.

### Why it exists

Synchronous calls couple the caller to the callee's availability and speed. A broker gives temporal decoupling (the consumer may be down), load levelling (a queue absorbs bursts), fan-out (one event, many consumers) and retry. In this platform, a ticket write should not wait for email or in-app notification delivery, and a notification outage must not fail ticket creation.

### How it works internally

- A **connection** is a TCP connection; **channels** are lightweight virtual connections multiplexed over it. Channels are not thread-safe for publishing; frameworks use one per thread.
- **Queues** live on a node; **classic queues** and **quorum queues** (Raft replicated, recommended for durability) are the main types; **streams** are log-like. Messages are held in memory and paged to disk; persistent messages in durable queues are written to disk.
- The broker is **at-most-once or at-least-once by configuration**, never exactly-once end to end (H.9).
- Flow control: when memory or disk alarms trigger, the broker blocks publishers (backpressure).

### Why selected for this project

RabbitMQ is a conventional choice for task and event distribution with routing flexibility, per-message acknowledgements, TTL and dead lettering built in. The requirement here is moderate-volume domain events to one consumer service. Kafka would be chosen for very high throughput and replay of a long log; this project does not need replay or partitioned ordering, which is a defensible reason (stated as a trade-off in H.17).

### How THIS PROJECT uses it

**Project-implemented behaviour.** The topology picture is `docs/diagrams/png/10-rabbitmq-topology.png`, and a routing detail diagram follows in H.2.

![Figure: RabbitMQ topology of the platform](docs/diagrams/png/10-rabbitmq-topology.png)

Components:

| Component | File | Role |
|---|---|---|
| Event contract | `platform-events/src/main/java/com/example/cx/events/DomainEvent.java` | record with `eventId`, `type` (routing key), `schemaVersion`, `tenantId`, `correlationId`, `occurredAt`, `payload` map; constants for exchange and queue names |
| Outbox | `core-service/.../outbox/OutboxService.java`, `OutboxRelay.java`, `OutboxRepository.java`, `OutboxPurgeJob.java` | durable staging and relay |
| Publisher (docker) | `core-service/.../outbox/RabbitEventPublisher.java` | publishes to `cx.events` |
| Publisher (local) | `core-service/.../outbox/LocalEventPublisher.java` | calls `EventListener` beans in the same JVM |
| Topology and consumer | `notification-service/.../rabbit/NotificationRabbitConfiguration.java` | declares queues and bindings, listener container |
| Consumer logic | `notification-service/.../rabbit/NotificationMessageHandler.java` | manual ack, retry, dead-letter |
| Business processing | `notification-service/.../NotificationProcessor.java`, `store/NotificationStore.java` | idempotent processing in the `notif_*` tables |

Profiles: in `local`, RabbitMQ auto-configuration is excluded (`application-local.yml`) and the notification module is embedded in core-service (`cx.notifications.embedded: true`). In `docker`, `application-docker.yml` configures the connection with `publisher-confirm-type: correlated`, `publisher-returns: true` and `template.mandatory: true`, and the separate notification-service runs the real consumer.

## H.2 Exchanges, queues, bindings and routing keys

### What they are

- **Exchange**: a router that receives messages. Types: **direct** (exact routing key match), **topic** (pattern match with `*` for exactly one word and `#` for zero or more words, words separated by dots), **fanout** (all bound queues), **headers** (match on headers).
- **Queue**: an ordered buffer from which consumers read.
- **Binding**: a rule linking an exchange to a queue, with a binding key (pattern).
- **Routing key**: set by the producer on each message.
- The **default exchange** (empty name) is a direct exchange that routes to the queue whose name equals the routing key. It is how the consumer republishes to a retry queue by name.

A message that matches no binding is **unroutable**: dropped silently by default, or returned to the publisher if published with the `mandatory` flag and a returns handler exists.

![Figure: Routing keys through the topic exchange to the notifications queue and the dead-letter path](docs/diagrams/png/23-amqp-routing-keys.png)

### How THIS PROJECT uses it

**Project-implemented behaviour, NOT EXECUTED.** From `NotificationRabbitConfiguration`:

| Entity | Type and arguments |
|---|---|
| `cx.events` | topic exchange, durable |
| `cx.events.dlx` | fanout exchange, durable |
| `cx.notifications` | durable queue, `x-dead-letter-exchange = cx.events.dlx` |
| `cx.notifications.retry` | durable queue, `x-message-ttl = retryDelayMs` (5000), `x-dead-letter-exchange = ""` (default exchange), `x-dead-letter-routing-key = cx.notifications` |
| `cx.notifications.dlq` | durable queue, bound to `cx.events.dlx` |
| Bindings | `cx.events` to `cx.notifications` with `ticket.#`; `cx.events` to `cx.notifications` with `ai.#`; `cx.events.dlx` to `cx.notifications.dlq` |

The routing keys are the event types: `ticket.created`, `ticket.assigned`, `ticket.status.changed`, `ticket.commented`, `ticket.escalated`, `ticket.sla.breached`, `ai.suggestion.decided` (listed in `OutboxService`). The configuration comment explains the use of `#` rather than `*`: `ticket.sla.breached` and `ai.suggestion.decided` have three words, and `ticket.*` would not match them.

**An important design observation (from reading the code, not from a run).** The exchange `cx.events` is declared by both core-service (`RabbitEventPublisher.Topology`) and notification-service, but the **queue and its bindings are declared only by notification-service** (its `RabbitAdmin`). If core-service publishes before any notification-service instance has ever connected, the messages have no bound queue: they are unroutable. Because `template.mandatory: true` is set and `RabbitEventPublisher` registers **no returns callback** (a search for `ReturnsCallback` in the repository finds nothing), the broker returns the message, and Spring AMQP only logs that a message was returned with no callback. The publisher confirm still arrives as an ack (the broker handled the message), so `waitForConfirmsOrDie` succeeds and the outbox row is marked `SENT`. Result: **events published before the queue first exists can be silently lost.** Fixes: declare the queue and bindings in core-service as well (idempotent declaration with identical arguments), declare them in a shared definitions file loaded by the broker, or add a returns callback that fails the publish so the outbox retries. This is a good example of "configured is not the same as verified".

### Conceptual learning example: topic matching

| Binding key | Matches `ticket.created` | Matches `ticket.sla.breached` | Matches `ai.suggestion.decided` |
|---|---|---|---|
| `ticket.#` | yes | yes | no |
| `ticket.*` | yes | no (three words) | no |
| `#` | yes | yes | yes |
| `*.created` | yes | no | no |

Declare and test it with the management tool (**To-run commands**, any RabbitMQ):

```bash
rabbitmqadmin declare exchange name=cx.events type=topic durable=true
rabbitmqadmin declare queue name=demo durable=true
rabbitmqadmin declare binding source=cx.events destination=demo routing_key='ticket.#'
rabbitmqadmin publish exchange=cx.events routing_key=ticket.sla.breached payload='{"x":1}'
rabbitmqadmin get queue=demo ackmode=ack_requeue_false
```

### Performance, security, mistakes, debugging

- Performance: topic matching cost grows with the number of bindings; keep binding counts reasonable. Many small queues cost memory.
- Security: use per-service users with least privilege (configure and write/read permissions limited by regex on resource names, topic permissions), TLS on 5672 (`5671`), and keep the management port (15672) bound to localhost (compose binds both ports to `127.0.0.1`). Credentials come from `CX_RABBIT_USER` and `CX_RABBIT_PASSWORD` with no defaults; the compose file requires `RABBITMQ_DEFAULT_USER` and `RABBITMQ_DEFAULT_PASS`. Default guest credentials are not used.
- Mistakes: using `*` where `#` is needed; declaring a queue with different arguments than an existing one (the broker refuses with `PRECONDITION_FAILED`: changing `x-message-ttl` requires deleting the queue); forgetting that exchange or queue declaration must be done by something.
- Debugging: management UI at `http://127.0.0.1:15672` (Exchanges, Queues, bindings), `rabbitmqctl list_bindings`, `rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers`, publishing with the Tracing plugin or `rabbitmqadmin publish` and watching where the message lands.

### Alternatives

Direct exchange per event type (explicit but many bindings), fanout (every consumer gets everything and filters), a header exchange, or one queue per consumer service with a topic exchange (the standard publish-subscribe pattern; this project has a single consumer service).

### Interview Q&A

**Q1. Explain what happens to a message published with routing key `ticket.sla.breached` in your setup.**
It goes to the durable topic exchange `cx.events`. The binding `ticket.#` matches (three words), so it is routed to the durable queue `cx.notifications`. The consumer reads it with manual acknowledgement. If processing succeeds it is acked; if not, it is retried through `cx.notifications.retry` or dead-lettered to `cx.notifications.dlq`.

**Q2. What is the difference between `*` and `#`?**
`*` matches exactly one word, `#` matches zero or more. Words are dot separated.

**Q3. Where could a message be lost before reaching a queue, and how would you detect it?**
If no queue is bound (unroutable). Use `mandatory` with a returns callback or an alternate exchange, monitor unroutable counters, and declare topology on both sides. In this project, a missing returns callback is a known gap.

**Q4. Why use the default exchange for the retry republish?**
The consumer publishes to exchange `""` with the routing key equal to the queue name `cx.notifications.retry`, a direct way to put a message into a specific queue without a binding.

## H.3 Durability, persistent messages and acknowledgements

### What it is

Three separate things must all be set for a message to survive a broker restart: a **durable queue** (survives restart as a definition), a **persistent message** (`delivery_mode = 2`, written to disk), and a **durable exchange** (so bindings survive). Even then, persistence is not instant; a message in the broker's buffer may not yet be fsynced when the broker crashes, which is why **publisher confirms** exist (H.4).

**Consumer acknowledgements** tell the broker a message was handled. In manual mode the broker keeps the message **unacked** (invisible to others) until `basicAck` (done, remove), `basicNack` or `basicReject` (with or without requeue). If the channel closes without an ack, the broker **redelivers** the message with `redelivered = true`. Auto-ack removes the message on delivery, so a consumer crash loses it (at-most-once).

### How THIS PROJECT uses it

**Project-implemented behaviour, NOT EXECUTED against a broker.**

- Durable exchange and queues (see H.2).
- Publisher: `MessageDeliveryMode.PERSISTENT`, content type JSON, `messageId = eventId`, headers `X-Correlation-Id` and `tenantId` (`RabbitEventPublisher`).
- Consumer: `c.setAcknowledgeMode(AcknowledgeMode.MANUAL)` and `setDefaultRequeueRejected(false)` in `NotificationRabbitConfiguration`.
- Retry republish by the consumer: `deliveryMode(2)` (persistent), preserving content type and correlation id.
- Quorum queues are **not** used; the queues are classic durable queues. For production durability on a cluster, quorum queues are the recommended type; that was not configured.
- `appendonly`-like durability for RabbitMQ volumes: compose mounts `rabbitdata:/var/lib/rabbitmq`, so definitions and persistent messages survive container recreation.

The acknowledgement discipline in `NotificationMessageHandler.onMessage` (executed against a mocked `Channel` in `NotificationMessageHandlerTest`):

| Outcome | Action | Test |
|---|---|---|
| processed or duplicate | `basicAck` | `successIsAcked`, `duplicateIsAckedWithoutReprocessing` |
| unparseable body | `basicReject(tag, false)` so the broker dead-letters | `poisonMessageIsRejectedWithoutRequeueSoBrokerDeadLetters` |
| retryable failure, attempts left | republish to retry queue with persistent mode and a confirm, then `basicAck` | `failureWithAttemptsLeftRepublishesPersistentToRetryQueueThenAcks` |
| retry republish not confirmed | `basicNack(tag, false, true)` (requeue the original) | `unconfirmedRetryPublishRequeuesOriginalInsteadOfLosingIt` |
| attempts exhausted or non-retryable | persist DEAD state, then `basicReject(tag, false)` | `exhaustedAttemptsMarkDeadAndRejectToDlq` |
| ack fails with I/O error after success | not treated as a processing failure; redelivery is a safe no-op | `ackIoErrorIsNotTreatedAsProcessingFailureAndRedeliveryIsNoOp` |

The comment in the code is a good interview point: the `basicAck` is **outside** the try block, so that an I/O error on ack is not mistaken for a business failure and does not trigger a retry; the broker will redeliver and the idempotent processor treats it as a duplicate.

### Implemented versus not implemented

Not implemented: quorum queues or mirrored queues, queue length limits (`x-max-length`) and overflow policy, lazy queues configuration, message priorities, alternate exchange, and queue TTL policies. Without a max length a runaway producer can fill memory and disk until alarms block all publishers.

### Performance, security, mistakes, debugging

- Persistent messages cost disk I/O; batching confirms is the usual way to recover throughput.
- Mistake: marking messages persistent but the queue transient: they are lost on restart.
- Mistake: unbounded unacked messages because a consumer never acks (leak). Watch `messages_unacknowledged`.
- Mistake: requeueing a poison message forever with `basicNack(requeue=true)` (an infinite hot loop). This design avoids it by routing to retry or dead letter queues.
- Debugging: `rabbitmqctl list_queues name durable messages_ready messages_unacknowledged`, check `redelivered` flags, `rabbitmq-diagnostics check_running`.

### Interview Q&A

**Q1. List what is required for a message to survive a broker restart.**
Durable exchange, durable queue, persistent message, and ideally a publisher confirm so you know it was written; on a cluster, a replicated queue type such as a quorum queue.

**Q2. What happens to an unacked message when a consumer dies?**
The channel closes and the broker requeues the message (marked redelivered) to be delivered again, possibly to another consumer.

**Q3. Why manual acknowledgements here?**
To ack only after the database work is committed and to control retry behaviour (republish with an attempt counter), which auto-ack and requeue-based retries cannot give.

## H.4 Publisher confirms and returns

### What they are

By default `basic.publish` is fire-and-forget. With **publisher confirms** (`confirm.select`), the broker sends an ack after it has taken responsibility for the message (for persistent messages on durable queues, after writing it; for quorum queues, after a quorum), or a nack if it could not. This turns publishing into an at-least-once handshake. **Returns** report unroutable messages when `mandatory` is set. Confirms and returns are independent: an unroutable message is still confirmed once the broker has handled it.

### How THIS PROJECT uses it

**Project-implemented behaviour, NOT EXECUTED.** `RabbitEventPublisher.publish`:

```java
rabbit.invoke(ops -> {
    ops.send(DomainEvent.EXCHANGE, event.type(),
             MessageBuilder.withBody(body)...setDeliveryMode(PERSISTENT)...build(),
             new CorrelationData(event.eventId().toString()));
    ops.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);   // 5 000 ms
    return null;
});
```

`rabbit.invoke` runs the callback on a single dedicated channel, and `waitForConfirmsOrDie` blocks the relay until the broker acks, throwing on nack or timeout. A throw makes `OutboxRelay.fail(...)` set the row back to `PENDING` with exponential backoff. That is the key to "no event is lost between the database and the broker": the row is marked `SENT` only after the broker confirmed.

Consumer side: `NotificationMessageHandler.retryLater` uses `channel.confirmSelect()` and `channel.waitForConfirmsOrDie(confirmTimeoutMs)` (default 5 000 ms) before acking the original, so a retry message is never lost between ack and republish; if the confirm fails the original is requeued.

Gaps: no `ReturnsCallback` (see the H.2 observation), and publishing one message and waiting for its confirm serially is simple but slow (one round trip per event). Batch publishing with asynchronous confirm tracking via `CorrelationData` futures would raise throughput. The relay's batch size is 50 per second by default, which is modest, so the serial approach is acceptable at this scale; it is not measured.

Test evidence: `RabbitRedisDockerIT.eventsArePublishedPersistentWithConfirmsAndCorrelationHeader` would publish to a real RabbitMQ container and assert persistent delivery mode, the correlation header and the body. **Not run.**

### Interview Q&A

**Q1. Do publisher confirms guarantee the consumer processed the message?**
No. They guarantee only that the broker took responsibility. Processing is covered by consumer acknowledgements and idempotency.

**Q2. What happens in the relay if the broker never confirms?**
After 5 seconds `waitForConfirmsOrDie` throws, the relay records the error, increments attempts, sets the row to `PENDING` with backoff of `2 s * 2^(attempts-1)` capped at 300 s, and tries again later. After 8 attempts the row is `FAILED`.

**Q3. Mandatory flag versus publisher confirms?**
Different concerns: `mandatory` handles unroutable messages (returns), confirms handle broker acceptance. You usually want both, with handling for returns.

## H.5 Prefetch, concurrency and backpressure

### What it is

**Prefetch (QoS)** limits how many unacknowledged messages the broker sends to a consumer. Without a limit, the broker pushes the entire queue to the first consumer. A small prefetch gives fair dispatch and limits memory; a large prefetch improves throughput. **Concurrency** is the number of consumer threads and channels. Together they define the maximum number of messages in flight: `prefetch * consumers`.

**Backpressure** is the system's way of slowing producers when consumers cannot keep up: a queue growing, the broker's memory or disk alarm blocking connections, publisher confirms getting slower, or an explicit application limit.

### How THIS PROJECT configures it

**Project-implemented configuration (NOT EXECUTED):** in `notification-service/src/main/resources/application.yml`: `prefetch: 10`, `concurrency: 2`, `max-concurrency: 4`, applied to `SimpleMessageListenerContainer` (`setPrefetchCount`, `setConcurrentConsumers`, `setMaxConcurrentConsumers`). The container scales consumers between 2 and 4 based on load. So the maximum in-flight is 40 messages (4 consumers times 10 prefetch) per service instance.

Backpressure in this design is layered:

1. The queue buffers bursts (the broker absorbs).
2. The outbox buffers upstream: if the broker is down, rows stay `PENDING` in PostgreSQL and the relay's batch size of 50 per poll limits the rate (`cx.outbox.batch-size`, `poll-ms: 1000`).
3. The relay is single-threaded per node and waits for each confirm, a natural throttle.
4. There is no explicit queue length limit and no producer rate limit tied to queue depth.

Not implemented: `x-max-length` with `overflow = reject-publish` (which turns a full queue into nacks and so into outbox retries, a clean backpressure signal), autoscaling of consumers on queue depth, consumer-side rate limiting.

### Performance considerations

Throughput per consumer is about `prefetch / processing time`, bounded by CPU, the database and downstream channels. Processing here writes to PostgreSQL (`notif_*` tables) and simulated email and in-app channel senders, so database latency dominates. Prefetch 1 gives strict fairness and the lowest memory but one round trip per message; prefetch much larger than the processing rate causes long unacked backlogs and uneven distribution.

### Interview Q&A

**Q1. How would you choose a prefetch value?**
Start from processing time and network round trip: prefetch should cover the latency so a consumer never idles, but stay small enough that a slow message does not hold many others hostage. A common starting point is 10 to 50 for short tasks, 1 for long tasks. Measure `messages_unacknowledged` and consumer utilization.

**Q2. The queue depth keeps growing. What are your options?**
Scale consumers (raise `max-concurrency` or add instances), fix the slow dependency (database), reduce work per message, or shed or limit producers. Verify the cause first: consumers connected? retry storm? Runbook 3 in the performance guide gives the diagnosis steps.

**Q3. What is the danger of a huge prefetch with multiple instances?**
One instance can hoard messages while others idle, and if it dies all of them are redelivered at once.

## H.6 Retry, TTL queues, DLX and DLQ

### What they are

A **dead letter exchange (DLX)** receives messages that were rejected without requeue, that expired (TTL), that overflowed a queue length limit, or that hit a delivery limit (quorum queues). A **dead letter queue (DLQ)** is simply a queue bound to that exchange to hold them for inspection and re-drive.

RabbitMQ has no built-in per-message delayed retry, so a common pattern is a **TTL retry queue**: a queue with `x-message-ttl` and `x-dead-letter-exchange` pointing back to the work queue. A failed message is published there, waits for the TTL, expires, and is dead-lettered back to the work queue for another attempt. Broker-added `x-death` headers record how many times and why a message was dead-lettered.

![Figure: Retry and dead-letter timeline for a failing event](docs/diagrams/png/24-retry-dlq-timeline.png)

### How THIS PROJECT uses it

**Project-implemented behaviour.** The topology is in H.2. The decision logic is `RetryPolicy` (pure, no I/O, **executed** by `RetryPolicyTest`):

- `maxAttempts` (default 5) counts the attempt that just failed.
- `NonRetryableException` goes straight to dead letter (validation problems such as missing `eventId`, `tenantId` or a payload tenant that does not match the envelope; `NotificationProcessor.validate`).
- Delay: `base * multiplier^(attempt-1)` capped by `maxDelayMs`. With the default multiplier 1.0 the delay is fixed (5 000 ms from the queue's `x-message-ttl`). With a multiplier greater than 1, `perMessageDelay()` is true and the consumer sets a per-message `expiration` instead.

The flow in `NotificationMessageHandler` is covered by mocked-channel tests, including an end-to-end style `consumerFailureAndRecoveryViaRetryQueue`. Attempt counting uses the `x-attempt` header that the consumer sets, falling back to the broker's `x-death` count for the retry queue (`priorAttempts`, test `priorAttemptsFallsBackToXDeathCountOfRetryQueue`).

On the core side the outbox has its own retry (H.10): backoff `2 s * 2^(n-1)` capped at 300 s, maximum 8 attempts, then `FAILED`. Two retry layers exist: outbox to broker (H.10), and broker to consumer.

Observations that a careful reviewer will raise (reading the code, not run results):

1. **Per-message TTL head-of-line blocking.** RabbitMQ expires per-message TTL only when a message reaches the head of the queue. With a multiplier above 1 and messages of different delays in one retry queue, a short-delay message can be stuck behind a long one. The default (multiplier 1.0, fixed queue TTL) avoids this. Using separate retry queues per delay tier, or the delayed-message exchange plugin, avoids it.
2. **Retry loses the original routing key.** The retry message is dead-lettered to the default exchange with routing key `cx.notifications`, so it enters the work queue directly. That is fine for a single queue. With several consumer queues bound to the same exchange, a retry for one queue must not be broadcast to others, which this design respects.
3. **DLQ has no consumer.** Poison and exhausted messages wait for an operator. There is no re-drive tool; the performance guide says to use a shovel or republish after fixing the cause. That is a manual runbook step.
4. **DEAD state is persisted** (`notif_processed_event.status = 'DEAD'`) before reject, so a later redelivery of the same event id is a duplicate no-op even if someone manually re-drives it. That is also a trap: re-driving a dead event requires first resetting its state, otherwise the processor ignores it. Worth knowing for runbooks.

Test evidence: `NotificationRabbitDockerIT.poisonMessageEndsInDeadLetterQueue` and `eventPublishedToExchangeIsConsumedOnceEvenWhenPublishedTwice` would prove the broker-side behaviour. **Not run.**

### Conceptual learning example: tiered retry queues

```text
cx.retry.5s   (x-message-ttl=5000,   DLX="" , DLRK=cx.notifications)
cx.retry.30s  (x-message-ttl=30000,  DLX="" , DLRK=cx.notifications)
cx.retry.5m   (x-message-ttl=300000, DLX="" , DLRK=cx.notifications)
```

The consumer picks the queue by attempt number (attempt 1 to 5 s, 2 to 30 s, 3 to 5 m). Each queue has uniform TTL, so there is no head-of-line blocking. This is the standard robust alternative to the per-message `expiration` used by the project when the multiplier is above 1.

### Performance, security, mistakes, debugging

- A retry storm: a downstream outage makes every message fail, each cycles through the retry queue five times, multiplying load. Use jitter and a circuit breaker (consider pausing the listener container on a persistent downstream failure).
- DLQ messages may contain personal data (notification payloads include ticket titles and recipient ids). Treat the DLQ as sensitive, restrict access, and set retention.
- Mistake: DLX loop (the DLQ itself dead-lettering back to the work queue).
- Debugging: management UI "Get messages" with ack mode "reject requeue true" to peek; inspect `x-death`; `rabbitmqctl list_queues name messages_ready` for the three queues; correlate with `corr=` log field.

### Interview Q&A

**Q1. Explain how the retry queue brings a message back.**
The consumer publishes a copy to `cx.notifications.retry`, which has a TTL and a dead-letter exchange set to the default exchange with routing key `cx.notifications`. When the TTL expires the broker dead-letters the message through the default exchange into the work queue.

**Q2. Why republish and ack instead of `nack` with requeue?**
Requeue puts the message back immediately at the front, giving a hot retry loop with no delay and no attempt counter. Republish to a TTL queue gives a delay and carries an attempt header.

**Q3. What is the risk in "republish then ack"?**
A crash between the republish and the ack duplicates the message (original redelivered plus the copy). The design accepts at-least-once and relies on idempotency. The reverse order (ack then republish) could lose the message, so this order is correct. The code also waits for the republish confirm.

**Q4. How do you re-drive a DLQ?**
Fix the cause, reset the persisted DEAD state (or delete the `notif_processed_event` row) for each event, then shovel or republish messages to the work queue. Without resetting the state the processor skips them as duplicates.

## H.7 Idempotent consumers (`notif_processed_event`)

### What it is and why

Because delivery is at-least-once, a consumer will sometimes see the same message twice (redelivery after a lost ack, a relay re-publish after lease expiry, a manual re-drive). An **idempotent consumer** produces the same final state no matter how many times it processes a message. The standard technique is to record processed message ids in a table with a unique constraint, in the same transaction as the side effect.

### How THIS PROJECT does it

**Project-implemented behaviour (executed on H2 in notification-service tests; the broker path was not).**

Schema (`notification-service/src/main/resources/db/notification-migration/V1__notif_init.sql`):

```sql
create table notif_processed_event (
    event_id uuid not null, tenant_id uuid not null, event_type varchar(120) not null,
    status varchar(16) not null, attempts integer not null default 0, last_error varchar(1000),
    first_seen_at timestamp with time zone not null, completed_at timestamp with time zone,
    constraint pk_notif_processed_event primary key (event_id));
create table notif_notification (... constraint uq_notif_notification_event_user unique (event_id, user_id));
```

`NotificationStore.claim(event)` runs in its own transaction:

1. `select status from notif_processed_event where event_id = ?`.
2. If found and `DONE` or `DEAD`: `DUPLICATE` (no-op, ack).
3. If found and `PROCESSING`: increment `attempts`, return `RESUME` (a retry of an unfinished event).
4. If absent: insert with status `PROCESSING`; a `DuplicateKeyException` from a concurrent consumer inserting first is treated as `DUPLICATE`.

`NotificationProcessor.process` then: validates, claims, resolves recipients (dropping recipients that are not users of the event's tenant), renders, **idempotently creates notification and delivery rows** (unique `(event_id, user_id)`), sends each channel that is not already `SENT`, marks failures, throws if any channel failed (so the message retries), and marks the event `DONE` only when all deliveries are `SENT`. A retry therefore re-sends only what failed (the class Javadoc says "effectively-once for the simulated channels"). Metrics `cx_notifications_events_total{result}` count `processed`, `duplicate`, `no_recipients`, `failed`, `dead`, `recipient_rejected`.

Quality notes:

- **The primary key is the arbiter.** The check-then-insert has a race, but the insert's primary key makes the loser fail safely.
- **The claim and the side effects are separate short transactions**, not one transaction. That is why the status machine (`PROCESSING`, `DONE`, `DEAD`) and the per-delivery rows exist: crash recovery is by resumption, not by atomic rollback. Sending an email is an external side effect that no database transaction can roll back, so "effectively once" requires the downstream provider to deduplicate too (a provider idempotency key). The simulated senders here do not demonstrate that.
- **Cleanup gap**: `notif_processed_event` grows forever. The performance guide lists adding a purge older than the max redelivery window as a prevention step; it is not implemented.
- Core-side idempotency is separate: `idempotency_key` for `POST /tickets` (replay, same-body hash, 409 on different body, purged after 24 h). **Comment creation is not idempotent** (a client retry creates a second comment), as the performance guide states. This is a good "known limitation" answer.

Test evidence (executed): `NotificationMessageHandlerTest.duplicateIsAckedWithoutReprocessing`; `NotificationProcessorTest` (referenced by the performance guide as testing redelivery explicitly); `OutboxTest.ticketCreationAndIdempotencyReplayEmitExactlyOneCreatedEvent` (core, H2: a replayed idempotent create emits exactly one event).

### Alternatives

Natural idempotency (an upsert keyed by business id), version checks, dedup by a message-id store with TTL in Redis (`SET id NX EX`; fast but not transactional with the database), or exactly-once-style transactions with Kafka (limited to Kafka-to-Kafka).

### Interview Q&A

**Q1. How do you make a consumer idempotent?**
Record the message id in a table with a unique constraint and check it before the side effect, ideally in the same transaction. Alternatively design the operation to be naturally idempotent (upsert, set-to-value rather than increment).

**Q2. Why is "check then act" with a unique index not racy?**
Two consumers may both pass the check, but only one insert succeeds; the other gets a duplicate key error and treats the message as a duplicate. The database serializes the decision.

**Q3. What do you do about the side effect that cannot be rolled back, like sending email?**
Make the downstream call idempotent (pass the event id as an idempotency key to the provider), record per-delivery state before and after, and accept that a crash between "sent" and "recorded" can produce a duplicate email. State this plainly.

**Q4. When can the processed-event table be purged?**
After the maximum time a duplicate could arrive (the broker retention, retry horizon and any manual re-drive window). The project has not defined that horizon.

## H.8 Delivery guarantees: at-most-once, at-least-once, exactly-once

### The three semantics

| Semantics | How | Failure result | Use when |
|---|---|---|---|
| At-most-once | auto-ack or fire-and-forget publish | message loss possible | metrics, non-critical |
| At-least-once | persist plus confirm plus manual ack plus retry | duplicates possible | most business events |
| Exactly-once | not achievable end to end across independent systems | | |

### The exactly-once myth

Over an unreliable network, the sender cannot know whether a lost acknowledgement means the message was lost or only the ack. Therefore it must either risk loss (do not retry) or risk duplication (retry). "Exactly-once" in practice means **at-least-once delivery plus idempotent processing**, which yields exactly-once *effects*. Some systems (Kafka transactions, Flink) offer exactly-once within their own boundaries, but the moment a side effect leaves that boundary (an email, an HTTP call, a write to another database) you are back to idempotency.

### How THIS PROJECT stands

**Project-implemented design:** at-least-once end to end. Stages: atomic outbox insert (no loss at the source); relay with lease and retry (no loss between database and broker, duplicates possible); persistent messages with confirms; manual acks and retry (no loss at the consumer); idempotent consumer keyed by `eventId` (duplicates neutralized). The `DomainEvent` Javadoc states the contract: "Delivery is at-least-once; consumers MUST de-duplicate on eventId."

Where duplicates can arise: relay crashes after publish and before marking `SENT`; the 60 s lease expires while a slow publish is still running; the broker redelivers an unacked message; the consumer republishes to the retry queue and crashes before the ack; an operator re-drives the DLQ.

Where loss can still arise (honest list): unroutable events before the queue exists (H.2); `FAILED` outbox rows after 8 attempts (kept, but need manual re-drive: there is no replay endpoint); DLQ messages awaiting manual action; purge of `SENT` rows (intended); and in the `local` profile `LocalEventPublisher` drops events when no listener is registered (`"dropped after commit"` debug log) while still marking them `SENT`, so a missing consumer is invisible locally (the performance guide says to confirm a listener exists).

### Interview Q&A

**Q1. Can you build exactly-once delivery?**
Not in general. You build at-least-once delivery and make processing idempotent, giving exactly-once effects.

**Q2. Which component in your system provides each guarantee?**
No loss at source: transactional outbox. No loss to the broker: confirms plus retry. No loss to the consumer: manual ack plus retry and DLQ. No duplicate effects: processed-event table and unique keys.

**Q3. What is the worst failure mode you know of in your own system?**
Be specific: events published while the queue has never been declared are returned and, without a returns callback, dropped with the row marked sent; the fix is to declare topology on the publisher side or fail on returns.

## H.9 Ordering

### What it is

Message ordering guarantees are weak in practice. A single queue with a single consumer preserves publish order per channel. Ordering breaks when: multiple consumers process concurrently (completion order differs), messages are requeued or retried (they go to the back or through a retry queue), several publishers or channels interleave, or `SKIP LOCKED`-style relays pick rows in different batches.

### How THIS PROJECT behaves

**Project-implemented behaviour.** No global or per-ticket ordering is guaranteed:

- The relay selects due rows `order by created_at, id` with a batch limit, but multiple relays claim concurrently, and retried rows come back later, so a `ticket.assigned` event can overtake an earlier failed `ticket.created`.
- The consumer runs 2 to 4 concurrent consumers with prefetch 10, so processing order across messages is not sequential.
- Retries go through the retry queue and rejoin the end of the work queue.

Why it is acceptable: each event is a self-contained notification with the data it needs in its payload (flat map with ticket id, number, title, recipients), the notification processor does not depend on earlier events, and notifications are independent facts. The envelope has `occurredAt` so a consumer that needs ordering can sort or discard stale events. If per-ticket ordering ever mattered, the options are: a consistent-hash exchange or per-ticket queue sharding with single consumer per shard, version numbers in events with a "drop if older than seen" rule, or Kafka partitions keyed by ticket id. **None is implemented.**

### Interview Q&A

**Q1. Does RabbitMQ guarantee ordering?**
Only per queue, per channel, for a single consumer without requeue. With competing consumers, retries or dead lettering, order is not guaranteed.

**Q2. How would you guarantee per-entity ordering?**
Route by entity key to a specific queue and single consumer (consistent hash exchange), or include a monotonically increasing version and make the consumer reject or reorder stale events.

**Q3. Is ordering a problem for notifications?**
Rarely: an out-of-order "assigned" before "created" is still two valid notifications. It would be a problem for state replication (applying events to build a read model), where you must use versions.

## H.10 The transactional outbox (the project's implementation)

### The dual-write problem

A service that updates its database and then publishes a message has two writes to two systems with no shared transaction. Failures between them cause either a business change with no event (lost notification) or an event with no business change (phantom event). Wrapping the publish inside the database transaction does not help: the broker is not part of it, and a rollback after publishing cannot unsend the message.

### The pattern

Write the event as a row in an **outbox table in the same database transaction** as the business change. A separate **relay** reads the table and publishes to the broker, marks rows sent, and retries on failure. Atomicity of the business change and the event is guaranteed by the database; delivery is at-least-once by the relay. Variants: polling publisher (what this project does) and log-based **change data capture** (Debezium reading the WAL), which avoids polling at the price of more infrastructure.

![Figure: Transactional outbox flow](docs/diagrams/png/11-transactional-outbox.png)

### How THIS PROJECT implements it

**Project-implemented behaviour (executed with `LocalEventPublisher` on H2; the Rabbit publisher not).**

**Write side.** `OutboxService.publish(type, payload)` is `@Transactional(propagation = MANDATORY)`: it throws `IllegalTransactionStateException` if no transaction is active, which makes it impossible to write an event outside the business transaction. It builds a `DomainEvent` (new UUID, schema version 1, tenant from `TenantContext.require()`, correlation id from MDC, flat string payload with null values dropped), serializes it to JSON, and saves an `OutboxEvent` row with status `PENDING` and `next_attempt_at = now`. It increments `cx.outbox.enqueued{type}`. Table `outbox_event` (V14) holds `status`, `attempts`, `next_attempt_at`, `locked_by`, `locked_until`, `last_error`, `created_at`, `sent_at`, with indexes `ix_outbox_poll (status, next_attempt_at)` and `ix_outbox_tenant (tenant_id, created_at)`. The payload column is `varchar(20000)`.

Tests (executed on H2, `OutboxTest`): `publishRequiresAnActiveTransaction`; `eventIsAtomicWithTheBusinessTransaction` (rollback leaves nothing, commit leaves one `PENDING` row); `failedBusinessOperationLeavesNoEvent` (an invalid create returns 400 and leaves no ticket and no event); `ticketLifecycleEmitsTheContractEventTypesWithTenantAndCorrelation`; `ticketCreationAndIdempotencyReplayEmitExactlyOneCreatedEvent`.

**Relay.** `OutboxRelay.poll` is `@Scheduled(fixedDelayString = "${cx.outbox.poll-ms:1000}", initialDelay = 2000)`; `relayOnce()` does:

1. `findDueIds(now, PageRequest.of(0, batchSize=50))`: ids of `PENDING` rows whose `next_attempt_at <= now`, or `PROCESSING` rows whose lease expired, oldest first.
2. For each id, in its own transaction run `claim(id, nodeId, now + lockSeconds(60), now)`: a compare-and-set `UPDATE` that sets `PROCESSING`, `locked_by`, `locked_until` and increments `attempts`, only if the row is still due. Updated rows 1 means this relay owns it. (H.11 for the lease and why it is not `SKIP LOCKED`.)
3. Deserialize the payload; an unreadable payload becomes `FAILED` immediately (`unreadable payload`).
4. `publisher.publish(event)` **outside any database transaction**.
5. On success: in a small transaction set `SENT`, `sent_at`, clear lock fields and error; increment `cx.outbox.published{type}`.
6. On failure: `fail(...)` records `last_error` (trimmed to 480 chars), clears the lock, and either sets `FAILED` (permanent or attempts at or above `max-attempts`, default 8; metric `cx.outbox.failed`; ERROR log) or sets `PENDING` with `next_attempt_at = now + min(300, 2 * 2^(attempts-1))` seconds (metric `cx.outbox.retried`).

`OutboxPurgeJob` deletes `SENT` rows older than 7 days hourly in bounded batches (500 per batch, at most 20 batches per run) and keeps `FAILED` rows. `OutboxPurgeTest` covers it (H2).

Executed tests for the relay (`OutboxTest`): `relayDeliversOnceAndMarksSent`; `failuresAreRetriedWithBackoffAndEventuallyDeadLettered` (attempt 1 failure leaves `PENDING`, `attempts = 1`, error recorded, not due during backoff, delivered on attempt 2 after the consumer recovers, and `FAILED` at attempt 3 with the test's `max-attempts=3`); `exactlyOneRelayClaimsARow` (concurrent claims, exactly one winner); `expiredLeaseIsReclaimedAfterACrashedRelay` (a valid lease hands off, an expired lease is re-claimed and delivered).

**Dispatch.** `EventPublisher` has two implementations: `LocalEventPublisher` (`@Profile("!docker")`: calls every `EventListener` bean, reports a listener failure after all listeners ran so the event is retried, and drops the event with only a debug log if there are none) and `RabbitEventPublisher` (`@Profile("docker")`, H.4). In the local profile the notification module is embedded in core-service and receives events through `InProcessEventListener`.

Monitoring: `cx_outbox_enqueued_total`, `cx_outbox_published_total`, `cx_outbox_retried_total`, `cx_outbox_failed_total` by type, and SQL: `SELECT status, count(*), min(created_at) FROM outbox_event GROUP BY status;` (**To-run on PostgreSQL**; the same table exists on H2). The relay lag is `now - min(created_at) where status = 'PENDING'`.

### Implemented versus not implemented

Not implemented: Debezium/CDC, a replay endpoint for `FAILED` rows (the guide's manual SQL: set `status='PENDING', attempts=0, next_attempt_at=now()` for specific ids), partitioning of the outbox table, per-tenant fairness in the relay (one noisy tenant's events can fill the batch of 50), RLS on `outbox_event` (it is deliberately outside RLS because the relay claims across tenants), a metric for oldest pending age, the relay publishing batches with asynchronous confirms.

### Performance, security, mistakes, debugging

- The poll query `ix_outbox_poll (status, next_attempt_at)` serves `PENDING`; the `PROCESSING` expired-lease branch of the `OR` may not use it well, and the `order by created_at, id` needs a sort. **To-run command** to check on PostgreSQL: `EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM outbox_event WHERE status = 'PENDING' AND next_attempt_at <= now() ORDER BY created_at, id LIMIT 50;`. The performance guide says to expect `ix_outbox_poll` and to watch for a `Sort` and for table growth. Outbox tables churn, so autovacuum matters (`n_dead_tup`).
- Cost: every event is a database insert, a poll, an update to `PROCESSING`, an update to `SENT`: about four writes per event. Acceptable for notification volumes, not for firehose streams.
- Security: the payload is stored as text in PostgreSQL and may contain recipient ids and titles; apply the same access control as other tenant data, and do not log payloads. The relay has cross-tenant access by design: restrict who can run it and which role it uses.
- Mistakes: publishing inside the transaction; forgetting `MANDATORY`; unbounded purge deleting millions of rows in one transaction (the project batches); not indexing `status`; relays on several nodes without a claim.
- Debugging runbook (performance guide 3b): `cx_outbox_enqueued_total` rising while `published` is flat and `retried` rising means the relay cannot publish; look for log lines `Outbox event ... moved to FAILED` and `Outbox poll failed`.

### Alternatives

CDC with Debezium (no polling, lower latency, operational weight), listen/notify to wake the relay (reduces polling delay), publish-after-commit hooks (loses events on a crash between commit and publish), two-phase commit (rarely supported, blocking), event sourcing (the event log is the source of truth).

### Interview Q&A

**Q1. Why is the outbox needed, and what exactly does it guarantee?**
To avoid the dual-write problem. It guarantees that the business change and the intent to publish commit atomically, and that the relay will keep trying until the broker confirms. It does not guarantee exactly-once delivery: consumers must deduplicate.

**Q2. Why `Propagation.MANDATORY` on `publish`?**
It turns a design rule into a compile-time-like runtime guard: a caller cannot emit an event without an enclosing transaction, so the event always commits or rolls back with the business change. `OutboxTest.publishRequiresAnActiveTransaction` proves it.

**Q3. What happens if the relay crashes after publishing but before marking the row `SENT`?**
The row stays `PROCESSING` with a lease; when the lease (60 s) expires another relay re-claims it and publishes again. That is a duplicate by design; the consumer's processed-event table makes it harmless. A test (`expiredLeaseIsReclaimedAfterACrashedRelay`) covers the re-claim.

**Q4. Polling publisher versus CDC?**
Polling is simple, portable (it runs on H2 and PostgreSQL here), and fine at modest volume but adds latency up to the poll interval (1 s) and database load. CDC streams from the write-ahead log with low latency and no polling load, but needs Debezium, Kafka Connect or similar, and replication slots that must be monitored.

**Q5. How do you stop the outbox table growing?**
Purge delivered rows in bounded batches (the project does: 7 day retention), keep failed rows for inspection, and monitor table size and dead tuples. Partitioning by time allows dropping partitions cheaply.

## H.11 The relay, leases and why this is not SKIP LOCKED

This subsection exists because interviewers often ask "how do multiple relay instances avoid publishing the same row?".

**Project-implemented design: compare-and-set claim with a lease.**

```sql
update outbox_event
set status = 'PROCESSING', locked_by = :node, locked_until = :until, attempts = attempts + 1
where id = :id and ((status = 'PENDING' and next_attempt_at <= :now)
                 or (status = 'PROCESSING' and locked_until < :now));
```

![Figure: Outbox row state machine with claim, lease, retry and failure](docs/diagrams/png/25-outbox-claim-lease.png)

- Exactly one concurrent updater sees `1` row updated (a row-level lock serializes them; after the first commits, the second re-evaluates the `WHERE` under READ COMMITTED and sees `PROCESSING` with a future lease, so it updates zero rows).
- A crashed relay leaves `PROCESSING` and a lease timestamp; after `locked_until` passes anyone can claim it. No database lock is held while publishing, so a slow broker does not hold a connection.
- The lease length (60 s, `cx.outbox.lock-seconds`) must exceed the publish timeout (5 s confirm) with margin; if a publish takes longer than the lease, another relay may re-claim and publish a duplicate, which is again tolerated.

**Compared with the alternative**, `SELECT ... FOR UPDATE SKIP LOCKED` (conceptual example in Part F, F.7): claims many rows in one statement and is more efficient under heavy competition, but holds row locks until the claiming transaction commits and is PostgreSQL-oriented. The project's choice trades some efficiency (read ids, then claim each row separately: 1 read plus N updates) for portability and for recovery after crash by lease.

Failure modes of the lease approach: clock skew between relay nodes (the `now` used in the claim comes from the relay's JVM clock, not the database), so nodes with skewed clocks may re-claim early; using `current_timestamp` in the SQL (database time) would remove that. This is a subtle point worth raising proactively.

### Interview Q&A

**Q1. How do two relays avoid double-publishing?**
The CAS update returns 1 for exactly one of them. The other skips the row. Duplicates can still happen only through lease expiry or a crash after publish.

**Q2. What would you change to scale the relay?**
Claim in batches with `SKIP LOCKED` on PostgreSQL, publish asynchronously with confirms in batches, shard by tenant or id range, or move to CDC.

**Q3. Why is it important to use database time for leases?**
Because JVM clocks on different nodes disagree; a lease compared against the local clock can expire early or late.

## H.12 Eventual consistency and sagas

### What they are

**Eventual consistency**: if no new updates occur, all replicas or components eventually converge to the same state. It is the price of asynchronous decoupling: after a ticket is created, the ticket row exists immediately but the notification appears a moment later.

A **saga** is a sequence of local transactions across services, each publishing an event or command that triggers the next, with **compensating actions** if a step fails (instead of a distributed rollback). Two styles: **choreography** (services react to each other's events) and **orchestration** (a coordinator tells each service what to do).

### How THIS PROJECT relates

**Project-implemented behaviour: eventual consistency yes, sagas no.**

- Core to notification is eventually consistent: the outbox, relay and consumer make the notification appear after the ticket commit (about the relay poll interval of 1 s plus processing time locally; real end-to-end latency with RabbitMQ was **not measured**, per the performance guide's appendix).
- The frontend reads notifications by polling every 30 s per tab (performance guide, runbook 6), another layer of staleness.
- PostgreSQL to MongoDB (KB snapshots) is eventually consistent in the failure case only (orphan snapshot overwritten on the next change).
- The cache is eventually consistent with the database up to its TTL.

No multi-service saga exists. Core and notification are not steps of one business transaction: notification is a one-way consequence of core events, with no compensation needed, because a notification cannot undo a ticket. What looks saga-like but is not: the ticket status state machine and the AI suggestion approval flow (`PENDING_REVIEW` to approved or rejected through a compare-and-set update) are **local state machines in one database**, and the workflow engine runs actions inside the core transaction.

**Conceptual learning example: a ticket purchase-style saga adapted to this domain (not implemented).** Suppose "escalate ticket to a vendor" required three services:

1. Core: set ticket `ON_HOLD`, emit `ticket.vendor.requested` through the outbox.
2. Vendor-integration service: create the vendor case. On success emit `vendor.case.created`; on failure emit `vendor.case.failed`.
3. Core consumes the failure and compensates: set the ticket back to `OPEN`, add an activity entry, notify the assignee.

Needed pieces: idempotent handlers, a correlation id for the saga instance (the project has `correlationId` on every event), timeouts for a missing reply (a scheduled job scanning `ON_HOLD` tickets older than N minutes, like the SLA job), and compensation that is itself idempotent and retryable.

### Orchestration versus choreography

| Aspect | Choreography | Orchestration |
|---|---|---|
| Coupling | services know events | services know the orchestrator |
| Visibility | hard to see the whole flow | one place describes the flow |
| Failure handling | spread over services | centralized compensation |
| Risk | cyclic event chains | orchestrator as a single point of change |

### Interview Q&A

**Q1. Where is your system eventually consistent and how do users experience it?**
Notifications lag the ticket by about a second plus polling; dashboards lag writes by up to 60 s through the cache; KB history is consistent except in a narrow failure case. Core ticket data itself is strongly consistent in one PostgreSQL database.

**Q2. Do you use sagas?**
No. There is one transactional database for the domain and one-way events to a notification service, so there is nothing to compensate. I can describe how I would design one: outbox events, idempotent handlers, correlation id, timeouts and compensations.

**Q3. Why not use distributed transactions (two-phase commit) between PostgreSQL and the broker?**
Poor availability (blocking on coordinator failure), limited broker support, and operational complexity. The outbox achieves the needed atomicity with a single database transaction.

**Q4. How do you handle a user who reads their notifications before they exist?**
Design the UI to treat the ticket response as truth and notifications as asynchronous hints; use polling or push, and make no business decision depend on notification arrival.

## H.13 CAP, PACELC and consistency models

### What they are

**CAP theorem**: during a network **partition**, a distributed data store must choose between **consistency** (every read sees the latest write, linearizability) and **availability** (every request gets a non-error response). Outside a partition you can have both. **PACELC** extends it: if Partition then Availability or Consistency; Else, trade Latency against Consistency.

Consistency models, strongest to weakest, used in conversation: **linearizable** (as if one copy), **sequential**, **causal**, **read-your-writes**, **monotonic reads**, **eventual**.

### Where the components sit (be careful, this is configuration dependent)

| Component | Typical stance | Note for this project |
|---|---|---|
| PostgreSQL single node | CP-like (no partition inside one node) | one node in compose; no replicas, so no read-replica lag |
| PostgreSQL with async replicas | writes consistent on primary, replicas eventually consistent | not deployed here |
| Redis single node | strongly consistent on one node; replicated Redis is asynchronous and can lose acknowledged writes on failover | cache, so staleness tolerated |
| RabbitMQ classic queues in a cluster | availability depends on queue's node; quorum queues are CP (Raft) | classic queues, single node |
| MongoDB replica set | primary reads are consistent; secondary reads can be stale; write concern decides durability | standalone in compose |
| Caffeine | per node, no coordination | local profile only |

The honest summary for this project: everything runs as a **single node in development**, so partition tradeoffs are not exercised at all. The design is *tolerant*: caches fail open, notifications are asynchronous, and the system of record is one PostgreSQL.

### How the design makes consistency choices

- Strong where it matters: ticket state, tenant isolation, idempotency keys, the AI approval compare-and-set, ticket numbers (unique, gaps allowed).
- Eventual where acceptable: cache, notifications, KB version history, dashboards.
- Read-your-writes: after `POST /tickets` the client can `GET` it from the same primary database, and the cache for that tenant's analytics is evicted after commit.

### Interview Q&A

**Q1. Is PostgreSQL CP or AP?**
The label is loose. A single primary gives linearizable writes; with synchronous replication you can keep consistency at the cost of availability when the replica is unreachable; with asynchronous replication a failover can lose acknowledged writes. State your configuration before the label.

**Q2. What is the difference between consistency in ACID and in CAP?**
ACID consistency means a transaction preserves invariants (constraints). CAP consistency means linearizability across replicas.

**Q3. What anomalies can a user see with eventual consistency, and how do you mitigate?**
Stale reads, reading their own write missing, time going backwards across replicas. Mitigate by read-your-writes (route to the primary after a write, session tokens), monotonic reads (sticky sessions), and versions.

## H.14 Monitoring, recovery and runbooks

### What to monitor

| Layer | Signal | Where in this project |
|---|---|---|
| Outbox | `cx_outbox_enqueued_total`, `published`, `retried`, `failed` per type; pending age | core-service `/actuator/prometheus`; SQL on `outbox_event` |
| Broker queues | `rabbitmq_queue_messages_ready`, `rabbitmq_queue_messages_unacked`, consumers, DLQ depth | RabbitMQ Prometheus plugin on `:15692` (listed in the performance guide; **not run**) |
| Consumer | `cx_notifications_events_total{result}` (`processed`, `duplicate`, `no_recipients`, `failed`, `dead`) | notification-service `:8082` |
| Pool and DB | Hikari metrics, `pg_stat_activity` | F.10 |
| Health | `management.health.rabbit.enabled` true in docker for notification-service; off locally | `application-docker.yml` |
| Logs | `corr=` correlation id in every line, in headers (`X-Correlation-Id`), in `audit_event.correlation_id` and in `DomainEvent.correlationId` | all services |

Suggested alerts (not configured in the repository beyond dashboards under `infra/grafana`): `DLQ depth > 0`, `messages_ready` rising for 10 minutes, `increase(cx_outbox_failed_total[15m]) > 0`, `rate(cx_outbox_published_total)` far above `rate(cx_notifications_events_total{result="processed"})`, consumers equal to zero.

### Recovery procedures (from the performance guide, Runbooks 3, 3b and 5; all NOT EXECUTED)

**Backlog growing** (check `consumers` first):

```bash
rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
rabbitmqctl list_consumers
curl -s localhost:8082/actuator/prometheus | grep cx_notifications_events_total
```

```sql
SELECT status, count(*), min(created_at) FROM outbox_event GROUP BY status;
SELECT event_type, attempts, left(last_error,100) FROM outbox_event WHERE status IN ('PENDING','FAILED') ORDER BY created_at LIMIT 20;
```

**Re-drive failed outbox rows** after fixing the cause (manual; there is no endpoint): `UPDATE outbox_event SET status='PENDING', attempts=0, next_attempt_at=now() WHERE id IN (...)`.

**Duplicate notification complaint**: `SELECT event_id, status, attempts, last_error FROM notif_processed_event WHERE event_id = '<id>';` and `SELECT count(*) FROM notif_notification WHERE event_id = '<id>' GROUP BY user_id HAVING count(*) > 1;` (must be empty, because of the unique key).

**Broker restart**: durable queues and persistent messages return; consumers reconnect (Spring AMQP recovers connections automatically); the relay's confirms time out and retry during the outage and rows stay `PENDING`.

**Poison message**: it ends in `cx.notifications.dlq` with DEAD state persisted; inspect via management UI, fix, reset DEAD state, re-drive.

### Troubleshooting exercise 1 (notifications stop arriving after an outage)

Symptoms: users stop receiving notifications; `cx_outbox_published_total` is flat; `cx_outbox_retried_total` rises. Walk-through: (1) `SELECT status, count(*) FROM outbox_event GROUP BY status;` shows many `PENDING` with `attempts` growing. (2) Core logs show `Outbox poll failed` or confirm timeouts: the broker is unreachable or blocked by an alarm (`rabbitmqctl status` shows memory or disk alarm, publishers blocked). (3) After the broker is back, backoff delays up to 300 s explain slow recovery; rows at 8 attempts are `FAILED` and need manual re-drive. (4) Check the consumer is connected (`consumers` > 0).

### Troubleshooting exercise 2 (event published but never delivered, outbox says `SENT`)

Symptoms: outbox row `SENT`, no notification, `notif_processed_event` has no row, queue empty. Hypothesis from H.2: the event was unroutable (queue not bound yet, or a new event type with no binding such as `kb.published`). Checks: `rabbitmqctl list_bindings | grep cx.events`; the application log of core for "Returned message" lines; in the management UI the exchange's message-rate "unroutable" chart. Fix: add the missing binding (and a returns callback so this fails loudly), then re-publish from the outbox (reset the row to `PENDING`; the consumer is idempotent).

### Troubleshooting exercise 3 (duplicate notification despite idempotency)

Two rows for the same user and event type appear. Reason A: they belong to different `event_id`s (two real events, for example two comments; comment creation is not idempotent and a client retry makes two comments). Reason B: different channels (`IN_APP` and `EMAIL`) are two deliveries by design. Reason C: a true duplicate would violate `uq_notif_notification_event_user`, so check whether someone bypassed the store. Query `notif_notification` grouped by `event_id`.

### Troubleshooting exercise 4 (consumer keeps failing one event)

`cx_notifications_events_total{result="failed"}` climbs and the same event id appears in logs every 5 s, five times, then `result="dead"` once. Meaning: a retryable failure (an email sender throwing) exhausted `max-attempts: 5` with fixed 5 s delay and was dead-lettered. Decide whether the failure is transient (fix the sender, reset state, re-drive) or permanent (validation: should have been a `NonRetryableException`).

### Interview Q&A

**Q1. What are the first three things you check when notifications are delayed?**
Whether consumers are connected, whether the outbox has pending or failed rows (the problem might be before the broker), and queue depth and unacked counts with the consumer metrics.

**Q2. How do you recover from a DLQ full of messages?**
Understand the cause from `last_error`, fix it, reset the persisted DEAD state, shovel messages back to the work queue, and watch `duplicate` and `processed` counters while it drains.

**Q3. Which metrics prove the pipeline is healthy end to end?**
Outbox published rate approximately equal to consumer processed rate, DLQ depth zero, `messages_unacked` below prefetch times consumers, and outbox pending age below a few seconds. End-to-end latency from outbox insert to notification row is **not instrumented** today.

## H.15 Comparison tables and alternatives

| Concern | RabbitMQ (project docker profile) | Kafka | SQS | In-JVM events (project local profile) |
|---|---|---|---|---|
| Model | broker with exchanges, queues | distributed log | managed queue | method calls |
| Ordering | per queue, weak with competing consumers | per partition | FIFO queues optional | call order |
| Replay | no (messages removed on ack) | yes | no | no |
| Retry and DLQ | TTL plus DLX pattern (built by you) | consumer offsets, retry topics | redrive policy | relay retry only |
| Routing | rich (topic, headers) | topics and keys | per queue | none |
| Operations | one broker, easy | cluster, heavier | none (managed) | none |
| Project status | written, not run | not used | not used | executed |

| Delivery pattern | Loss | Duplicates | Used here |
|---|---|---|---|
| Fire and forget, auto-ack | possible | no | no |
| Persist plus confirm plus manual ack | no | possible | yes |
| Outbox plus idempotent consumer | no | harmless | yes |
| Two-phase commit | no | no | no |

| Retry style | Pros | Cons | Used here |
|---|---|---|---|
| Requeue on nack | simplest | hot loop, no delay | no |
| TTL retry queue, fixed delay | delay, simple | head-of-line only with per-message TTL | yes (default multiplier 1.0) |
| Per-message expiration (exponential) | adaptive delay | head-of-line blocking | yes when multiplier above 1 |
| Tiered retry queues | uniform TTL per queue | more queues | no (conceptual) |
| Delayed-message plugin | exact delays | plugin dependency | no |

## H.16 Runnable examples

### Conceptual learning example 1: observe redelivery with the management CLI

```bash
# 1. publish a persistent message
rabbitmqadmin publish exchange=amq.default routing_key=demo payload=hello properties='{"delivery_mode":2}'
# 2. read it without acking (requeue = true): redelivered flag appears on the second get
rabbitmqadmin get queue=demo ackmode=reject_requeue_true count=1
rabbitmqadmin get queue=demo ackmode=ack_requeue_false count=1
```

### Conceptual learning example 2: a minimal idempotent consumer in SQL

```sql
create table processed_message (message_id uuid primary key, processed_at timestamptz not null default now());
-- inside the consumer transaction:
insert into processed_message (message_id) values (:id) on conflict do nothing;
-- if the insert affected 0 rows, the message is a duplicate: ack and stop. Otherwise do the side effect in the SAME transaction.
```

### Project-implemented behaviour you can run today (no Docker)

On H2 with profile `local`, run the executed tests that cover this part (commands as in `RUN-AND-TEST-GUIDE.md`; set the JDK by full path, never export `JAVA_HOME` globally in shared shells):

```bash
mvn -pl core-service -am test -Dtest='OutboxTest,OutboxPurgeTest,EmbeddedNotificationsTest,CacheServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl notification-service -am test -Dtest='NotificationMessageHandlerTest,RetryPolicyTest,NotificationProcessorTest' -Dsurefire.failIfNoSpecifiedTests=false
```

These exercise the outbox atomicity, retry and lease logic, the retry and dead-letter decisions against a mocked channel, and the idempotent processor. They do **not** touch a broker. The Docker tests, to be run on a Docker host, are `mvn -Pdocker-tests ...` for `RabbitRedisDockerIT` and `NotificationRabbitDockerIT`.

## H.17 Why RabbitMQ and not something else (selection summary)

- Needed: reliable one-to-one delivery of moderate-volume events with per-message retry and dead lettering, and routing by event type for future consumers.
- RabbitMQ gives exchange routing, per-message acks, TTL and DLX natively and runs as one container.
- Kafka would add replay and partitioned ordering that this system does not need, at higher operational cost; SQS would tie the project to a cloud; a database-only queue (the outbox itself can act as the queue) would be simplest but loses fan-out and broker-side retry features.
- Honest trade-off: the project pays for two retry layers (outbox and consumer) and a more complex topology than a simple "outbox plus in-process listener" would need. In the local profile it uses exactly that simpler design.

## H.18 Quick interview recap for Part H

- Say the guarantee: at-least-once end to end, idempotent consumers give exactly-once effects. Name the table (`notif_processed_event`) and the primary key as the arbiter.
- Say the pattern: `OutboxService.publish` is `MANDATORY`; relay claims with CAS plus 60 s lease; publish outside transactions; confirm 5 s; backoff 2 s doubling to 300 s; 8 attempts then `FAILED`; purge after 7 days.
- Say the topology: `cx.events` topic to `cx.notifications` with `ticket.#` and `ai.#`; `retry` queue with 5 s TTL back via the default exchange; `cx.events.dlx` fanout to the DLQ; manual acks, prefetch 10, concurrency 2 to 4.
- Say the limits first: nothing ran against a broker; unroutable-before-queue-exists gap (no returns callback); no ordering guarantee; no sagas; no quorum queues; no max length; DLQ is manual; end-to-end latency unmeasured.
