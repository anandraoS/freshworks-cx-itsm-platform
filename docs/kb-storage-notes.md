# Knowledge base / AI: why two kinds of storage

Short version: **the relational database is the source of truth; the document store holds derived, append-only, schema-flexible data.**
Nothing authoritative is written to both. If the document store disappeared, every article, every suggestion and every decision would still be
intact; only the version history and the analysis metadata would be gone.

## What lives where

| Data | Store | Why |
|---|---|---|
| Article metadata + **current** content (`kb_article`: title, slug, summary, body, category, visibility, status, author, timestamps, `@Version`) | PostgreSQL / H2 (relational) | Needs constraints (unique slug per tenant, composite tenant FKs, status/visibility checks), transactions with the audit row, optimistic locking, tenant filter + RLS, filtering and paging by status/visibility/category. This is what the visibility rule (CUSTOMER = PUBLISHED + PUBLIC) is enforced against. |
| Categories, article<->ticket links | relational | Referential integrity to `ticket`, many-to-many with uniqueness. |
| AI suggestion **status / tenant / ticket / decision** (`ai_suggestion`) | relational | The approval gate is a compare-and-set `UPDATE ... WHERE status = 'PENDING_REVIEW'`; "approve twice" safety needs a transactional, strongly consistent row. Also feeds `aiAcceptanceRatePercent`. |
| KB **version snapshots** (`kb_article_version`, one document per committed change) | `DocumentStore` | Immutable, read mostly as "list the versions of article X", denormalised (a full copy of title/summary/body/category name/editor), and the shape may grow (e.g. diff, review notes) without a migration. |
| AI **analysis metadata** (`ai_suggestion_meta`: features/keywords matched, scores, prompt hash, provider details, redaction counts, injection signals) | `DocumentStore` | Heterogeneous per provider (a rule engine and a remote model report different things), write-once, diagnostic. Schema-flexible by nature; never used to decide anything. |

Rule of thumb used: *if a query needs a join, a constraint or a transaction with something else, it is relational; if it is a self-contained
JSON blob that is written once and read by key, it is a document.*

## Implementations of `DocumentStore` (`com.example.cx.core.docstore`)

| Property `cx.docstore` | Class | Profile | Notes |
|---|---|---|---|
| `jpa` (default) | `JpaDocumentStore` | local, and any SQL deployment | table `document_store (tenant_id, collection, doc_key, doc_json, created_at)`, PK on all three; **joins the caller's transaction**, so a version snapshot commits or rolls back atomically with the article change. |
| `memory` | `InMemoryDocumentStore` | unit tests / throw-away demos | volatile, per JVM. |
| `mongo` | `MongoDocumentStore` | docker | Spring Data MongoDB `MongoTemplate`; connection string only from `CX_MONGO_URI` (no default - it carries credentials). **Not executed here (no Docker)**; `MongoDocumentStoreDockerIT` is the test. |

All three honour the same contract (`DocumentStoreTest` asserts it for the first two): upsert, get by key, list by key prefix ascending,
tenant scoping on every call, and `averageBy`.

### MongoDB specifics

One physical collection `cx_documents`; each document: `{ tenantId, collection, key, doc: {...payload...}, createdAt, updatedAt }`. The payload is nested
under `doc` so user-controlled field names can never collide with the envelope. **Every query starts with `tenantId`** - there is no tenant-less read path.

Index definitions (created idempotently at start-up by `MongoDocumentStore.ensureIndexes`):

1. `uq_tenant_collection_key` - unique `(tenantId, collection, key)`: point lookups, upsert identity, and the anchored-prefix range scan used for
   "all versions of article X" (`key` = `{articleId}:{8-digit version}`, so lexical order = version order).
2. `ix_tenant_collection_provider` - `(tenantId, collection, doc.provider)`: grouping / filtering AI metadata by provider.
3. `ix_tenant_collection_article_version` - `(tenantId, collection, doc.articleId, doc.version desc)`: "versions of article X, newest first" without a regex.

Aggregation example (`MongoDocumentStore.averageBy`): average AI confidence per provider for one tenant:

```
$match { tenantId: <t>, collection: "ai_suggestion_meta" }
$group { _id: "$doc.provider", avg: { $avg: "$doc.confidence" } }
$sort  { _id: 1 }
```

The JPA / in-memory stores fold the same result in the JVM over at most 500 documents.

## Consistency trade-offs (honest)

* **JPA store**: snapshot + article + audit row = one transaction. Strongest, simplest, and fine up to a large number of articles.
* **Mongo store**: Mongo is *not* in the PostgreSQL transaction. `KbService` writes the snapshot **last** inside the transaction and lets a failure abort
  (roll back) the article change; the inverse failure (snapshot written, commit then fails) leaves one orphan snapshot under a version number that is
  re-used by the next successful change and simply overwritten (upsert on the same key). No distributed transaction is used; if stricter guarantees
  were needed the answer would be to publish the snapshot through the **outbox** (the transactional outbox already exists) and have a consumer write it.
* AI metadata is *diagnostic*: a failure to store it is logged and counted (`cx.ai.meta.failed`) but never fails the user-visible suggestion.
* Version history is bounded to 500 snapshots per article when listed.

## Search

Portable path (what runs on H2 and PostgreSQL, and what is tested): the database narrows with `LIKE '%token%'` (case-insensitive, wildcards escaped) over
title / summary / body for any of at most 8 tokens, newest first, bounded to 300 candidates and always tenant + visibility scoped; the JVM then scores
them (`KbSearchScorer`: title 5, summary 3, body occurrences up to 5, whole-phrase bonus 10 / 5) and slices the requested page. Deterministic and
explainable; **not** a relevance model, and `totalElements` counts matches within the 300-candidate bound.

PostgreSQL-only extra (`db/migration-pg/V1010`, **not executed here**): a GIN `tsvector` index and a trigram index on `kb_article`. They exist for a native
`to_tsvector(...) @@ plainto_tsquery(...)` path; that path is **not wired in** because it could not be executed here - the portable search does not use these indexes.
Beyond roughly 10^4-10^5 articles per tenant the right move is that native path (or an external search engine), behind the same `KbService.list` signature.

## Caching

Cache-aside through `CacheService` (tenant-keyed, `t:{tenantId}:...`): category list (5 min) and the PUBLISHED+PUBLIC article list, per page (60 s, separate keys
for staff vs customer shape so ticket ids can never leak through a shared entry). Both are evicted **after the commit** of any write
(`TxHooks.afterCommit`). Search results are not cached (high-cardinality keys, low hit rate).
