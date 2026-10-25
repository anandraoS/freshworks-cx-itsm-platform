# Part G. MongoDB and Redis

This part covers the two stores that sit beside PostgreSQL in the docker profile: MongoDB for flexible, derived documents, and Redis as a shared cache. It explains the concepts, then shows exactly how the repository uses them, and it is strict about what was and was not run.

> **Honesty box.** No Docker was available. **MongoDB and Redis were never executed against a real server.** The classes `MongoDocumentStore` and `RedisCacheService`, the tests `MongoDocumentStoreDockerIT` and `RabbitRedisDockerIT` (tag `docker`, Testcontainers) were written but not run. What did run: the local profile (H2, Caffeine cache, JPA document store), `CacheServiceTest` (Caffeine behaviour plus Redis behaviour against a **mocked** `StringRedisTemplate`), `CacheIntegrationTest` (Caffeine through the real API), and `DocumentStoreTest` (the JPA and in-memory stores). Every Mongo shell and `redis-cli` command below is a **To-run command** unless it says otherwise.

Labels: **Project-implemented behaviour** means code in the repository; **Conceptual learning example** means a teaching example that is not in the repository.

Sections G.1 to G.7 cover MongoDB.

## G.1 Document modeling

### What it is and why it exists

MongoDB is a document database. Data is stored as BSON documents (binary JSON with extra types such as dates, `ObjectId`, decimals) in collections. It exists because some data is naturally a self-contained nested structure that is read and written as a unit, has a shape that varies between records, or changes shape faster than a migration process can follow.

### How it works internally

- A **collection** is a set of documents; there is no enforced schema unless you add a JSON Schema validator.
- Every document has a unique `_id`, indexed automatically.
- The default storage engine, **WiredTiger**, stores documents in B-trees, compresses them, and uses document-level concurrency control with MVCC snapshots. Maximum document size is 16 MB.
- A **replica set** (a primary plus secondaries) provides durability and failover. Writes go to the primary, are recorded in the **oplog**, and replicate. **Write concern** (`w:1`, `w:"majority"`, `j:true`) decides when a write is acknowledged. **Read concern** (`local`, `majority`, `snapshot`) and **read preference** (`primary`, `secondary`) decide what you can read. Single-document operations are atomic; multi-document ACID transactions exist (since 4.0 on replica sets) but cost more and are not the intended default.
- **Sharding** splits a collection across servers by a shard key. A bad shard key (low cardinality, monotonically increasing) causes hot shards.

### Why this project chose it

The knowledge base and AI features produce two kinds of data that fit documents well:

1. **KB version snapshots**: immutable, denormalized copies of an article at a point in time, read as "list the versions of article X".
2. **AI analysis metadata**: heterogeneous per provider (a rule engine and a remote model report different things), write-once, diagnostic, never used to decide anything.

`docs/kb-storage-notes.md` states the rule of thumb the project used: "if a query needs a join, a constraint or a transaction with something else, it is relational; if it is a self-contained JSON blob that is written once and read by key, it is a document." It also states the invariant that matters most: "Nothing authoritative is written to both. If the document store disappeared, every article, every suggestion and every decision would still be intact."

An honest remark for interviews: a JSON column in PostgreSQL (`jsonb`) could hold this data too, and the project's default `JpaDocumentStore` effectively does that with a `varchar` JSON column. MongoDB is present partly to demonstrate the polyglot pattern behind an interface, and because aggregation pipelines and per-document flexibility are worth showing. A reviewer may ask "why not jsonb?", and the right answer is a trade-off discussion (G.7), not a claim that Mongo was necessary.

### How THIS PROJECT uses it

**Project-implemented behaviour (Mongo path NOT EXECUTED).**

The abstraction is `core-service/src/main/java/com/example/cx/core/docstore/DocumentStore.java`:

| Method | Meaning |
|---|---|
| `put(tenantId, collection, key, doc)` | upsert |
| `get(tenantId, collection, key)` | point lookup |
| `list(tenantId, collection, keyPrefix, limit)` | prefix range scan, ascending by key, limit 1 to 500 |
| `averageBy(tenantId, collection, groupField, valueField)` | aggregation example |

Selection is by property `cx.docstore`:

| Value | Class | Profile | Executed? |
|---|---|---|---|
| `jpa` (default) | `JpaDocumentStore`, table `document_store` (V21) | local and any SQL deployment | yes, H2, `DocumentStoreTest` |
| `memory` | `InMemoryDocumentStore` | unit tests | yes |
| `mongo` | `MongoDocumentStore` | docker (`application-docker.yml` sets `cx.docstore: mongo`) | **no** |

The envelope document in the single physical collection `cx_documents`:

```json
{
  "tenantId": "3f1c...-uuid-string",
  "collection": "kb_article_version",
  "key": "9b2e...-articleId:00000007",
  "doc": { "articleId": "9b2e...", "version": 7, "title": "...", "body": "...", "editorId": "..." },
  "createdAt": "2026-10-17T10:00:00Z",
  "updatedAt": "2026-10-17T10:00:00Z"
}
```

Design points to articulate:

- **The envelope and payload are separated.** The user-controlled payload lives under `doc`, so a payload field named `tenantId` or `key` can never overwrite or collide with the envelope. This also blocks operator injection through field names at the top level.
- **One physical collection, logical collections by field.** This keeps index management simple and mirrors the `document_store` table (`tenant_id, collection, doc_key`).
- **Every query starts with `tenantId`.** There is no tenant-less read path. The implementation converts the UUID to a string (`tenantId.toString()`) consistently on write and read.
- **Key design for ordering.** Version snapshot keys are `{articleId}:{8-digit zero-padded version}`, so lexical order equals version order, and `list(...keyPrefix...)` returns versions ascending with an anchored regex.

Credentials: the URI comes only from `CX_MONGO_URI`, with no default, because it contains a password (`application-docker.yml`). The compose file requires `MONGO_INITDB_ROOT_USERNAME` and `MONGO_INITDB_ROOT_PASSWORD` from `.env`. The port is bound to `127.0.0.1` only.

### Implemented versus not implemented

Implemented: the interface, three implementations, indexes created at startup, tenant scoping, aggregation example, a Docker IT with three tests (`putGetListAreTenantScoped`, `uniqueIndexOnTenantCollectionKeyExists`, `aggregationAveragesConfidenceByProviderPerTenant`). Not implemented: Mongo in the default runtime, replica set configuration (the compose service is a standalone `mongo:7`, so there is no failover and no multi-document transactions), TLS to Mongo, JSON Schema validation, a TTL index, change streams, sharding, and any benchmark.

### Performance, security, mistakes

- Performance: document size matters. A KB article body is up to 15 000 characters, so a snapshot is a few tens of kilobytes at most; 500 snapshots per article are bounded by `MAX_LIST`.
- Security: authentication is enabled by the root user, but the application connects with the root credential in the example URI (`authSource=admin`). A least-privilege user with `readWrite` on database `cx` only is the better practice and is **not** configured.
- Mistakes: unbounded arrays inside documents; treating a missing schema as no schema; using the application's key concatenation without escaping when building regexes (the code uses `Pattern.quote(keyPrefix)`, which prevents regex injection).
- Debugging: `db.cx_documents.getIndexes()`, `db.cx_documents.find({tenantId: "..."}).explain("executionStats")`, `db.currentOp()`, the profiler (`db.setProfilingLevel(1, {slowms: 100})`).

### Alternatives and trade-offs

PostgreSQL `jsonb` with GIN (one database, one transaction, one backup, but weaker aggregation ergonomics and no per-document flexibility at the storage layer), an object store for immutable snapshots (cheap, no query), Elasticsearch/OpenSearch for search-heavy content, DynamoDB or Cosmos for managed key-value documents.

### Interview Q&A

**Q1. Why does this project keep authoritative KB data in PostgreSQL and only snapshots in MongoDB?**
Authoritative data needs constraints (unique slug per tenant, composite tenant FKs, status checks), transactions with the audit row, optimistic locking, tenant filter and RLS, and filtering and paging by status. Snapshots are immutable, self-contained and read by key, so they fit a document store, and losing them loses history only.

**Q2. What consistency problem appears when one operation writes to both PostgreSQL and MongoDB?**
There is no distributed transaction. `docs/kb-storage-notes.md` describes the chosen failure behaviour: the snapshot is written last inside the PostgreSQL transaction and a failure aborts the article change; the inverse failure (snapshot written, commit fails) leaves one orphan snapshot under a version number that the next successful change overwrites, because the write is an upsert on the same key. If stricter guarantees are needed, publish the snapshot via the outbox and let a consumer write it.

**Q3. Why nest the payload under `doc`?**
To keep the envelope fields (`tenantId`, `collection`, `key`) uncontaminated by user-supplied field names, and to let indexes target `doc.provider` and `doc.articleId` distinctly.

**Q4. Is a standalone MongoDB safe for this data?**
For derived, rebuildable data in a demo environment, acceptable. For production you would run a replica set with `w:"majority"`, enable TLS and a least-privilege user, and back up. None of that is configured here.

## G.2 Embedding versus referencing

### What it is

Embedding places related data inside the parent document (an article snapshot contains its title, summary, body, category name and editor name). Referencing stores an id and requires a second query (`$lookup` or an application join).

### Rules of thumb

| Choose embedding when | Choose referencing when |
|---|---|
| the child is always read with the parent | the child is read or updated independently |
| the child set is bounded and small | the child set is unbounded (comments on a hot post) |
| the data is a point-in-time copy (snapshots) | many parents share the child and it changes |
| you want a single-document atomic write | you need constraints and joins |

### How THIS PROJECT uses it

**Project-implemented behaviour.** Version snapshots are fully **denormalized**: "a full copy of title/summary/body/category name/editor" (`docs/kb-storage-notes.md`). That is right for history: the category might be renamed later, but the snapshot must say what it said then. The relational side does the opposite: `kb_article` references `kb_category` by composite foreign key and the list endpoint resolves names at read time. This is a clear contrast to quote: **normalize live data, embed historical data.**

AI metadata (`ai_suggestion_meta`) embeds features, keywords, scores, prompt hash, redaction counts and injection signals in one document per suggestion. The relational `ai_suggestion` row carries only status, tenant, ticket and decision.

### Conceptual learning example

A one-to-many that should be referenced: a ticket with thousands of comments should not embed them in one document, because the document grows without bound (16 MB limit) and every append rewrites a large document in the worst case. A bounded one-to-few that should be embedded: a ticket with at most five attachments metadata.

### Interview Q&A

**Q1. What is the 16 MB limit's practical lesson?**
Model for bounded growth. If an array can grow forever, reference or bucket it (the bucket pattern groups N entries per document).

**Q2. Why are snapshots denormalized but live data is not?**
A snapshot is a historical fact and must not change when the source changes; live data must change in one place only.

**Q3. What do you lose by embedding?**
Cross-document constraints, the ability to update shared data in one place, and joins. You gain locality: one read, one atomic write.

## G.3 MongoDB indexes

### What and how

MongoDB uses B-tree indexes with the same leftmost-prefix rule as relational composite indexes, plus multikey (arrays), text, geospatial, hashed, TTL and partial/sparse variants. The **ESR rule** (Equality, Sort, Range) orders compound index fields. A unique index enforces uniqueness (and is how `put` upserts stay unambiguous).

### How THIS PROJECT uses it

**Project-implemented behaviour (created at startup by `MongoDocumentStore.ensureIndexes`; NOT EXECUTED).**

| Index name | Keys | Serves |
|---|---|---|
| `uq_tenant_collection_key` | `(tenantId asc, collection asc, key asc)` unique | point lookups, upsert identity, anchored-prefix scans |
| `ix_tenant_collection_provider` | `(tenantId, collection, doc.provider)` | grouping and filtering AI metadata by provider |
| `ix_tenant_collection_article_version` | `(tenantId, collection, doc.articleId asc, doc.version desc)` | "versions of article X, newest first" without a regex |

Notes for interviews:

- An anchored regex such as `^abc` can use an index range scan on `key`, which is why the prefix regex is always anchored with `Pattern.quote`. An unanchored regex would scan the index (or collection).
- The `ensureIndex` call is idempotent, so restarts are safe. Index builds on a populated collection use an optimized build in modern MongoDB, but still take resources.
- The third index makes the "newest first" ordering a pure index traversal; the `list` method as written sorts by key ascending and does not use that index, which is a small mismatch between index and the current method. The index exists for a query path the interface does not yet expose.

**To-run commands (NOT EXECUTED):**

```javascript
db.cx_documents.getIndexes()
db.cx_documents.find({ tenantId: "<uuid>", collection: "kb_article_version", key: { $regex: "^<articleId>:" } })
  .sort({ key: 1 }).limit(50).explain("executionStats")
```

What to look for: `winningPlan.stage` is `IXSCAN` (not `COLLSCAN`), `totalKeysExamined` close to `nReturned`, `totalDocsExamined` close to `nReturned`, and no `SORT` stage (the index supplies order).

### Performance, security, mistakes, debugging

- Every index costs write time and memory; keep the working set (hot index pages) in RAM.
- Index prefix order matters the same way as in PostgreSQL: all three indexes lead with `tenantId` and `collection`, so tenant-less queries cannot use them. That is by design and also a security property.
- Mistake: a compound index with a multikey array field and a sort on another array field (not allowed to use both).
- Debugging: `explain("executionStats")`, `$indexStats` aggregation stage for unused indexes, `db.collection.stats()`.

### Interview Q&A

**Q1. What is the ESR rule?**
Equality fields first, then Sort fields, then Range fields in a compound index, so the index supports filtering and ordering without an in-memory sort.

**Q2. What is a covered query in MongoDB?**
A query answered entirely from the index (projection restricted to indexed fields, `_id` excluded unless indexed). The explain shows `totalDocsExamined: 0`.

**Q3. How does a unique compound index help `put`?**
`upsert` on the identity `(tenantId, collection, key)` can race: two concurrent upserts could both insert. The unique index makes the second fail with a duplicate key error, which a robust caller retries as an update.

## G.4 Aggregation pipelines (the project's example)

### What it is

An aggregation pipeline is an ordered list of stages. Each stage transforms the stream of documents: `$match` filters, `$group` aggregates, `$sort`, `$project`, `$lookup` (left outer join), `$unwind`, `$facet`, `$merge`. Putting `$match` first lets it use an index and reduces work.

### How THIS PROJECT uses it

**Project-implemented behaviour (Mongo version NOT EXECUTED; the JVM version ran).**

`MongoDocumentStore.averageBy` builds:

```javascript
db.cx_documents.aggregate([
  { $match: { tenantId: "<uuid>", collection: "ai_suggestion_meta" } },
  { $group: { _id: "$doc.provider", avg: { $avg: "$doc.confidence" } } },
  { $sort:  { _id: 1 } }
])
```

The result maps provider to average confidence for one tenant. Spring Data builds it with `Aggregation.newAggregation(match, group(...).avg(...).as("avg"), sort)`. The same result shape is produced by `DocAggregations.averageBy`, a JVM fold over at most 500 documents, which `JpaDocumentStore` and `InMemoryDocumentStore` use. `DocumentStoreTest` asserts both stores honour the contract (upsert, get, prefix list ascending, tenant scoping, `averageBy`).

**Conceptual learning example (not in the repository): a richer pipeline** to count suggestions that mention a keyword, by provider and by day:

```javascript
db.cx_documents.aggregate([
  { $match: { tenantId: "<uuid>", collection: "ai_suggestion_meta", "doc.keywords": "refund" } },
  { $group: { _id: { provider: "$doc.provider",
                     day: { $dateToString: { format: "%Y-%m-%d", date: "$createdAt" } } },
              n: { $sum: 1 }, avgConfidence: { $avg: "$doc.confidence" } } },
  { $sort: { "_id.day": 1 } }
])
```

The equivalent SQL is a `GROUP BY provider, date_trunc('day', created_at)` over a table. The honest point: for two fields and an average the SQL version is just as short, so the pipeline is there to show the model, not because SQL could not.

### Performance, security, mistakes

- Stage order: `$match` and `$sort` early to use indexes; `$group` holds data in memory up to 100 MB per stage unless `allowDiskUse` is set.
- Security: do not build pipelines by concatenating user input into field names (`"doc." + groupField` in the project is only safe because callers pass constants; if user input ever reaches `groupField` it must be whitelisted, because a field name like `$where` style operators or very large cardinality could cause abuse).
- Mistake: running `$lookup` against a large unindexed foreign collection.

### Interview Q&A

**Q1. Why `$match` first and on `tenantId`?**
It uses the index to cut the working set and it is the tenant isolation boundary. A pipeline without it would aggregate across tenants.

**Q2. When does an aggregation pipeline beat application-side folding?**
When the result is far smaller than the input: the server returns a few groups instead of shipping hundreds of documents. The JVM fold here is fine only because the input is capped at 500.

**Q3. What is `allowDiskUse` and its risk?**
It lets blocking stages spill to disk instead of failing at 100 MB; it avoids errors but can make queries slow and disk-heavy.

## G.5 Schema evolution and versioning

### What it is

A schemaless store moves schema enforcement into the application. Documents of different shapes coexist, so readers must tolerate old shapes.

### Techniques

1. **Schema version field** in each document and readers that upgrade on read (lazy migration).
2. **Additive changes only**: new optional fields; unknown fields ignored.
3. **Backfill jobs** for changes that must apply to all documents, run in bounded batches.
4. **JSON Schema validation** (`$jsonSchema` validator on the collection) in `moderate` or `strict` mode for guard rails.

### How THIS PROJECT approaches it

**Project-implemented behaviour.** The document payload is a `Map<String,Object>`, so the store itself imposes no schema. Compatibility is handled at two levels:

- The event envelope has `SCHEMA_VERSION = 1` (`OutboxService`), and consumers set `FAIL_ON_UNKNOWN_PROPERTIES=false` (`NotificationMessageHandler`), which is tolerant-reader behaviour.
- For KB snapshots, `docs/kb-storage-notes.md` notes the shape "may grow (e.g. diff, review notes) without a migration". There is **no** `schemaVersion` field in the documents, **no** Mongo validator and **no** backfill tool. If the snapshot shape changed incompatibly, a reader would have to branch on field presence.

This is an honest gap worth stating: "schema-flexible" is a benefit only when a version marker and a tolerant reader exist.

### Conceptual learning example

```javascript
// Add validation to a collection without breaking existing documents
db.runCommand({ collMod: "cx_documents", validationLevel: "moderate", validationAction: "warn",
  validator: { $jsonSchema: { bsonType: "object", required: ["tenantId","collection","key","doc"],
    properties: { tenantId: { bsonType: "string" }, doc: { bsonType: "object" } } } } })
```

### Interview Q&A

**Q1. How do you roll out a breaking field rename in MongoDB?**
Expand and contract: write both fields, update readers to prefer the new one, backfill in batches, then stop writing the old one and remove it.

**Q2. Is "schemaless" a good selling point?**
Only for data whose shape varies by nature. For stable structured data, a schema (relational or validated Mongo) catches mistakes earlier.

## G.6 Retention and TTL

### What it is

A TTL index (`expireAfterSeconds`) on a date field makes MongoDB's background task (runs about every 60 seconds) delete expired documents. Retention is the policy about how long data is kept.

### How THIS PROJECT uses it

**Project-implemented behaviour.** There is **no TTL index** on `cx_documents`. The only retention implemented in the repository is relational: `OutboxPurgeJob` deletes `SENT` outbox rows older than `cx.outbox.retention-days` (7) in bounded batches (`purge-batch-size: 500`, `purge-max-batches-per-run: 20`, hourly), and keeps `FAILED` rows. Idempotency keys are purged after about 24 hours (`TicketCommandService`, with a purge that logs a warning on failure). Version history is bounded only on read (500 per article). Whether KB version history should expire is a product and compliance decision that is not made here.

### Conceptual learning example

```javascript
db.cx_documents.createIndex({ createdAt: 1 }, { expireAfterSeconds: 60*60*24*365, partialFilterExpression: { collection: "ai_suggestion_meta" } })
```

This would expire AI diagnostics after a year but never touch KB snapshots. Note that TTL deletion is approximate, is not instant, and deletes are replicated and cost I/O.

### Interview Q&A

**Q1. What are the limits of TTL indexes?**
They run on a single date field (or an array of dates), expiry is approximate (the monitor runs about once a minute and large deletions can lag), and they cannot be compound.

**Q2. How would you retain data for GDPR deletion requests per tenant?**
A per-tenant delete by `tenantId` (the first key of every index makes it efficient), plus a documented process for the relational side. The project has no tenant offboarding job yet.

## G.7 PostgreSQL versus MongoDB trade-offs

| Dimension | PostgreSQL (project: authoritative data) | MongoDB (project: snapshots and metadata) |
|---|---|---|
| Model | relational tables, constraints, joins | documents, embedding, `$lookup` |
| Transactions | strong multi-row ACID, default | single-document atomic; multi-document supported on replica sets at a cost |
| Schema | enforced by DDL and Flyway | flexible; enforced by app or validators |
| Queries | SQL, planner, rich joins | MQL and pipelines, joins are limited |
| Scaling writes | vertical, then partitioning or sharding extensions | native sharding |
| Flexible JSON | `jsonb` with GIN | native |
| Tenant isolation | FK + filter + RLS (RLS not executed) | application predicate only; no native row-level security |
| Operations in this project | not run (no Docker) | not run (no Docker) |
| Failure impact here | system of record: outage is an outage | derived data: outage loses history and diagnostics only |

Decision summary: use PostgreSQL by default; add MongoDB only when document shape variability or an aggregation workload justifies a second system, and then keep it non-authoritative. Cost of the second store: another thing to secure, back up, monitor and keep consistent, and no cross-store transactions.

### Troubleshooting exercise 1 (Mongo document missing after an article update)

Symptom: the article shows version 8 in PostgreSQL but `list(..., "<articleId>:", 50)` returns only 7 snapshots. Reasoning: the snapshot is written last inside the PostgreSQL transaction; a failure aborts the article change, so version 8 existing without its snapshot would require the snapshot write to have been skipped or to have failed after commit. Check: logs for write errors around the time; `db.cx_documents.find({tenantId:"<t>", key:"<articleId>:00000008"})`; whether `cx.docstore` was `jpa` at the time (snapshots then went to `document_store` instead). Fix: rewrite the snapshot from the article row if it is the current version; otherwise document the gap.

### Troubleshooting exercise 2 (aggregation returns nothing)

Symptom: `averageBy` returns an empty map in docker but works locally. Causes: `doc.confidence` stored as a string (the code only accepts `Number`); `tenantId` stored as a binary UUID by another writer while queries use a string (the project consistently uses strings); the logical collection name differs. Check with `db.cx_documents.findOne({collection:"ai_suggestion_meta"})` and `typeof` of the fields (`$type` operator).

Sections G.8 to G.15 cover Redis.

## G.8 Redis data structures

### What Redis is and why it exists

Redis is an in-memory data structure server. Its data lives in RAM, so reads and writes take microseconds to low milliseconds over the network, and it offers structures richer than key-value strings. It exists because databases are too slow and too expensive per query for hot, repeated, or ephemeral data, and because many problems (rate limiting, leaderboards, queues, locks) map directly to its structures.

### How it works internally

Redis executes commands on a **single main thread** (I/O can be threaded in newer versions), so each command is atomic. Persistence is optional: **RDB** snapshots and the **AOF** (append-only file) with `appendfsync` policies. Replication is asynchronous from a primary to replicas; **Sentinel** adds failover, **Cluster** shards keys across 16 384 hash slots. Memory is bounded by `maxmemory`; when it is reached, the **eviction policy** decides what happens.

### Data structures and typical use

| Structure | Commands | Typical use | In this project |
|---|---|---|---|
| String | `GET`, `SET key value EX seconds`, `INCR` | cached JSON, counters, simple locks | cache values (JSON strings) |
| Hash | `HSET`, `HGETALL` | object fields | not used |
| List | `LPUSH`, `BRPOP` | simple queues | not used |
| Set | `SADD`, `SISMEMBER` | membership, dedupe | not used |
| Sorted set | `ZADD`, `ZRANGE` | leaderboards, sliding-window rate limits, delayed jobs | not used |
| Stream | `XADD`, `XREADGROUP` | log with consumer groups | not used (RabbitMQ is the broker) |
| HyperLogLog, bitmap, geo | | approximate counts, flags, geo | not used |

**Project-implemented behaviour.** Only **strings with TTL** are used, through `StringRedisTemplate.opsForValue().get/set`, plus `delete` and a `SCAN` with `DEL` for prefix eviction. Keys follow `t:{tenantId}:{name}:{args}`, for example `t:<uuid>:analytics:dashboard:30`.

**Not implemented** (and worth knowing when asked): the login rate limiter (`LoginRateLimiter`), the AI rate limiter (`AiRateLimiter`), the token revocation negative cache (`TokenRevocationService`) and the gateway token bucket are all **in-process Caffeine or in-memory structures**, not Redis. With several core instances, the effective limits are per node, as the performance guide notes (for the gateway, "N replicas multiply the effective limit"). A shared Redis counter or sorted-set sliding window would give global limits; it is a known gap, not an implemented feature.

### Interview Q&A

**Q1. Why is Redis fast, and what is the catch of being single threaded?**
Data is in memory, structures are simple, and there is no lock contention on the data. The catch: one slow command (for example `KEYS *` or a huge `SMEMBERS`) blocks every client. That is why the project uses `SCAN` with `COUNT 200`, never `KEYS`.

**Q2. Which structure would you use for a sliding-window rate limit?**
A sorted set per user where the score is the timestamp: `ZADD`, `ZREMRANGEBYSCORE` to trim the window, `ZCARD` to count, wrapped in a Lua script or `MULTI` for atomicity. Or a token bucket with `INCR` and `EXPIRE`.

**Q3. What does Redis guarantee about durability?**
Little by default. RDB can lose minutes of data; AOF with `everysec` can lose about a second; replication is asynchronous so a failover can lose acknowledged writes. Therefore treat Redis as a cache or ephemeral store unless you design around loss.

## G.9 Cache-aside, TTL and the project's CacheService

### What cache-aside is

The application owns the cache. On read: look in the cache; on a miss, load from the database, then put the value in the cache with a time to live. On write: change the database and then invalidate (evict) the cache entry. It is the most common pattern because the cache is optional and failures degrade to the database.

![Figure: Cache-aside flow with single-flight and Redis-down fallback](docs/diagrams/png/13-cache-aside.png)

Alternatives: **read-through** (the cache library loads on a miss), **write-through** (writes go to the cache and database together), **write-behind** (writes go to the cache and asynchronously to the database; fast but risks data loss), **refresh-ahead** (refresh before expiry).

### Why TTL

A time to live bounds how stale data can be even if an invalidation is missed (a bug, a crashed process, a replica lag). It also bounds memory. Choosing it is a business decision: how stale may this screen be.

### How THIS PROJECT uses it

**Project-implemented behaviour.** The contract is `CacheService` (`core-service/.../cache/CacheService.java`): `getOrLoad(tenantId, key, ttl, TypeReference<T>, Supplier<T> loader)`, `evict`, `evictByPrefix`, and `fullKey` which returns `"t:" + tenantId + ":" + key` and throws if the tenant is null. Two implementations:

| Profile | Class | Backing | Executed? |
|---|---|---|---|
| local (`!docker`) | `CaffeineCacheService` | in-process Caffeine, `maximumSize = cx.cache.max-entries` (10 000), per-entry TTL through `expireAfter` | yes (tests and the load tests) |
| docker | `RedisCacheService` | `StringRedisTemplate`, JSON via Jackson, TTL via `SET ... EX` | **no** (mock-based unit tests only) |

What is cached and for how long (from the source and the performance guide):

| Cache label (`cx.cache` tag) | Data | TTL | Invalidation |
|---|---|---|---|
| `analytics` | dashboard per tenant and days, key `analytics:dashboard:{days}` | 60 s (`AnalyticsService.TTL`) | `evictByPrefix(tenant, "analytics:")` after commit of ticket writes and AI decisions |
| `categories` | ticket categories | per the service | evict on write |
| `sla` | SLA policy | 5 min | evict on update |
| `settings` | tenant calendar settings, `TenantSettingsService` | 5 min | `cache.evict` on settings update |
| `workflow` | workflow definitions | per the service | evict on write |
| `kb` | category list (5 min) and published article pages (60 s) | as stated | evicted after commit via `TxHooks.afterCommit` |

Eviction after commit: `TxHooks.afterCommit(() -> cache.evictByPrefix(tenant, "analytics:"))`. If the transaction rolls back nothing is evicted; if it commits the entry is dropped. A remaining race: a concurrent reader can load the old row just before commit, after the evict a moment later it stores a stale value that lives until its TTL. The TTL (60 s for the dashboard) is the safety net. This is a classic trade-off the project accepts knowingly.

**The measured effect (H2 plus Caffeine, from the performance guide section 4):** on a warm JVM with 3 753 tickets, a dashboard cache hit had a median of **3.23 ms** and a miss **21.73 ms** (about 6.7 times). Under the write-heavy load test the cache counters read 31 983 hits and 1 697 misses, a **95.0 percent hit ratio**, because create and mixed scenarios evict the tenant's analytics entries on every write. These are Caffeine numbers; with Redis a hit adds a network round trip and JSON decode, and that was **not measured**.

**Redis behaviours in `RedisCacheService` (NOT EXECUTED):**

- Read: `redis.opsForValue().get(fullKey)`; a non-null JSON string is decoded with `mapper.readValue(json, type)`. An undecodable entry is deleted and treated as a miss (protects against format changes after a deploy).
- Write: `SET` with TTL plus **jitter** of plus or minus 10 percent (`ttl * 0.1 * random(-1,1)`), so keys written together do not expire together.
- Values that are `null` are **not cached** (no negative caching), so a missing item hits the database every time. That is a deliberate simplification and a cache penetration weakness (G.12).
- The cached values must be Jackson-serializable records or DTOs, never JPA entities (stated in the interface Javadoc).
- Evict: `DEL` for a key; `evictByPrefix` uses `SCAN` with `MATCH t:{tenant}:{prefix}*` and `COUNT 200` and deletes each key.

### Implemented versus not implemented

Not implemented: negative caching, cache warming, versioned keys, pub/sub invalidation across nodes, Redis Cluster, compression, `maxmemory` configuration (compose starts `redis-server --requirepass ... --appendonly yes` with no `maxmemory`, so the default **noeviction** policy applies and a full Redis would reject writes, which the application treats as a counted fallback), TLS to Redis, ACL users. The Caffeine cache in a multi-node deployment is per node, so invalidation on one node does not clear the others. That is only a local-profile concern, because docker uses Redis.

### Performance, security, mistakes, debugging

- Performance: cached payload size (the dashboard JSON) and serialization cost; `SCAN` is O(keys in the database), not O(matches), so `evictByPrefix` on a huge keyspace is costly. Keys with tenant prefix make a per-tenant drop possible but not cheap.
- Security: the password comes from `CX_REDIS_PASSWORD` (empty default means no password for a local dev instance: dangerous if exposed); the compose file requires `REDIS_PASSWORD` and binds to `127.0.0.1`. Keys contain tenant ids, so a mis-keyed value would be a cross-tenant leak: all access goes through `fullKey`. Never cache per-user secrets (tokens) in shared keys. Redis commands such as `FLUSHALL`, `CONFIG` and `KEYS` should be restricted by ACL in production.
- Mistakes: caching entities (lazy proxies, version fields), forgetting to include a variant in the key (for example staff versus customer response shape: the KB module uses separate keys for that reason, "so ticket ids can never leak through a shared entry"), unbounded key cardinality, unbounded TTL.
- Debugging: `redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping`, `INFO memory`, `INFO stats` (`keyspace_hits`, `keyspace_misses`, `evicted_keys`), `TTL key`, `--scan --pattern 't:*:analytics:*' | head`, `SLOWLOG GET 10`, `MONITOR` (never in production for long), `redis-cli --latency`.

### Alternatives

Caffeine only (fastest, per node, no shared invalidation), Redis (shared, network hop), Hazelcast or Infinispan (distributed in-process), Memcached (simpler, no structures), CDN or HTTP caching for public content. A two-level cache (Caffeine in front of Redis) with pub/sub invalidation is the usual next step; it is not implemented.

### Interview Q&A

**Q1. Walk through cache-aside for the dashboard in this project.**
A request calls `AnalyticsService.dashboard`, which calls `cache.getOrLoad(tenant, "analytics:dashboard:30", 60 s, type, loader)`. The key becomes `t:<tenant>:analytics:dashboard:30`. On a hit the cached DTO returns; on a miss the loader computes the dashboard from the database (about nine queries) and the result is stored with TTL. A ticket write commits and then `TxHooks.afterCommit` evicts every `analytics:` key of that tenant.

**Q2. Why evict after commit instead of before or inside the transaction?**
Before commit, a concurrent reader may reload the old value and re-cache it; and if the transaction rolls back you evicted for nothing. After commit the database already has the new data, so the next miss loads it. The remaining race is small and bounded by the TTL.

**Q3. Why jitter the TTL?**
Entries created at the same moment expire at the same moment, producing a synchronized wave of misses (an avalanche). Plus or minus 10 percent spreads them.

**Q4. What goes wrong if the cache key omits the tenant?**
One tenant reads another's data: a severe isolation bug. The design forces every call through `CacheService.fullKey`, which throws on a null tenant, and `CacheServiceTest.keysAreTenantPrefixedAndIsolated` asserts isolation (Caffeine, executed).

**Q5. Cache-aside versus write-through, and when would you not cache?**
Cache-aside tolerates cache loss and is simple but has stale windows. Write-through keeps the cache current but couples writes to the cache availability. Do not cache data that is cheap to read, unique per request (search results with high cardinality: the KB search is deliberately not cached), or that must be strictly fresh (authorization decisions without a bounded staleness).

## G.10 Eviction policies and memory

### What it is

When Redis reaches `maxmemory`, the policy decides: `noeviction` (reject writes), `allkeys-lru`, `allkeys-lfu`, `volatile-lru`, `volatile-lfu`, `volatile-ttl`, `allkeys-random`, `volatile-random`. "volatile" policies only consider keys with a TTL. Redis LRU and LFU are **approximate** (sampling `maxmemory-samples` keys), trading accuracy for speed.

### How THIS PROJECT uses it

**Project-implemented behaviour for Caffeine (executed):** Caffeine uses a size-bounded cache (`maximumSize`) with the W-TinyLFU admission policy, plus per-entry TTL. `CacheServiceTest.ttlExpiresEntries` checks TTL expiry.

**Redis (NOT EXECUTED):** No `maxmemory` or eviction policy is configured (compose command: `redis-server --requirepass ... --appendonly yes`). Because every project key has a TTL (the project never writes a key without one), `volatile-lru` or `allkeys-lru` with a `maxmemory` would be an appropriate production choice; set it explicitly. Under the default `noeviction`, a memory-full Redis returns an `OOM` error to writes, which `RedisCacheService.writeQuietly` turns into a breaker trip (counted as `cx.cache.fallback{op="write"}`), so the system keeps serving from the database but loses caching.

Also: `appendonly yes` is set, which gives durability that a pure cache does not need. It costs disk I/O and slows restarts. It is harmless but not required by the design, because "nothing is authoritative in Redis".

### Interview Q&A

**Q1. LRU versus LFU for a dashboard cache?**
LRU evicts the least recently used; good for recency-driven access. LFU keeps frequently used keys; better when a few hot keys must survive scans of one-off keys. With a small set of per-tenant dashboards both work; the real risk is a flood of one-off keys (high-cardinality keys), which LFU resists better.

**Q2. What happens with `noeviction` when memory is full?**
Writes fail with an OOM error; reads continue. For a cache you almost always want an evicting policy.

## G.11 Invalidation, stale data and consistency

### The two hard problems

"There are only two hard things in computer science: cache invalidation and naming things." The causes of staleness are: missed invalidation, race between reader and writer, replica lag, and TTL semantics.

### Strategies

1. **TTL only**: simple, bounded staleness.
2. **Explicit eviction on write**: precise, requires every write path to evict.
3. **Versioned keys** (`key:v{version}`): writers bump a version; readers read the version first; no deletes needed.
4. **Event-driven invalidation** (pub/sub or message): needed with a local cache layer across nodes.

### How THIS PROJECT approaches it

**Project-implemented behaviour.** TTL plus explicit eviction after commit, keyed by prefix per tenant. The `analytics:` prefix eviction hits every `days` variant for that tenant. Tests (Caffeine, executed): `CacheIntegrationTest.dashboardIsCachedPerTenantAndInvalidatedByTicketWrites`, `categoriesAreCachedAndInvalidatedOnWrite`, `settingsUpdateEvictsTheCalendarCache`; `CacheServiceTest.evictAndEvictByPrefix` proves other prefixes and other tenants are untouched.

Known staleness windows, stated as you would in a design review:

| Source of staleness | Window | Mitigation present |
|---|---|---|
| Reader reloads old row just before writer commits, stores after evict | up to the TTL (60 s dashboard) | short TTL |
| Eviction fails because Redis is down | until Redis returns; the entries may then be stale up to their TTL | TTL; counted by `cx.cache.fallback{op="evict"}` |
| `SCAN`-based prefix delete misses keys created during the scan | until TTL | TTL |
| Per-node Caffeine in a multi-node local deployment | until TTL | not applicable to docker profile |

Not implemented: version-stamped keys, pub/sub invalidation. The 5 minute TTL for settings and categories means a changed setting can be visible late on nodes whose eviction failed.

### Interview Q&A

**Q1. How would you reduce the stale window if 60 seconds were too long?**
Cache the heavy computation behind a version key bumped by writers; or store a per-tenant epoch counter in Redis and include it in the cache key; or reduce TTL and accept more misses.

**Q2. Is it acceptable that the dashboard is up to 60 s stale after a write?**
For analytics, yes, and it is a product decision. Not for authorization or for the ticket itself (tickets are not cached).

## G.12 Stampede, single-flight and the other cache failure modes

### The problem

A cache **stampede** (thundering herd, dogpile) happens when a hot key expires and many concurrent requests all miss and all hit the database to rebuild it. The database sees a spike exactly when the value was supposed to protect it. Related failures:

| Failure | Cause | Defence |
|---|---|---|
| Stampede | hot key expires, N concurrent misses | single-flight / lock, soft TTL, refresh ahead |
| Avalanche | many keys expire together, or the cache dies | TTL jitter, circuit breaker, capacity for cold start |
| Penetration | requests for keys that do not exist bypass the cache | negative caching, Bloom filter, validation |
| Hot key | one key takes huge traffic on one Redis shard | local cache in front, key replication |

![Figure: Single-flight prevents a stampede; Redis failure falls back to the loader](docs/diagrams/png/22-cache-stampede-singleflight.png)

### How THIS PROJECT defends

**Project-implemented behaviour.**

- **Single-flight per node** in both implementations: `ConcurrentHashMap<String, CompletableFuture<Object>> inflight`. The first thread to `putIfAbsent` the future runs the loader; later threads for the same key `join()` that future and get its result. The loader runs outside any map lock, so a loader that itself uses the cache cannot deadlock (a deliberate improvement over `ConcurrentHashMap.computeIfAbsent`, which forbids recursive updates; `loaderMayUseTheCacheItself` tests it).
- If the loading thread throws, waiting threads see the exception through `join()` and fall back to calling the loader themselves (a simple recovery, though it can multiply a failing load by the number of waiters).
- Failures and `null` results are **not cached** (`failuresAndNullsAreNotCached`).
- **Jitter** of plus or minus 10 percent on Redis TTL.
- **Executed proof:** `CacheServiceTest.singleFlightPreventsAStampede` starts 12 threads on one hot key with a 300 ms loader and asserts the loader ran **once**. This is Caffeine, executed. The Redis class shares the same pattern but its single-flight was never run against a real Redis.
- **Limit stated in the Javadoc:** across nodes the worst case is **one load per node per TTL**, which is acceptable for 60 s dashboards. A distributed lock (`SET lock NX PX`) would reduce it to one load globally but adds failure modes (lock expiry while loading, lock holder crash).

### Not implemented

Negative caching (null is not cached, so repeated lookups for absent keys always hit the database; the project's keyed values such as dashboards are never absent, so it is low risk here), soft TTL with background refresh, a distributed lock, Bloom filters.

### Conceptual learning example (not in the repository): distributed single-flight with a Redis lock

```java
// Conceptual only. Fails safe: if the lock cannot be taken within a short wait, load anyway.
String lockKey = "lock:" + key;
boolean got = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, token, Duration.ofSeconds(10)));
if (got) { try { value = loader.get(); put(key, value); } finally { releaseIfOwner(lockKey, token); } }
else { sleepBriefly(); value = readFromCacheOrLoad(); }
```

The release must be atomic (a Lua script that compares the token, then deletes), otherwise a slow holder can delete someone else's lock. For correctness-critical mutual exclusion Redis locks are not enough (clock and failover issues); for a cache stampede guard they are acceptable.

### Interview Q&A

**Q1. Explain how single-flight works in your code and what it does not cover.**
One `CompletableFuture` per in-flight key per JVM; followers wait for the leader. It protects a node from its own concurrent misses, but ten nodes can still each load once per TTL. A test with 12 threads and a 300 ms loader verifies one load locally.

**Q2. What is the difference between stampede and avalanche?**
Stampede is one hot key expiring under concurrency; avalanche is many keys expiring together or the cache layer disappearing. Single-flight addresses the first; jitter and a breaker address the second.

**Q3. How would you protect against cache penetration?**
Cache a short-lived "not found" marker, validate inputs before hitting the cache, and for huge keyspaces use a Bloom filter. The project does not implement negative caching.

## G.13 Failure handling: Redis down, and the simulation test

### What should happen

The cache is an optimization. When Redis is unreachable, requests must still succeed, served from the database, without each request paying a connect timeout.

### How THIS PROJECT behaves

**Project-implemented behaviour.** `RedisCacheService` wraps every Redis call:

- `readQuietly`: on any `RuntimeException` call `tripBreaker("read", e)` and return null (a miss). If the breaker is already open (`System.nanoTime() < skipRedisUntilNanos`), it counts `cx.cache.fallback{op="read"}` and returns null **without touching Redis**.
- `writeQuietly`: skipped while the breaker is open; failures trip the breaker.
- `guarded` for evictions: failures are swallowed.
- `tripBreaker` increments `cx.cache.fallback` with the operation tag, sets `skipRedisUntilNanos = now + backoff` (`cx.cache.redis-failure-backoff-seconds`, default 5), and logs a warning "Redis unavailable (read); serving without cache for 5s".
- Connection and read timeouts are 500 ms (`application-docker.yml`: `timeout: 500ms`, `connect-timeout: 500ms`), so the first failed call is bounded.

This is a minimal circuit breaker: closed, then open for 5 s, then the next call is the trial (there is no explicit half-open state; the first call after the window is simply attempted, and a failure re-opens).

**The simulation test (executed with a mock).** `CacheServiceTest.redisDownFallsBackToTheLoaderAndTripsTheBreaker` mocks `StringRedisTemplate` and `ValueOperations`, makes `get` throw `RedisConnectionFailureException("Connection refused (simulated)")`, and asserts:

1. two `getOrLoad` calls both return the loader value (`from-db-1`, `from-db-2`),
2. `cx.cache.fallback{op="read"}` is at least 1,
3. `ops.get` was invoked exactly **once** (`verify(ops, times(1)).get(anyString())`) even though two requests ran, proving the breaker stops contacting a dead Redis during the back-off window,
4. `set` was never called,
5. `evict` and `evictByPrefix` do not throw.

Two more mock tests: `redisHitsAreTenantKeyedJsonAndCounted` (tenant-keyed JSON, hit and miss counters, `set` with a TTL under the right key) and `redisWriteFailureStillReturnsTheLoadedValue` (a `QueryTimeoutException` on `set` still returns the value).

What this does **not** prove: real network behaviour, real timeouts, Lettuce reconnection, serialization against a real server, or `SCAN` semantics. `RabbitRedisDockerIT.redisCacheHitsThenDegradesGracefullyWhenRedisStops` is the real test (starts a Redis container, checks the key `t:<tenant>:k` exists and is deleted by `evictByPrefix`, stops the container, expects the loader value) and **was not run**.

Consequences to discuss: when Redis returns, every key is cold, so the database sees a burst (the cold-start load; the performance guide measured a dashboard miss at about 6.7 times a hit on Caffeine/H2). The breaker window plus jitter spreads it somewhat; single-flight limits it per node.

### Observability: cache metrics (`cx.cache`)

| Prometheus name | Labels | Meaning |
|---|---|---|
| `cx_cache_total` | `cache` (analytics, categories, sla, settings, workflow, kb), `result` (hit, miss) | every lookup |
| `cx_cache_fallback_total` | `op` (read, write, evict) | Redis failure or breaker skip |

Hit ratio: `sum(rate(cx_cache_total{result="hit"}[5m])) / sum(rate(cx_cache_total[5m]))`. Alert: `rate(cx_cache_fallback_total[5m]) > 0`. Measured reference (Caffeine, load test): `settings` 3 925 hits and 3 misses, `sla` 8 289 and 3, `workflow` 3 617 and 1, `categories` 5 and 1, `analytics` 95.0 percent overall. Redis server side: `INFO stats` `keyspace_hits` and `keyspace_misses`.

### Troubleshooting exercise 3 (Redis outage runbook, from the performance guide Runbook 4; NOT EXECUTED)

Symptoms: latency up on cached endpoints, WARN logs "Redis unavailable (read|write|evict); serving without cache for 5s", `cx_cache_fallback_total` rising, `hikaricp_connections_active` rising. Steps:

```bash
curl -s localhost:8081/actuator/prometheus | grep -E 'cx_cache_(total|fallback_total)'
redis-cli -h 127.0.0.1 -a "$REDIS_PASSWORD" --no-auth-warning ping
redis-cli -h 127.0.0.1 -a "$REDIS_PASSWORD" --no-auth-warning info memory
docker compose -f infra/docker-compose.yml logs redis | tail
```

Causes to separate: Redis process down, wrong password (authentication errors, not connection errors), 500 ms timeout too tight under load, memory full with `noeviction`, a huge `SCAN` blocking. Remediation: restore Redis, nothing to replay (entries refill on demand), and protect the database during the cold start. Prevention: alert on fallbacks, size the database for a cold cache, never store authoritative data in Redis.

### Troubleshooting exercise 4 (stale dashboard after ticket creation)

Symptom: a manager creates a ticket and the dashboard count does not change for a minute. Checks: confirm the write path calls the eviction (the create flow evicts `analytics:` after commit); confirm the request hits the same node/cache (docker uses Redis so should be shared); check the `evict` fallback counter (Redis hiccup during eviction would leave the entry until TTL); compare the Redis `TTL` of the key. Expected explanation if no bug: eviction succeeded but a concurrent reader re-cached an old value (race) and the TTL is 60 s.

### Interview Q&A

**Q1. What must happen when Redis is down and why is a breaker needed?**
Requests must succeed from the database. Without a breaker every request waits for a connect or read timeout (500 ms here) on top of the database time, which can exceed gateway timeouts and tie up request threads. The breaker skips Redis for 5 s after a failure.

**Q2. How did you test Redis failure when Docker was unavailable, and what is the limit?**
With a mocked `StringRedisTemplate` that throws `RedisConnectionFailureException`, asserting loader fallback, the fallback counter, single contact with Redis during the back-off, and no exceptions from evictions. The limit is that it validates the control flow, not real client or network behaviour; a Testcontainers IT exists for that and has not run.

**Q3. What happens to the database when Redis comes back empty?**
All keys are cold, so each distinct key causes one load per node (single-flight), spread by jitter on later refreshes. Size the database for the cold-cache case.

**Q4. Should the application fail open or fail closed when the cache is down?**
For a performance cache, fail open to the source of truth. For a security control such as a rate limiter or token revocation list, the answer depends on the risk: failing open means no limit during an outage. The project's rate limiter and revocation cache are in process, so they do not depend on Redis at all.

**Q5. What are the risks of `SCAN`-based prefix eviction?**
It is incremental and non-blocking per call, but it iterates the whole keyspace, costs O(N) overall, is not atomic (keys created during the scan may survive), and can be slow on big databases. A per-tenant epoch in the key avoids scanning entirely.

## G.14 Alternatives and trade-offs: Redis versus Caffeine versus the database

| Concern | Caffeine (local) | Redis (docker) | Database only |
|---|---|---|---|
| Latency | nanoseconds to microseconds | sub-millisecond plus network hop | milliseconds |
| Shared between nodes | no | yes | yes |
| Invalidation across nodes | none | one place | not needed |
| Failure mode | none external | network and server outage (handled) | the database is the system |
| Memory | JVM heap, GC pressure | separate process | n/a |
| Project status | executed, measured | written, mock-tested, never run | baseline |

Decision for this project: Caffeine locally (zero dependencies) and Redis in the docker profile (shared across core replicas), both behind one interface, with identical keying and single-flight semantics.

## G.15 Quick interview recap for Part G

- MongoDB holds only derived, non-authoritative documents; the Mongo path is written and test-designed but not executed; the default runtime uses a JPA table behind the same interface.
- Redis holds only strings with TTL, tenant-prefixed, behind `CacheService`; single-flight, jitter, breaker and metrics exist; Redis-down behaviour is verified with a mock, not a real outage.
- Known gaps to state first: no `maxmemory` or eviction policy configured, no negative caching, no cross-node single-flight, no TTL index or schema version in Mongo, no least-privilege Mongo user, global rate limits not backed by Redis.
- Measured numbers are Caffeine and H2 only: 3.23 ms hit versus 21.73 ms miss, 95.0 percent hit ratio under write-heavy load.
