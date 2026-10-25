# Part F. PostgreSQL and Hibernate

This part explains the relational layer of the multi-tenant CX and ITSM platform from first principles and then ties every idea to the real files in this repository. It is written for an interview: each section says what a thing is, why it exists, how it works inside, how this project uses it, and where the project stops.

> **Honesty box: what was and was not run.** No Docker was available while this project was built. The PostgreSQL-only migrations (`core-service/src/main/resources/db/migration-pg/V1001`, `V1002`, `V1003`, `V1010`, `V1011`), row-level security, the GIN and trigram indexes, and the Testcontainers test `PostgresRlsDockerIT` were **never executed**. Everything that was executed ran on **H2 in PostgreSQL compatibility mode** (profile `local`, URL `jdbc:h2:mem:cxcore;MODE=PostgreSQL;...`). Every `EXPLAIN ANALYZE` for PostgreSQL in this part is labelled **To-run command**. Only the plan evidence labelled **H2** was measured, and it comes from `PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md` section 7.1.

Two labels are used throughout:

- **Project-implemented behaviour**: code or SQL that exists in the repository. Where it was executed, the text says on which database.
- **Conceptual learning example**: a teaching example that is not part of the repository, written so you can run it yourself on a scratch PostgreSQL.

## F.1 Relational modeling and normalization

### What it is

Relational modeling means describing your domain as tables (relations), rows (facts) and constraints (rules the database itself enforces). Normalization is the discipline of storing each fact once, so that an update cannot make two copies disagree.

### Why it exists

Before relational databases, applications stored data in files or hierarchies and every program re-implemented integrity rules. Codd's idea was that the database owns the rules: a foreign key says "this reference must point at a real row" no matter which program writes.

### How it works internally

PostgreSQL stores each table as a heap of 8 kB pages. A row is a tuple. Constraints are checked by the executor at statement end (or at commit when declared `DEFERRABLE`). A foreign key is enforced by internal triggers that look up the referenced key through its unique index. This is why a foreign key needs a unique or primary key on the referenced side, and why an index on the referencing column is often wise: deleting a parent row has to find child rows.

The normal forms in one paragraph each:

- **First normal form (1NF)**: every column holds one atomic value and rows are identifiable. A column containing `"a,b,c"` as a list breaks it.
- **Second normal form (2NF)**: with a composite key, no non-key column depends on only part of the key.
- **Third normal form (3NF)**: no non-key column depends on another non-key column (no transitive dependency). Example: storing `category_name` next to `category_id` in `ticket` would break it, because the name depends on the id and not on the ticket.
- **Boyce-Codd normal form (BCNF)**: every determinant is a candidate key. In practice 3NF plus unique constraints gets you there.

Denormalization is the deliberate, documented breaking of those rules for read speed or simplicity.

### Why this project chose it

The platform is an operational system of record for tickets, users, SLA state and audit. It needs integrity (a ticket cannot point to another tenant's user), transactions (ticket plus audit plus outbox row commit together) and flexible filtering. That is the textbook case for a relational database. Document storage is used only for derived, write-once data (Part G).

### How THIS PROJECT uses it

**Project-implemented behaviour.** The schema is owned by Flyway scripts in `core-service/src/main/resources/db/migration`. The entity-relationship diagram is `docs/diagrams/png/12-database-erd.png`.

![Figure: Database entity-relationship diagram of core-service](docs/diagrams/png/12-database-erd.png)

Key tables and what they model:

| Table (migration) | Role | Notable design decision |
|---|---|---|
| `organization` (V1) | Tenant root; its `id` is the `tid` claim in the JWT | `slug` unique and forced lower case by `ck_organization_slug_lower` |
| `app_user` (V2), `ticket_category` (V5) | Tenant-owned reference data | `unique (tenant_id, id)` exists so other tables can reference the pair |
| `ticket` (V7) | The central aggregate | Composite tenant foreign keys, check constraints for every enum, `version` column |
| `ticket_counter` (V6) | Per-tenant ticket numbers | One row per tenant, updated atomically |
| `outbox_event` (V14) | Transactional outbox | Status machine as a check constraint |
| `idempotency_key` (V11) | Replay of ticket creation | `unique (tenant_id, user_id, idem_key)` |
| `kb_article`, `kb_category`, `kb_article_ticket` (V20) | Knowledge base | Same composite-FK pattern |
| `document_store` (V21) | JSON documents, JPA flavour | PK on `(tenant_id, collection, doc_key)` |

Deliberate denormalizations, each with a reason you can quote:

1. `ticket.tags` is a single string such as `,billing,urgent,` (V7 default is `','`). It breaks 1NF on purpose: the `Ticket` entity comment says the leading and trailing comma make `LIKE '%,a,%'` exact and avoid a join table, which would add one more `IN` query per page. The cost is that you cannot put a foreign key or a normal B-tree index on a single tag.
2. `ticket.sla_status`, `escalation_level`, `breach_notified` are derived state cached on the row so the SLA job can scan with `ix_ticket_sla_scan`.
3. Display names are not stored on the ticket. They are resolved by one batched query per page in `TicketMapper.toDtos`. That is the opposite choice: stay normalized and batch the reads.

### Implemented versus not implemented

- Implemented: 3NF core schema, check constraints for enumerations, composite foreign keys, surrogate UUID keys assigned by the application.
- Not implemented: table partitioning (for example by tenant or by month for `audit_event` and `outbox_event`), a tags join table, and soft-delete columns. The outbox is cleaned with `OutboxPurgeJob` instead of partition drops.

### Performance, security, mistakes

- Performance: wide rows (`description varchar(8000)`) slow sequential scans; PostgreSQL moves values over about 2 kB to TOAST storage automatically, so list queries that do `select *` still pay to detoast only when the column is actually read. The list endpoint maps the entity, which does read `description`.
- Security: the schema is the last line of defence. A forged event or a buggy query still cannot write a cross-tenant reference because of the composite keys (next section).
- Common mistakes: using `varchar` lengths as validation instead of constraints; storing money or counters in floating point; storing enumerations as integers (the project stores `EnumType.STRING` plus a `check`); adding a boolean "deleted" column and forgetting it in unique constraints.

### Alternatives and trade-offs

A fully normalized tags table gives clean joins and per-tag indexes but costs a join or an extra batched query. An `text[]` column with a GIN index (PostgreSQL only) gives fast containment queries (`tags @> '{billing}'`) but is not portable to H2, and the project's rule is that one set of migrations runs on both. That portability rule is the real reason for the comma string.

### Interview Q&A

**Q1. When would you denormalize, and how do you keep it safe?**
Denormalize when a measured read path pays for a join or aggregate on every request and the data changes rarely relative to reads. Keep it safe by having one writer path (here `TicketService` and the SLA job), by documenting the invariant next to the column, and by adding a constraint where possible (the `ck_ticket_sla_status` check). In this project `sla_status` is derived, but it is written only by the SLA calculation, and the SLA job re-checks it every 60 seconds.

**Q2. Why UUID primary keys instead of `bigserial`?**
UUIDs let the application assign the id before insert (the `Ticket` entity has a plain `@Id private UUID id`, no generator), which makes idempotent replays and outbox event ids easy and avoids a round trip. The cost is a 16-byte key, random insertion order into the primary-key B-tree (more page splits and cache misses than a sequential key), and bigger secondary indexes because every index entry carries the key. UUIDv7 (time-ordered) reduces the splitting problem. The project uses random UUIDs and has not measured the effect.

**Q3. What is the difference between a surrogate and a natural key, and where does the project use each?**
Surrogate: `ticket.id` (UUID). Natural keys that are still enforced as unique: `(tenant_id, number)` for the human ticket number and `(tenant_id, slug)` for KB articles. The pattern is: surrogate for references, natural unique constraints for what humans type.

**Q4. A column holds comma-separated values. Is that always wrong?**
It violates 1NF, so it is a trade-off, not a sin. It is acceptable when you never need to join on the elements, never need per-element constraints and the access path is a substring match with delimiters. The moment you need "all tickets with tag X" at scale, move to a join table or an array with a GIN index.

## F.2 Constraints and composite tenant foreign keys

### What and why

Constraints are declarative rules: `NOT NULL`, `UNIQUE`, `PRIMARY KEY`, `FOREIGN KEY`, `CHECK`, and exclusion constraints. They exist so that integrity does not depend on every code path remembering to validate. In a multi-tenant system the most important integrity rule is "a row may only reference rows of its own tenant", and a plain foreign key `ticket.requester_id -> app_user.id` does not enforce that: it would happily accept a user id from another tenant.

### How it works

A foreign key can reference any unique key, including a composite one. If `app_user` has `unique (tenant_id, id)` then `ticket` can declare `foreign key (tenant_id, requester_id) references app_user (tenant_id, id)`. The pair must exist as a pair, so the ticket's `tenant_id` must equal the user's `tenant_id`. The redundancy of `unique (tenant_id, id)` on a table whose `id` is already unique looks wasteful but it is exactly what the referenced side needs.

### How THIS PROJECT uses it

**Project-implemented behaviour** (`V7__ticket.sql`, executed on H2 by every test; the same file is portable SQL for PostgreSQL):

```sql
constraint fk_ticket_requester foreign key (tenant_id, requester_id) references app_user (tenant_id, id),
constraint fk_ticket_assignee  foreign key (tenant_id, assignee_id)  references app_user (tenant_id, id),
constraint fk_ticket_category  foreign key (tenant_id, category_id)  references ticket_category (tenant_id, id),
constraint uq_ticket_tenant_number unique (tenant_id, number),
constraint uq_ticket_tenant_id     unique (tenant_id, id),
constraint ck_ticket_status check (status in ('NEW','OPEN','PENDING','ON_HOLD','RESOLVED','CLOSED','REOPENED')),
```

Two details worth knowing for an interview:

- With the default `MATCH SIMPLE` semantics, a composite foreign key is not checked when any of its columns is null. `assignee_id` is nullable, so an unassigned ticket passes. `tenant_id` is `NOT NULL`, so a non-null `assignee_id` is always checked together with the tenant. This is correct, but it is a common trap: if the tenant column were nullable the check would silently vanish.
- `kb_article_ticket` (V20) references both `kb_article (tenant_id, id)` and `ticket (tenant_id, id)`, so a link between an article and a ticket of different tenants is impossible at the database level.

A test or a manual experiment shows the effect. **Conceptual learning example** (run on any PostgreSQL or on H2):

```sql
insert into organization (id, slug, name, created_at) values ('00000000-0000-0000-0000-00000000000a','a','A', now());
insert into organization (id, slug, name, created_at) values ('00000000-0000-0000-0000-00000000000b','b','B', now());
-- a user of tenant B
insert into app_user (id, tenant_id, email, display_name, password_hash, roles, active, created_at)
values ('11111111-1111-1111-1111-111111111111','00000000-0000-0000-0000-00000000000b','u@b.test','U','x',',AGENT,',true, now());
-- a ticket of tenant A pointing at tenant B's user must fail with a foreign key violation (SQLSTATE 23503)
```

### Implemented versus not implemented

Implemented: composite tenant FKs for requester, assignee, category, KB author, KB links, plus check constraints for status, priority, severity, SLA status. Not implemented: exclusion constraints, deferrable constraints, or database-side state machine validation (valid status transitions are enforced in Java by the ticket state machine, tested by `TicketStateMachineTest`). A `check` constraint can list allowed values but cannot express "from RESOLVED you may only go to CLOSED or REOPENED"; that needs a trigger.

### Performance, security, mistakes

- Performance: every insert into `ticket` performs up to three foreign-key lookups through the unique indexes on `app_user (tenant_id, id)` and `ticket_category (tenant_id, id)`. They are cheap point lookups, but they are why bulk loads sometimes disable constraints.
- Security: composite FKs are a structural defence against an insecure direct object reference that sets a foreign id from user input. They complement, not replace, tenant filters and RLS.
- Common mistakes: forgetting `unique (tenant_id, id)` on the parent (the migration then fails with "there is no unique constraint matching given keys"); using `ON DELETE CASCADE` casually in a system that needs an audit trail; adding a check constraint to a large table without `NOT VALID` then `VALIDATE CONSTRAINT`, which holds an `ACCESS EXCLUSIVE` lock during the scan.
- Debugging: in PostgreSQL, `\d ticket` in `psql` lists constraints; the error text includes the constraint name, which is why the project names every constraint explicitly (`fk_ticket_requester`). The `GlobalExceptionHandler` maps data-integrity violations to an API error.

### Alternatives

Application-only checks (cheaper to write, easy to bypass), triggers (flexible, hard to reason about and slower), or one database per tenant (strongest isolation, high operational cost). Schema-per-tenant sits between. The project chose a shared schema with `tenant_id` everywhere, because it scales to many small tenants with one migration path.

### Interview Q&A

**Q1. Why add `unique (tenant_id, id)` to a table whose `id` is already the primary key?**
Because a foreign key must reference a unique constraint on exactly the referenced columns. The pair `(tenant_id, id)` is logically unique (since `id` alone is), and declaring it lets child tables bind the tenant into the reference.

**Q2. Does a composite foreign key protect you if `tenant_id` can be null?**
No. Under `MATCH SIMPLE`, a row with any null column in the key is not checked. Keep the tenant column `NOT NULL`, or use `MATCH FULL`.

**Q3. Should the database or the application enforce the ticket state machine?**
Both have a role. The application enforces transitions with good error messages; the database enforces what is cheap and absolute (allowed values, ranges). A trigger-based state machine is possible but hides logic and complicates migrations.

## F.3 Index design: B-tree, selectivity, composite, partial, GIN

### What an index is

An index is a separate, sorted data structure that maps column values to the location of rows, so the database can avoid reading the whole table. It exists because a sequential scan is O(n) and an index lookup is O(log n) plus the rows returned.

### How a B-tree works internally

The default PostgreSQL index is a B-tree (technically a B+tree variant). Pages are 8 kB. The root page points to internal pages, which point to leaf pages. Leaf pages hold index entries sorted by key, each with a pointer (the tuple id, `ctid`) to the heap row, and neighbouring leaves are linked so range scans walk sideways. Lookups cost the tree height, typically 3 to 4 page reads even for hundreds of millions of rows.

![Figure: How a composite B-tree index serves tenant_id equality, status equality and an ordered range](docs/diagrams/png/19-btree-composite-index.png)

Rules that follow from this structure:

1. **Leftmost prefix.** An index on `(tenant_id, status, created_at)` can serve conditions on `tenant_id`, or `tenant_id, status`, or all three, but not `status` alone, because the entries are sorted by `tenant_id` first. The H2 evidence in the performance guide shows this: Q2 (`status = 'OPEN'` without the tenant predicate) examined all 75 001 rows.
2. **Equality columns first, then the range or sort column.** After equality on the leading columns, the entries are already in `created_at` order, so `ORDER BY created_at DESC LIMIT 20` can stop after 20 entries with no sort.
3. **Selectivity.** Selectivity is the fraction of rows a predicate keeps. A predicate that keeps 1 percent is selective; `status = 'OPEN'` on a table where most rows are open is not. The planner estimates it from statistics collected by `ANALYZE` (`pg_stats`, most common values, histograms). When it expects to read a large fraction of the table, a sequential scan is cheaper than random index lookups, and PostgreSQL correctly ignores the index.
4. **Cost of indexes.** Each index slows inserts and non-HOT updates, consumes memory and disk, and must be vacuumed.
5. **Index-only scans.** If the index contains every column the query needs and the visibility map says the pages are all-visible, PostgreSQL can skip the heap. `INCLUDE (col)` adds non-key payload columns.

### Variants and when to use them

| Type | Good for | Not good for | In this project |
|---|---|---|---|
| B-tree | equality, ranges, sorting, prefix `LIKE 'abc%'` (with `text_pattern_ops` or C collation) | `LIKE '%abc%'`, array containment | all indexes in V15, V4, V14, V20 |
| Composite B-tree | multi-column filters with sort | queries missing the leading column | `ix_ticket_tenant_status_prio_created` |
| Partial B-tree | a hot subset of rows | queries that do not repeat the predicate | `ix_ticket_open_queue` (V1001, not executed) |
| Expression index | `lower(title)` lookups | queries that use a different expression | `ix_ticket_title_trgm` uses `lower(title)` |
| GIN | many keys per row: full text, arrays, JSONB, trigram | tiny tables, write-heavy columns (slower inserts) | `ix_ticket_fts`, `ix_kb_article_fts` (not executed) |
| GiST, BRIN, hash | geometry and ranges; huge append-only tables; equality only | | not used |

### How THIS PROJECT uses it

**Project-implemented behaviour: portable indexes (V15, ran on H2).** Every index leads with `tenant_id`, matching the tenant predicate that the Hibernate filter and every repository method add:

```sql
create index ix_ticket_tenant_status_prio_created on ticket (tenant_id, status, priority, created_at);
create index ix_ticket_tenant_created             on ticket (tenant_id, created_at);
create index ix_ticket_tenant_assignee_status     on ticket (tenant_id, assignee_id, status);
create index ix_ticket_tenant_requester_created   on ticket (tenant_id, requester_id, created_at);
create index ix_ticket_tenant_sla                 on ticket (tenant_id, sla_status);
create index ix_ticket_sla_scan                   on ticket (tenant_id, status, sla_checked_at);
```

The outbox has `ix_outbox_poll (status, next_attempt_at)` and the audit log has three tenant and time indexes.

**Project-implemented behaviour: PostgreSQL-only indexes (V1001, V1010). NOT EXECUTED.** Flyway applies them only under the docker profile (`flyway.locations: classpath:db/migration,classpath:db/migration-pg`).

```sql
create index ix_ticket_open_queue on ticket (tenant_id, priority, created_at)
    where status not in ('RESOLVED', 'CLOSED');
create index ix_ticket_fts on ticket
    using gin (to_tsvector('english', coalesce(title,'') || ' ' || coalesce(description,'')));
create extension if not exists pg_trgm;
create index ix_ticket_title_trgm on ticket using gin (lower(title) gin_trgm_ops);
```

What they are for:

- The partial index stores only open tickets. Closed tickets are the large majority over time, so the index stays small and hot queues, dashboards and the SLA scan benefit. The query must contain a predicate that implies the index predicate (`status not in ('RESOLVED','CLOSED')`) for the planner to use it. A JPA `status = ?` bind parameter does not imply it, which is a common surprise (see mistakes).
- The `tsvector` GIN index serves `to_tsvector('english', ...) @@ plainto_tsquery('english', ?)`. A GIN index is an inverted index: each lexeme points to the list of rows containing it. **The application does not call this native query.** `docs/kb-storage-notes.md` says plainly that the portable search (`LIKE`) is what runs and the native path is "not wired in" because it could not be executed.
- The trigram index supports `lower(title) LIKE '%jam%'`. The performance guide warns that the application's search also ORs a condition on `lower(description)`, which this index does not cover, so a plan may still scan.

**H2 evidence (measured, from the performance guide section 7.1).** Three tenants with 50 000, 20 000 and 5 000 tickets:

| Query | Index H2 chose | Rows examined (H2) | Lesson |
|---|---|---:|---|
| List page, `tenant_id`, `status = 'OPEN'`, order by `created_at desc, id desc`, limit 20 | `ix_ticket_sla_scan` | 14 287 | filter uses an index, but the sort is not provided: all OPEN rows are read and sorted |
| Same on a copy without indexes | table scan | 75 001 | 5.2 times more rows |
| `status = 'OPEN'` without tenant predicate | table scan | 75 001 | leading-column rule |
| `lower(title) like '%jammed ticket 4999%'` | tenant index | 50 001 | a leading wildcard cannot use a B-tree |
| Candidate index `(tenant_id, status, created_at desc, id desc)` | H2 kept the old index | 14 287 | H2 did not pick it; says nothing about PostgreSQL |

Only these row counts are evidence. No speed-up numbers exist for PostgreSQL.

### Implemented versus not implemented

Implemented: tenant-led composite indexes. Written but never executed: partial, GIN and trigram indexes. Not implemented: the candidate index `(tenant_id, status, created_at desc, id desc)` (proposed in the performance guide as a test), covering indexes with `INCLUDE`, index-only scans for list queries (the list loads the whole entity), `CREATE INDEX CONCURRENTLY` in the migrations (Flyway runs each script in a transaction by default, and `CONCURRENTLY` cannot run inside one; you need a non-transactional script).

### Performance implications

- The ticket table has eight secondary indexes (V15) plus the PK and two unique constraints. Every ticket insert updates up to 11 index structures. That is acceptable for a ticketing workload that is read-heavy, but it explains why write-heavy tables such as `outbox_event` keep only two indexes.
- An update to an indexed column creates a new index entry and prevents a HOT (heap-only tuple) update. `ticket.status`, `assignee_id` and `sla_*` columns are all indexed, so most ticket updates are not HOT. The project's `@DynamicUpdate` and bulk SLA update reduce the number of changed columns but cannot avoid this.
- Low-cardinality leading columns are fine in a composite index when they follow the tenant, because the tenant narrows first.

### Security considerations

Indexes can leak data through error messages and timing in unique constraints (a unique violation reveals that a value exists). The `uq_ticket_tenant_number` constraint includes `tenant_id`, so a collision can only happen inside the tenant. Do not put secrets in expression indexes.

### Common mistakes

1. Indexing every column "just in case".
2. Putting the range column before the equality columns.
3. Writing a query whose predicate does not imply a partial index predicate: `where status = $1` cannot use `where status not in (...)`, because the planner does not know the parameter value in a generic plan.
4. Wrapping the indexed column in a function (`lower(title) = ?` against an index on `title`).
5. Leaving statistics stale after a bulk load (run `ANALYZE`).
6. Creating indexes on a busy table without `CONCURRENTLY`.

### Debugging techniques

Use `EXPLAIN (ANALYZE, BUFFERS)`. Compare estimated rows with actual rows: a large gap means stale or insufficient statistics. Use `pg_stat_user_indexes` to find unused indexes, `pg_stat_user_tables` for sequential-scan counts and dead tuples, and `pg_indexes` to confirm an index exists. In H2 the equivalent is `EXPLAIN ANALYZE`, which is what `perf/sql/H2Explain.java` printed.

### Alternatives and trade-offs

Hash indexes (equality only, now crash-safe, rarely worth it), BRIN (tiny, good for naturally ordered append-only data such as `audit_event.occurred_at`, a plausible future choice), and external search (OpenSearch) when relevance ranking, typo tolerance and facets matter more than transactional consistency.

### Interview Q&A

**Q1. You have `(tenant_id, status, priority, created_at)`. Which of these queries use it fully: (a) tenant and status, (b) tenant and priority, (c) status only, (d) tenant, status, order by created_at?**
(a) yes, uses the first two columns. (b) uses only `tenant_id` for the seek and filters priority inside the index range, because `status` is skipped (newer PostgreSQL versions can do a skip scan in limited cases, but do not rely on it). (c) cannot seek. (d) uses tenant and status for the seek, but `priority` sits between status and `created_at`, so the order by `created_at` is not provided: PostgreSQL has to sort. That mismatch is exactly why the H2 plan sorted.

**Q2. When does PostgreSQL prefer a sequential scan even though an index exists?**
When the planner estimates that a large fraction of the table matches (roughly above a few percent for random I/O costs), when the table is tiny, or when statistics are wrong. Fix with `ANALYZE`, better statistics targets, or a different index, not with disabling seq scans in production.

**Q3. What is a partial index and what does the query need to use it?**
An index over a subset defined by a `WHERE`. The query must have a predicate that logically implies it, written so the planner can prove it from the SQL text. With prepared statements and a parameter in the predicate the proof can fail.

**Q4. Why does `LIKE '%abc%'` not use a B-tree and what are the options?**
A B-tree is sorted by the beginning of the string, so an unknown prefix gives no range to seek. Options are a trigram GIN or GiST index (`pg_trgm`), full-text search with `tsvector`, or an external search engine. This project wrote the trigram and `tsvector` indexes but does not yet call the native queries.

**Q5. How would you add an index to a 200 million row production table safely?**
`CREATE INDEX CONCURRENTLY` outside a transaction, check `pg_index.indisvalid` afterwards (a failed concurrent build leaves an invalid index to drop), do it in a low-traffic window, and watch replication lag and I/O. With Flyway, use a script with `-- flyway:executeInTransaction=false` (or the equivalent setting).

## F.4 Query plans and how to read them

### What and why

A query plan is the tree of operations the executor will run. SQL says what you want; the planner decides how, using cost estimates from table statistics. You need to read plans because the same SQL can be 1 ms or 10 s depending on the plan.

### How it works

The planner enumerates access paths (sequential scan, index scan, bitmap scan, index-only scan) and join strategies (nested loop, hash join, merge join), assigns cost units from `seq_page_cost`, `random_page_cost`, `cpu_tuple_cost` and row estimates, and picks the cheapest. `EXPLAIN` shows the estimate. `EXPLAIN ANALYZE` actually runs the statement and adds real row counts and times. `BUFFERS` adds page counts from shared buffers (hit) versus disk or OS cache (read).

Nodes you will see most:

| Node | Meaning | Healthy when |
|---|---|---|
| Seq Scan | read every page | table is small or most rows match |
| Index Scan | walk the index, fetch heap rows one by one | few rows match |
| Bitmap Index Scan then Bitmap Heap Scan | collect row locations, then read heap pages in order | medium selectivity, or combining several indexes (GIN uses this) |
| Index Only Scan | answer from the index | heap fetches are near zero |
| Sort, top-N heapsort | explicit sort | input is small, or limit is small |
| Nested Loop, Hash Join, Merge Join | join strategies | estimates match actual rows |

### How THIS PROJECT uses it

**H2 (measured).** `perf/sql/H2Explain.java` loads the real Flyway migrations V1 to V15 into H2 and prints the chosen index and rows examined. The table in F.3 is the result. Wall-clock times were discarded because at that size they were dominated by parsing and H2's result cache.

**To-run command (PostgreSQL 16, NOT EXECUTED).** From the performance guide section 7.2, run as the application role with a tenant bound so RLS applies exactly as in the app:

```sql
BEGIN;
SELECT set_config('app.tenant_id', '<tenant-uuid>', true);   -- same call TenantAwareJpaTransactionManager makes
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT * FROM ticket
WHERE tenant_id = '<tenant-uuid>' AND status = 'OPEN'
ORDER BY created_at DESC, id DESC LIMIT 20;
ROLLBACK;
```

What to look for, and what it means:

1. `Index Scan` or `Bitmap Heap Scan` on a `tenant_id`-led index, not `Seq Scan`.
2. A `Sort` node with `Sort Method: top-N heapsort` above an index scan whose `actual rows` is far larger than 20. That is the signature of the H2 result: the index finds the OPEN rows but does not deliver the order.
3. `Buffers: shared hit` versus `read`: reads mean cold cache or too little memory.
4. The RLS filter `(tenant_id = cx_current_tenant())` appears in `Filter` or `Index Cond`. Because the function is declared `STABLE`, the planner can evaluate it once per statement rather than per row. If it were `VOLATILE` it could block index use.
5. Estimated rows versus actual rows within roughly a factor of 10.

The candidate index to test, **not part of the migrations**:

```sql
CREATE INDEX CONCURRENTLY ix_ticket_tenant_status_created
    ON ticket (tenant_id, status, created_at DESC, id DESC);
```

Re-run the plan and compare `Execution Time` and buffers. If it works, the sort disappears and `actual rows` on the index node falls to about 20.

A **Conceptual learning example** you can run on any scratch PostgreSQL to see leading-column and selectivity behaviour:

```sql
create table t (tenant int, status text, created timestamptz default now(), payload text);
insert into t select (random()*50)::int, (array['NEW','OPEN','CLOSED'])[1+(random()*2)::int], now() - (random()*1000||' minutes')::interval, md5(random()::text)
from generate_series(1,500000);
create index ix_t on t (tenant, status, created);
analyze t;
explain (analyze, buffers) select * from t where tenant = 7 and status = 'OPEN' order by created desc limit 20;  -- index, no sort
explain (analyze, buffers) select * from t where status = 'OPEN';                                             -- seq scan: leading column missing
```

### Implemented versus not implemented

Implemented and executed: the H2 plan study. Not executed: every PostgreSQL plan. Not implemented: `auto_explain`, slow-query logging configuration, `pg_stat_statements` (all are only described as steps in the performance guide).

### Performance, security, mistakes, debugging

- `EXPLAIN ANALYZE` on `INSERT` or `UPDATE` really executes it. Wrap it in `BEGIN; ... ROLLBACK;`.
- Plans for prepared statements can switch between custom and generic plans after five executions (`plan_cache_mode`). A plan that is perfect for a rare tenant can be terrible for a huge tenant. This matters here because tenant sizes are skewed.
- Do not paste real tenant UUIDs or customer data into shared bug reports.
- Debugging tools: `pg_stat_statements` for the top statements by total time, `auto_explain` to log plans of slow statements, `pg_stat_activity` for running queries, `pg_locks` for blockers.

### Alternatives

Query hints are not part of core PostgreSQL (the `pg_hint_plan` extension exists). The usual levers are statistics (`ALTER TABLE ... ALTER COLUMN ... SET STATISTICS`), extended statistics for correlated columns (`CREATE STATISTICS`), and rewriting the query.

### Interview Q&A

**Q1. An index exists, the table was just bulk loaded, and PostgreSQL does a sequential scan. Why?**
The statistics are stale or absent, so the planner thinks the table is tiny or the predicate unselective. Run `ANALYZE`. Autovacuum would eventually do it, but not instantly after a bulk load.

**Q2. What does "actual rows=20 loops=1" with "Rows Removed by Filter: 40000" tell you?**
The scan produced 40 020 rows internally and discarded 40 000. That is the offset or filter cost. It means the index does not match the predicate or the offset is large.

**Q3. Why can the same query be fast for one tenant and slow for another?**
Skewed data. Statistics are global per column, so a predicate on `tenant_id` has very different selectivity for a tenant with 50 rows and a tenant with 5 million. Generic cached plans make this worse. Mitigations: per-tenant partitioning, `plan_cache_mode = force_custom_plan` for the hot statements, or extended statistics.

## F.5 Pagination: offset versus keyset

### What and why

Paging splits a large result into pages. Offset paging (`LIMIT 20 OFFSET 40000`) is simple but makes the database produce and discard 40 000 rows. Keyset paging (also called seek or cursor paging) remembers the last row's sort key and asks for rows after it: `WHERE (created_at, id) < (:lastCreated, :lastId) ORDER BY created_at DESC, id DESC LIMIT 20`.

### How it works

With an index on `(tenant_id, created_at, id)` the keyset predicate is a range seek straight to the position, then 20 entries are read. Cost is independent of depth. The `id` tiebreaker makes the order total, otherwise two rows with the same timestamp could be skipped or repeated. Row-value comparison `(a, b) < (x, y)` is supported by PostgreSQL and can use the composite index.

Trade-offs: keyset cannot jump to page 500 directly, cannot show "page 3 of 50" cheaply, and requires a stable, unique sort key. Inserts during paging do not shift pages (a real advantage over offset, where a new row causes a duplicate on the next page).

### How THIS PROJECT uses it

**Project-implemented behaviour.** The ticket list endpoint uses offset paging: `TicketService` builds `PageRequest.of(page, size)` (size capped at 100 by `PageParams`) and Spring Data issues a page query plus a count query. This is why the N+1 guard counts "page query, count query" among its six statements.

**Keyset is not implemented in the API.** The performance guide measured it on H2 only (Q4 versus Q4b): offset 40 000 examined 50 001 rows, keyset at the same position examined 10 001 (H2's row-value handling is limited, so it still read more than 20).

**To-run command (NOT EXECUTED on PostgreSQL):**

```sql
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM ticket WHERE tenant_id = '<tenant-uuid>'
ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 40000;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM ticket WHERE tenant_id = '<tenant-uuid>'
AND (created_at, id) < ('<last_created_at>', '<last_id>') ORDER BY created_at DESC, id DESC LIMIT 20;
```

Look for the index node's `actual rows`: about 40 020 for the offset query and about 20 for keyset.

### Performance, security, mistakes

- The count query for `totalElements` is another full tenant-range scan on large tenants. Many systems drop exact counts or cap them.
- Offset beyond a bound is a denial-of-service lever. The API caps the page size, not the page number, so a client can request a very deep page.
- Mistakes: keyset on a non-unique key without a tiebreaker; keyset on a nullable column; sorting by a column the user chooses and having no index for it.
- Security: an opaque cursor (base64 of the last key) should be signed or at least validated, and always combined with the tenant predicate.

### Alternatives

Keyset, offset with a hard cap, or "load more" with cursor tokens. For analytics lists, precomputed rollups.

### Interview Q&A

**Q1. Why is offset paging O(offset)?**
There is no way to know where row 40 000 starts without walking the first 40 000 index entries (or heap rows). The B-tree has no positional index.

**Q2. How do you sort by a column that is not unique with keyset paging?**
Add a unique tiebreaker as the last sort key, and include both in the index and in the row-value comparison.

**Q3. What happens to offset pages when rows are inserted while the user pages?**
Items shift: a row can appear on two pages or be skipped. Keyset pages are anchored to a key, so they stay stable.

## F.6 Transactions, isolation levels and MVCC

### What a transaction is

A transaction groups statements so they succeed or fail together (atomicity), keep constraints true (consistency), do not see each other's partial work (isolation) and survive a crash after commit (durability). ACID is a property set, and isolation is configurable because stronger isolation costs concurrency.

### How PostgreSQL implements it: MVCC

PostgreSQL uses multi-version concurrency control. An `UPDATE` does not overwrite a row. It writes a new row version (tuple) and marks the old one dead for newer snapshots, using hidden system columns `xmin` (creating transaction id) and `xmax` (deleting or locking transaction id). A reader takes a snapshot (the list of transactions that were committed or in progress at that moment) and sees only versions that are visible in that snapshot. Therefore **readers never block writers and writers never block readers**. Only two writers on the same row block each other.

Consequences:

- Old versions accumulate until `VACUUM` removes those no snapshot can see. Long-running transactions hold back the cleanup horizon and cause table bloat.
- Index entries point to specific versions, so updates of indexed columns add index entries (the HOT update discussion in F.3).
- Transaction id wraparound is prevented by autovacuum freezing old tuples.

![Figure: Two transactions seeing different row versions under READ COMMITTED and REPEATABLE READ](docs/diagrams/png/20-mvcc-isolation.png)

### Isolation levels

| Level | PostgreSQL behaviour | Anomalies allowed | Notes |
|---|---|---|---|
| READ UNCOMMITTED | treated as READ COMMITTED | none beyond RC | PostgreSQL never shows dirty reads |
| READ COMMITTED (default) | new snapshot per statement | non-repeatable read, phantom read, lost update (unless you lock or use a version) | `UPDATE` re-checks the row after waiting for a conflicting writer |
| REPEATABLE READ | one snapshot per transaction | write skew; serialization failure `40001` on concurrent update of the same row | stronger than the SQL standard requires (no phantoms) |
| SERIALIZABLE | snapshot plus predicate tracking (SSI) | none, but transactions may abort with `40001` | the application must retry |

Key anomalies:

- **Non-repeatable read**: reading the same row twice gives different values because another transaction committed in between.
- **Phantom**: a repeated range query returns new rows.
- **Lost update**: two transactions read a value, both compute and write; the second overwrites the first. Under READ COMMITTED this happens with read-modify-write in application code.
- **Write skew**: two transactions each read a set of rows, each updates a different row based on what it read, and together they break an invariant (two doctors both going off call). Only SERIALIZABLE (or explicit locks) prevents it.

### How THIS PROJECT uses it

**Project-implemented behaviour.** The code never sets an isolation level (a search of `core-service/src/main` finds no `isolation=` and no `Isolation`), so every transaction runs at the database default: READ COMMITTED on PostgreSQL, and READ COMMITTED on H2 as used in tests. Concurrency safety does not depend on a high isolation level. It comes from three narrower tools, each covered later:

1. optimistic locking with `@Version` on `Ticket` and `KbArticle`,
2. single-statement atomic updates (`update ticket_counter set next_number = next_number + 1`, the outbox `claim` compare-and-set, the AI suggestion `UPDATE ... WHERE status = 'PENDING_REVIEW'`),
3. unique constraints as the arbiter (idempotency keys, `notif_processed_event` primary key).

Transaction boundaries: `OutboxService.publish` is `Propagation.MANDATORY`, so an event can only be written inside an existing business transaction. The relay and the ticket number allocator use `TransactionTemplate` with their own short transactions, deliberately not nested in a business transaction (see F.8 and F.9). Cache eviction runs after commit through `TxHooks.afterCommit`, so a rolled-back write never evicts and a committed write never leaves a window where the old value is cached after the evict (a reader could still repopulate a stale value in that window, which is the classic race discussed in Part G).

`spring.jpa.open-in-view: false` means no database transaction or session spans the HTTP view rendering. That keeps connection hold time short.

**Executed evidence.** `OutboxTest.eventIsAtomicWithTheBusinessTransaction` (H2) proves that a rollback leaves no outbox row and a commit leaves exactly one `PENDING` row. `TenancyTest` creates 10 tickets in parallel and expects numbers 1 to 10.

### Implemented versus not implemented

Not implemented: SERIALIZABLE anywhere, retry loops for `40001` errors, explicit savepoints, advisory locks, `statement_timeout`, `lock_timeout` and `idle_in_transaction_session_timeout` (recommended in the performance guide as a prevention step, not applied).

### Performance, security, mistakes, debugging

- Long transactions are the main enemy: they hold a pooled connection, hold row locks, and block vacuum. The performance guide measured `hikaricp_connections_usage_seconds_max = 6.077 s` during the create tail (H2) and traced it to the per-tenant counter lock.
- External calls inside a transaction (HTTP, message broker publish) are a classic mistake. The project avoids it: the relay publishes outside any database transaction, and the AI provider call is outside any transaction.
- `@Transactional` on a private method or a self-invocation is silently ignored because Spring uses proxies. `readOnly = true` is a hint that allows Hibernate to skip dirty checking and lets some drivers route to replicas.
- Debugging on PostgreSQL (**To-run commands**):

```sql
SELECT pid, now() - xact_start AS xact_age, state, wait_event_type, wait_event, left(query,100)
FROM pg_stat_activity WHERE xact_start IS NOT NULL ORDER BY xact_age DESC LIMIT 10;
SELECT * FROM pg_locks WHERE NOT granted;
SELECT pg_blocking_pids(<pid>);
```

### Alternatives

Stronger isolation with retries (SERIALIZABLE), explicit pessimistic locks for hot rows, or distributing writes with a queue so one consumer owns a key. The project's choice of weak isolation plus precise tools is common and efficient, provided each invariant has a named mechanism.

### Interview Q&A

**Q1. Explain MVCC in one minute and say what a long-running transaction does to it.**
Each update creates a new tuple version; readers use snapshots; old versions are removed by vacuum when no snapshot can see them. A long transaction keeps an old snapshot alive, so vacuum cannot reclaim anything newer than it, tables and indexes bloat, and queries slow down.

**Q2. Two users edit the same ticket at the same time. Under READ COMMITTED, what happens without any locking, and what does this project do?**
Without a version check, the second `UPDATE` waits for the first to commit, then applies on top, silently overwriting. The project adds `@Version`: the `UPDATE ... WHERE id = ? AND version = ?` of the second user matches zero rows, Hibernate throws `ObjectOptimisticLockingFailureException`, and `GlobalExceptionHandler` returns HTTP 409 with `TICKET_VERSION_CONFLICT`.

**Q3. When is REPEATABLE READ or SERIALIZABLE worth it?**
When an invariant spans several rows that you read and then write (write skew), for example "an agent may have at most N open tickets" enforced by read-then-insert. Then either use SERIALIZABLE with retry, lock a parent row, or encode the rule in a constraint.

**Q4. Is `READ COMMITTED` in PostgreSQL the same as in Oracle or SQL Server?**
Similar guarantees, different mechanics. SQL Server by default uses locks for reads unless `READ_COMMITTED_SNAPSHOT` is on. PostgreSQL always uses MVCC snapshots, so reads never block.

## F.7 Locking: optimistic, pessimistic, and SKIP LOCKED

### What it is and why

Locking prevents two actors from making conflicting changes. Pessimistic locking assumes conflict is likely and takes a lock first. Optimistic locking assumes conflict is rare, does the work without a lock, and verifies at write time that nothing changed.

### How it works internally

**Row locks in PostgreSQL** live in the tuple header (`xmax` plus lock bits), not in a memory lock table, so they scale to millions of rows. Modes include `FOR UPDATE`, `FOR NO KEY UPDATE`, `FOR SHARE`, `FOR KEY SHARE`. A second transaction that wants a conflicting lock waits on the first transaction's id. `NOWAIT` fails immediately; `SKIP LOCKED` skips rows locked by others, which is the building block of database-backed work queues. Deadlocks (A waits for B, B waits for A) are detected after `deadlock_timeout` (1 s default) and one transaction is aborted with `40P01`. The standard prevention is to lock rows in a consistent order.

**Optimistic locking in JPA**: a `@Version` column. Hibernate includes `AND version = ?` in the `UPDATE`/`DELETE` and increments it. Zero rows updated means someone else changed it, so Hibernate throws `OptimisticLockException` (Spring translates it to `ObjectOptimisticLockingFailureException`).

### How THIS PROJECT uses it

**Optimistic locking (Project-implemented behaviour).**
`Ticket` has `@Version private Long version;` and `@DynamicUpdate`. The entity Javadoc explains the design: `@DynamicUpdate` writes only changed columns so that the SLA job, which uses bulk `UPDATE`s that do not bump the version (`TicketRepository.updateSlaState`), and a user edit do not overwrite each other's columns. A background SLA check therefore never causes a user-facing 409. The cost is that the version does not protect SLA columns from a lost update by a second user edit, which is acceptable because those columns are system-owned. `GlobalExceptionHandler` maps `ObjectOptimisticLockingFailureException` to 409 `TICKET_VERSION_CONFLICT`. `KbArticle` also carries a version column (`kb_article.version`).

**Pessimistic locking (Project-implemented history).** The per-tenant ticket number used to be taken with `SELECT ... FOR UPDATE` on the tenant's `ticket_counter` row inside the ticket-creating transaction. The performance guide measured this on H2 (section 3.1.1): with ten clients on one tenant, p99 was 477 to 1 033 ms and the maximum up to 3.5 s; at 20 clients one request stalled for 12.8 to 12.9 s; with the same clients spread across three tenants p99 was about 45 to 87 ms. That differential experiment confirmed the cause (not a lock trace). The fix (`TicketNumberAllocator`) allocates the number with one atomic `UPDATE ticket_counter SET next_number = next_number + 1` in its own tiny transaction before the business transaction starts. After the fix on H2, single-tenant c=10 p99 was 81 to 84 ms and the maximum 0.15 to 0.22 s. **Trade-off recorded in the code: numbers are no longer gap-free**, because a create that fails after allocation burns its number. `TicketNumberAllocatorTest` asserts uniqueness and that gap behaviour. This is the project's best story for pessimistic locking: it names a measured cost, a fix and the trade-off. It is H2 evidence only; a PostgreSQL row lock behaves similarly in kind but the numbers were not measured on PostgreSQL.

**SKIP LOCKED versus the project's claim (be precise in interviews).** The outbox relay does **not** use `SELECT ... FOR UPDATE SKIP LOCKED`. `OutboxRepository.claim` is a compare-and-set update:

```java
@Modifying
@Query("update OutboxEvent o set o.status = 'PROCESSING', o.lockedBy = :by, o.lockedUntil = :until, o.attempts = o.attempts + 1 "
     + "where o.id = :id and ((o.status = 'PENDING' and o.nextAttemptAt <= :now) "
     + "or (o.status = 'PROCESSING' and o.lockedUntil < :now))")
int claim(...);
```

Only the relay whose update returns 1 owns the row. The Javadoc calls it "the portable equivalent of SELECT ... FOR UPDATE SKIP LOCKED". The two differ: SKIP LOCKED claims a batch in one statement and holds a lock until commit, while the CAS claims row by row after a separate `findDueIds` read, with a lease column (`locked_until`, 60 s default) that makes crashed relays recoverable without holding any database lock. The project chose the CAS because the same SQL runs on H2 and PostgreSQL.

**Conceptual learning example (PostgreSQL only): SKIP LOCKED claim of a batch.**

```sql
WITH due AS (
  SELECT id FROM outbox_event
  WHERE status = 'PENDING' AND next_attempt_at <= now()
  ORDER BY created_at LIMIT 50
  FOR UPDATE SKIP LOCKED
)
UPDATE outbox_event o
SET status = 'PROCESSING', locked_by = 'relay-1', locked_until = now() + interval '60 seconds', attempts = attempts + 1
FROM due WHERE o.id = due.id
RETURNING o.id;
```

Run two sessions at once; each gets different rows, none blocks. Note that with `ORDER BY ... LIMIT` and `SKIP LOCKED` ordering is not strictly guaranteed across workers, which matters if you need per-key order (see Part H).

### Implemented versus not implemented

Implemented: `@Version`, atomic single-statement updates, CAS claim with lease, unique-constraint arbitration. Not implemented: `LockModeType.PESSIMISTIC_WRITE` in current main code (a search of `core-service/src/main` finds no `FOR UPDATE` or `LockModeType`), `SKIP LOCKED`, advisory locks, lock timeouts.

### Performance, security, mistakes, debugging

- Optimistic locking costs nothing when there is no conflict, but under high contention it wastes work in retries. The 409 is returned to the client, which must reload and reapply; there is no automatic retry.
- Pessimistic locks held across user think time or external calls are a classic outage cause.
- Mistake: bulk JPQL updates bypass the persistence context and the version unless you write `set version = version + 1` explicitly (here the project bypasses on purpose for SLA columns).
- Mistake: catching `OptimisticLockException` inside the transaction; the transaction is already marked rollback-only.
- Debugging: `pg_locks` joined to `pg_stat_activity`, `log_lock_waits = on`, `deadlock_timeout`, and the deadlock report in the PostgreSQL log.

### Alternatives

Advisory locks (`pg_advisory_xact_lock(key)`) for application-defined mutexes; a sequence for ticket numbers (gap-tolerant, no row lock, but not per-tenant); a single-writer queue per tenant; Redis locks (weaker guarantees: see Part G).

### Interview Q&A

**Q1. Optimistic or pessimistic for editing a ticket? Why?**
Optimistic. Conflicts are rare (two agents editing the same ticket within seconds), edits involve user think time, and holding a database lock across it would be harmful. The user gets a clear 409 and can merge. Pessimistic suits short, hot, write-heavy critical sections.

**Q2. Your create endpoint got slow only under concurrency and only for one tenant. How do you investigate?**
Check pool metrics first (acquire time and timeouts rule pool exhaustion out), GC pauses, then transaction usage time. High usage time with a fast pool means time inside transactions, usually lock waits. Confirm by a differential experiment (spread clients over tenants) or `pg_stat_activity` showing `wait_event_type = Lock`. That is exactly the sequence in the performance guide.

**Q3. What is `SKIP LOCKED` good for and what can go wrong?**
It lets many workers pull from a table queue without blocking each other. Pitfalls: no strict ordering, the lock lasts until commit (so a worker that crashes mid-transaction releases the rows, which is nice) but a worker that processes inside the same transaction as the claim holds a connection for the processing time; starvation of rows that are always locked; and the result is an inconsistent view by design.

**Q4. How can two relays both publish the same outbox event in this project?**
Rarely, but yes: the lease expires (60 s) while a slow publish is still running, another relay re-claims, and both publish. That is by design at-least-once; consumers deduplicate on `eventId`.

## F.8 Entity lifecycle, persistence context and dirty checking

### What it is

Hibernate's `Session` (the JPA `EntityManager`) holds a **persistence context**: a first-level cache and identity map of entities loaded or persisted in the current unit of work. It guarantees that within one session one row has at most one Java object, and it remembers a snapshot of each managed entity so it can detect changes. That change detection is **dirty checking**.

### Entity states

![Figure: Entity lifecycle states: transient, managed, detached, removed](docs/diagrams/png/21-persistence-context.png)

- **Transient (new)**: not associated with a session, no row.
- **Managed (persistent)**: associated with an open persistence context. Changes are tracked.
- **Detached**: was managed, session closed. Changes are not tracked; `merge` copies state into a managed instance.
- **Removed**: scheduled for deletion at flush.

### How it works internally

1. `find` or a query loads a row, creates the entity, stores a loaded-state snapshot (array of property values) in the persistence context.
2. At **flush** (before a query that might be affected under `FlushMode.AUTO`, at commit, or on explicit `flush()`), Hibernate compares each managed entity with its snapshot. Differences generate `UPDATE` statements. Cost is proportional to the number of managed entities times their property count, which is why loading 50 000 entities in one transaction is expensive.
3. Statements are queued in an **action queue** and executed in a fixed order: inserts, updates, collection operations, deletes. With `hibernate.jdbc.batch_size` (the project sets 50) consecutive statements of the same shape are sent as a JDBC batch.
4. `@DynamicUpdate` builds the `UPDATE` with only changed columns; otherwise Hibernate uses a pre-built statement that sets every column.
5. Bulk JPQL or SQL updates bypass the persistence context. Managed entities already loaded can become stale, which is why `TicketCounterRepository.increment` uses `@Modifying(clearAutomatically = true, flushAutomatically = true)`.

Identifier strategies matter for batching: `GenerationType.IDENTITY` disables JDBC insert batching because Hibernate needs the generated id immediately. The project assigns UUIDs in application code, which keeps batching possible. One more detail: with an assigned id and no `@Version`, `save()` cannot tell new from existing and calls `merge`, which issues a `SELECT` first. `Ticket` has a `@Version` field, so a null version marks it as new and `persist` is used (Spring Data's `isNew` check consults the version property).

### How THIS PROJECT uses it

**Project-implemented behaviour.**

- `application.yml`: `spring.jpa.open-in-view: false`, `ddl-auto: none` (Flyway owns the schema), `jdbc.batch_size: 50`, `default_batch_fetch_size: 32`, `generate_statistics: false`. The test profile sets `ddl-auto: validate` so the Spring context fails if an entity mapping drifts from the migrations. Those tests ran on H2.
- Tenant binding to the session: `TenantAwareJpaTransactionManager.createEntityManagerForTransaction` enables the Hibernate filter `tenantFilter` on the transaction's session with the current tenant. It is the single choke point for web requests, schedulers and listeners.
- Filters do not apply to `em.find`, `getReference` and `getById`. The `TenantOwned` Javadoc says so, which is why repositories use `findByIdAndTenantId...` and `@PrePersist` refuses to persist a row for a tenant different from the bound one (`IllegalStateException("Cross-tenant write blocked")`).
- Entities reference other aggregates by id (`requesterId`, `assigneeId`, `categoryId` are plain `UUID` columns), not by `@ManyToOne`. This is a deliberate DDD-style choice (aggregate boundaries) that also removes lazy loading from the ticket path entirely.

### Lazy loading and `LazyInitializationException`

**Conceptual learning example.** Had `Ticket` been mapped with `@ManyToOne(fetch = LAZY) AppUser requester`, Hibernate would inject a proxy. Touching `ticket.getRequester().getDisplayName()` outside an open session throws `LazyInitializationException`. Common wrong fixes: `open-in-view=true` (holds a connection through view rendering and hides N+1), `FetchType.EAGER` everywhere (loads far more than needed), or marking the transaction wide. Better fixes: fetch what you need in the query (`join fetch`, `@EntityGraph`), or project to a DTO inside the transaction.

### Implemented versus not implemented

Implemented: dirty checking, `@DynamicUpdate`, batching configuration, tenant filter, `@PrePersist` guard, id-based references. Not implemented: second-level Hibernate cache (the caching is explicit in `CacheService`), `@BatchSize` annotations (global `default_batch_fetch_size` is set but irrelevant to the ticket path because there are no lazy associations), envers auditing (audit is an explicit `audit_event` table).

### Performance, security, mistakes, debugging

- `default_batch_fetch_size: 32` turns lazy-loading N+1 into `ceil(N/32)+1` queries as a safety net, but it does not replace explicit fetching.
- Large read-only list queries should use DTO projections (the repository's `aggregates` query returns `TicketAgg` records) to avoid creating managed entities and snapshots. The dashboard folds up to 50 000 projected rows this way.
- Security: the Hibernate filter and `@PrePersist` guard are application-layer defences. Native SQL, `JdbcTemplate` and `findById` bypass the filter, which Runbook 8 of the performance guide lists as the first thing to grep for.
- Debugging: set `org.hibernate.SQL=DEBUG` temporarily (default is `WARN`; never log parameters in shared environments), use `Statistics` (`generate_statistics=true`), and look for repeated statements with different ids.

### Alternatives

JDBC with `JdbcTemplate` or jOOQ (full SQL control, no persistence context, no dirty checking), Spring Data JDBC (aggregate-oriented, no lazy loading, no session), MyBatis. The project uses JPA for entity CRUD and JPQL projections for reports, and `JdbcTemplate` in the notification service (where tenant context is not needed).

### Interview Q&A

**Q1. What is the persistence context and why does it matter for performance?**
It is the per-transaction identity map plus change tracker. It gives repeatable `find` results and automatic updates, but it grows with every loaded entity and dirty checking walks all of them at flush. For bulk reads use projections or `readOnly` queries; for bulk writes batch and periodically `flush()` and `clear()`.

**Q2. When does Hibernate flush?**
Before commit, before a JPQL or Criteria query whose tables overlap pending changes (under AUTO), and on explicit `flush()`. Native queries do not trigger auto-flush unless Hibernate knows the affected tables, which can cause stale reads inside a transaction.

**Q3. What is the difference between `persist` and `merge`?**
`persist` makes a transient instance managed and fails for detached ones. `merge` copies the state of any instance onto a managed instance (loading it first if needed) and returns the managed copy. Calling `merge` on a new assigned-id entity costs an extra `SELECT`.

**Q4. Why does the project have a tenant filter and also explicit tenant predicates and RLS?**
Defence in depth: the filter covers JPQL and Criteria, but not `find` by id; explicit predicates cover `findById`-style paths; RLS covers anything that reaches the database, including native SQL. `TenantIsolationTest` asserts that another tenant gets 404 on tickets, users, analytics, audit, settings, categories, workflows and SLA, on H2.

## F.9 N+1 queries: the project's guard and fix

### What it is

N+1 means one query to load N parents and then N more queries, one per parent, to load something related. It exists because ORMs make navigation (`ticket.getRequester()`) look free. At 100 rows per page and 2 ms per query, a hidden 3N pattern costs 600 ms of round trips.

### How it happens, and the standard fixes

**Conceptual learning example (not the project's code).**

```java
// Lazy association: 1 query for the page, then 1 per row for each association touched
List<Ticket> page = ticketRepo.findAll(PageRequest.of(0, 30)).getContent();
for (Ticket t : page) dtos.add(new Dto(t.getId(), t.getRequester().getDisplayName()));   // +30 queries
```

Fixes, in order of preference: a `join fetch` or `@EntityGraph` for to-one associations; a batched `IN` query (what the project does); `@BatchSize` or `default_batch_fetch_size` as a net; a DTO projection with a join. Beware `join fetch` on collections with pagination: Hibernate then pages in memory (warning `HHH000104`).

### How THIS PROJECT prevents and proves it

**Project-implemented behaviour.** `TicketMapper.toDtos(List<Ticket>)` collects all requester, assignee, category and ticket ids from the page and issues one `findByTenantIdAndIdIn` per kind:

```java
Map<UUID, String> userNames = users.findByTenantIdAndIdIn(tenant, userIds)...;
categories.findByTenantIdAndIdIn(tenant, categoryIds)...;
attachments.findByTenantIdAndTicketIdInOrderByCreatedAtAscIdAsc(tenant, ticketIds)...;
```

`NPlusOneGuardTest` (in `core-service/src/test/java/com/example/cx/core/tickets/`) creates 30 tickets with attachments and categories, then counts JDBC statements prepared by Hibernate (`Statistics.getPrepareStatementCount()`) for `size=2` and `size=30` and asserts they are equal and at most 8; a single-ticket GET must cost at most 6.

**Measured evidence (H2, from the performance guide section 6):**

```
NPLUS1 small(size=2)=6 large(size=30)=6
```

Fifteen times more rows, the same six statements: page query, count query, actor lookup, users `IN`, categories `IN`, attachments `IN`. The test passed in an isolated copy of the sources with a print statement added. A naive mapper doing one user lookup for the requester, one for the assignee and one category lookup per row would issue `1 + 3N`, which is 91 for N=30 (arithmetic, hypothetical, not measured).

Why the guard is statement-count based and not time based: tests with an in-memory database cannot measure real round-trip cost, but statement count is deterministic and is the quantity that scales with network latency in production.

### Implemented versus not implemented

Implemented: batch assembly, a regression guard test in the normal suite, production detection guidance (compare request rate to `pg_stat_statements.calls` per endpoint). Not implemented: a PostgreSQL-level check, or a general `QueryCountAssert` helper applied to every list endpoint (only the ticket list and the customer list have guards).

### Performance, mistakes, debugging

- Each `IN (...)` list of 100 ids is one statement with a bounded parameter count. PostgreSQL handles thousands of parameters but the protocol limit is 32 767 bind parameters per statement, so chunk very large lists.
- A mistake that survives such guards: the count query. `Page<T>` triggers an extra `count(*)` which on a huge tenant is slow; use `Slice` or a capped count when exact totals are unnecessary.
- Debugging: SQL logging, Hibernate statistics, and `pg_stat_statements` ratios.

### Interview Q&A

**Q1. How do you detect N+1 in a code review and in production?**
In review: associations accessed in loops or mappers, `@OneToMany` without fetch plan. In tests: statement-count assertions with Hibernate `Statistics`, as `NPlusOneGuardTest` does. In production: the ratio of DB calls to HTTP requests per endpoint growing with page size.

**Q2. Why not just use `join fetch` everywhere?**
It multiplies rows for collections (Cartesian growth), breaks pagination (in-memory paging), and loads data you may not show. Batched `IN` queries keep each statement small and independent.

**Q3. Why did the project not map associations at all?**
Aggregate boundaries and N+1 safety: the ticket references users and categories by id, resolved in a batch. The cost is manual assembly code in `TicketMapper`.

## F.10 Connection pools: HikariCP sizing and exhaustion

### What and why

Opening a PostgreSQL connection costs a TCP handshake, authentication and a backend process (about 5 to 10 MB of server memory each). A pool keeps a bounded set of open connections and lends them out. Spring Boot uses HikariCP by default.

### How it works

A request thread asks the pool for a connection; if one is idle it is returned; if the pool is below `maximumPoolSize` a new one is created; otherwise the thread waits up to `connectionTimeout` (30 s default) and then fails with "Connection is not available, request timed out". Important settings: `maximumPoolSize` (default 10), `minimumIdle`, `connectionTimeout`, `idleTimeout`, `maxLifetime` (30 min default, keep below any infrastructure idle timeout), `leakDetectionThreshold`.

**Sizing.** Little's law: concurrent connections needed = arrival rate times time each request holds a connection. A request at 50 RPS holding a connection for 20 ms needs about one connection on average. A pool of 10 therefore handles roughly 500 RPS of 20 ms queries in the ideal case. A bigger pool is not faster: the database has a finite number of cores and I/O channels, and more concurrent queries means more context switching and lock contention. A widely used starting point is `connections = (core_count * 2) + effective_spindle_count`, then measure. Total connections across all application instances must stay below PostgreSQL `max_connections` (100 by default), leaving room for admin, migrations and replication.

**Exhaustion** happens when connections are held too long (slow query, lock wait, external call inside a transaction, leak) or the pool is too small for the load. Symptoms are in the Hikari metrics: `hikaricp_connections_active == max`, `pending > 0`, `acquire_seconds` rising, and `timeout_total` increasing.

### How THIS PROJECT uses it

**Project-implemented behaviour.** No Hikari property is set in `application.yml`, `application-local.yml` or `application-docker.yml`. The pool therefore runs at defaults: maximum 10 connections per node. The performance guide records "Hikari pool of 10" and measured, during the H2 create series, `hikaricp_connections_acquire_seconds_max = 0.106 s`, `hikaricp_connections_timeout_total = 0`, so the pool was not the bottleneck even though `usage_seconds_max` was 6.077 s. That combination (fast acquire, long usage) pointed to lock waiting inside transactions, which the counter-allocation fix resolved.

Design choices that protect the pool:

- `open-in-view: false`.
- The ticket number allocator must run **while no transaction is open**: otherwise it would need a second pooled connection per request, and under load every thread could hold one connection while waiting for a second, a classic pool deadlock. The class Javadoc states this explicitly.
- The relay publishes to the broker outside any transaction, so a slow broker does not hold database connections.
- The AI provider call is outside any transaction.
- The negative cache for token revocation (3 s per `jti`) reduces a per-request read to roughly one every 3 s per node.

**Proposed, not applied** (performance guide section 8): name the pool, set explicit `maximum-pool-size` and `connection-timeout`, and enable `leak-detection-threshold` in non-production. PgBouncer in transaction mode is mentioned as compatible with the `SET LOCAL` RLS design.

### Pooling and RLS

Because the RLS tenant id is set with `set_config(..., true)` (transaction-local), it disappears at commit or rollback, so a connection returned to the pool carries no tenant. If instead the code used a session-level `SET` the id could leak to the next borrower, which is the central pooling caveat. See F.11.

### Implemented versus not implemented

Not implemented: explicit Hikari tuning, metrics dashboards for pool saturation (Grafana panels exist under `infra/grafana`, but they were not run), PgBouncer, read replicas, `idle_in_transaction_session_timeout`.

### Performance, security, mistakes, debugging

- Mistake: raising the pool size to fix slow queries. Instead find the blocking transaction.
- Mistake: forgetting that each application instance has its own pool: 10 instances times 10 connections is 100 connections, the PostgreSQL default limit.
- Security: pool credentials come from the environment (`CX_DB_USER`, `CX_DB_PASSWORD` with no default in `application-docker.yml`). The application should use a non-superuser, non-`BYPASSRLS` role, while Flyway runs as the owner.
- Debugging (PostgreSQL, **To-run commands**): `SELECT state, count(*) FROM pg_stat_activity GROUP BY 1;` and look for `idle in transaction`; `SHOW max_connections;`; `SELECT * FROM pg_locks WHERE NOT granted;`. Locally: `curl localhost:8081/actuator/prometheus | grep hikaricp`; `jcmd <pid> Thread.print | grep -c HikariPool`.

### Troubleshooting exercise 1 (pool exhaustion)

Scenario: after a deploy, p95 latency jumps from 80 ms to 30 s on every endpoint and logs show "Connection is not available, request timed out after 30000ms". Metrics: `hikaricp_connections_active = 10`, `pending = 40`, `usage_seconds_max = 280`.

Work through it: (1) Is it too many callers or too slow holders? `usage_seconds_max` of 280 says holders. (2) On PostgreSQL run the `pg_stat_activity` query: one session `idle in transaction` for 280 s, holding a lock. (3) Find the code path: an external HTTP call or a missing `commit` in a `TransactionTemplate`. (4) Remediate: cancel the backend only as an emergency (`pg_cancel_backend`), fix the code, set `idle_in_transaction_session_timeout`. (5) Do **not** raise the pool to 100: that would let 100 sessions queue on the same lock.

### Interview Q&A

**Q1. How do you size a connection pool?**
Start from database capacity, not application threads: a small multiple of database cores, shared across all instances. Verify with Little's law and measure p95 and pool wait. Prefer a small pool and short transactions over a big pool.

**Q2. What is the effect of an external call inside `@Transactional`?**
The connection stays checked out for the duration of the call, so a slow dependency exhausts the pool and takes the whole service down. Publish after commit, or use the outbox, which is exactly why the project has one.

**Q3. Why can PgBouncer in transaction mode be a problem for PostgreSQL features?**
Session state (`SET`, prepared statements, advisory session locks, `LISTEN`) is not stable across transactions. The project's transaction-local `set_config` is compatible; session-level settings and server-side prepared statements may not be.

## F.11 Row-level security (RLS): how it works, pooling caveats, and honest status

### What it is and why it exists

RLS lets the database filter rows per query according to policies. In a shared-schema multi-tenant system it provides defence in depth: even if the application forgets a `WHERE tenant_id = ?`, the database returns nothing from other tenants.

### How it works in PostgreSQL

1. `ALTER TABLE t ENABLE ROW LEVEL SECURITY` turns it on. Without any policy, non-owners see no rows (default deny).
2. `CREATE POLICY name ON t USING (predicate) WITH CHECK (predicate)`. `USING` filters rows visible for `SELECT`, `UPDATE` and `DELETE`. `WITH CHECK` validates new or changed rows for `INSERT` and `UPDATE`.
3. Policies are applied by the planner as extra quals on the table scan, so they can use indexes when the predicate is `leakproof` or the function is `STABLE`.
4. **Bypass rules**: superusers and roles with `BYPASSRLS` ignore policies; table owners also bypass unless `FORCE ROW LEVEL SECURITY` is set.
5. The tenant is passed through a custom setting, for example `app.tenant_id`, set with `set_config('app.tenant_id', value, is_local)`. With `is_local = true` the value lives only until the end of the transaction (the same semantics as `SET LOCAL`).

### How THIS PROJECT uses it

**Project-implemented behaviour, NOT EXECUTED.** The files are `V1002__pg_row_level_security.sql` and `V1011__pg_rls_kb_ai.sql` (docker profile only) and the Java side is `TenantAwareJpaTransactionManager`.

```sql
create or replace function cx_current_tenant() returns uuid
    language sql stable
as $$ select nullif(current_setting('app.tenant_id', true), '')::uuid $$;
-- for each tenant table:
alter table ticket enable row level security;
alter table ticket force  row level security;
create policy tenant_isolation on ticket
    using (tenant_id = cx_current_tenant()) with check (tenant_id = cx_current_tenant());
```

```java
// TenantAwareJpaTransactionManager.doBegin, when pgRls is true and a tenant is bound
conn.prepareStatement("select set_config('app.tenant_id', ?, true)")   // true = transaction-local
```

Tables covered: `app_user`, `audit_event`, `ticket_category`, `ticket_counter`, `ticket`, `ticket_attachment`, `ticket_comment`, `ticket_activity`, `idempotency_key`, `workflow`, `sla_policy` (V1002) and `kb_category`, `kb_article`, `kb_article_ticket`, `ai_suggestion`, `document_store` (V1011). Deliberately **not** under RLS (stated in the migration comment): `organization` (login resolves slug to tenant before a tenant is known), `revoked_token` (no tenant column), `outbox_event` (the relay claims rows across all tenants). Those rely on application logic only.

Enabled by `cx.tenancy.pg-rls: true` in `application-docker.yml`, or by the docker profile itself (`TenancyConfig`).

Fail-closed behaviour: `current_setting('app.tenant_id', true)` returns NULL when unset, `nullif(..., '')` handles the empty string left behind after a transaction-local setting has been reset, and `tenant_id = NULL` is never true, so no rows match.

Intended test: `PostgresRlsDockerIT` (tag `docker`, Testcontainers `postgres:16-alpine`, run with `-Pdocker-tests`) applies all migrations, creates a role with `nosuperuser nobypassrls`, and checks isolation per transaction, fail-closed without a tenant, the three PG-only indexes exist, and that `audit_event` is append-only. **It was never run.** Do not claim RLS works in practice. Say: "the design is written, reviewed and has a test that needs a Docker host."

### Pooling caveats

1. **Transaction-local only.** A session-level `SET app.tenant_id` would persist on the pooled connection and leak across requests. The project uses `set_config(..., true)` for this reason.
2. **Work outside a transaction.** With autocommit and no transaction, a transaction-local setting is reset immediately after the statement. Code that runs `JdbcTemplate` outside `@Transactional` therefore sees no tenant and, under RLS, no rows (fail closed, but confusing). The Runbook 8 advice is to forbid `JdbcTemplate` outside transactions.
3. **PgBouncer.** Transaction pooling is compatible with transaction-local settings; session pooling is also fine; statement pooling is not.
4. **Role setup.** The application connects as `cx_app` (see `application-docker.yml`), but the compose file creates it as the database owner via `POSTGRES_USER`. An owner without `FORCE` bypasses RLS; the migration sets `FORCE`, which fixes that, but a superuser would still bypass. In production use separate roles: an owner for Flyway and a restricted role for the application. **This role split is not implemented in `infra/docker-compose.yml`; the Testcontainers test builds it explicitly.**
5. **Performance.** The policy predicate adds a filter to every query, but with `tenant_id`-led indexes the planner already has the equality, so the added cost should be small. To verify: **To-run command** `EXPLAIN (ANALYZE, BUFFERS)` inside the `BEGIN; set_config(...)` block shown in F.4.
6. **Superuser-run migrations and background jobs** that need cross-tenant access (the SLA job iterates tenants and binds each one; the outbox relay is exempt by not enabling RLS on the table).

### Implemented versus not implemented

Implemented (written, never executed): policies, forced RLS, transaction-local binding, fail-closed function, audit append-only trigger (`V1003`, raises exception on update or delete), a Docker IT. Not implemented: separate owner and app roles in compose, RLS on `organization`, `outbox_event`, `revoked_token`, per-role policies (`TO role`), and a CI job that runs the Docker tests.

### Security considerations

RLS protects against missing predicates, not against a compromised application role with `BYPASSRLS`, a leaked JWT secret that forges `tid`, or SQL injection that changes the tenant setting inside the same transaction (a user able to run arbitrary SQL could call `set_config` themselves). Treat the setting as trusted only because only the transaction manager sets it. Policy functions marked `SECURITY DEFINER` need careful `search_path` handling.

### Common mistakes

Forgetting `FORCE` for owners; testing as a superuser and concluding RLS "works"; using `SET` instead of `set_config(..., true)`; writing policies that call non-`STABLE` functions per row; forgetting `WITH CHECK` (rows can then be inserted for other tenants); forgetting to add policies when adding a new tenant table (the project does it by listing table names in a `DO` block, so every new table must be added to a new migration).

### Alternatives

Schema per tenant (stronger isolation, migration fan-out), database per tenant (strongest, highest cost), application-only filtering (what most systems do, weakest). Hybrid: shared schema with RLS for small tenants, dedicated database for large or regulated ones.

### Interview Q&A

**Q1. How does the application tell PostgreSQL which tenant a request belongs to, safely under connection pooling?**
By running `select set_config('app.tenant_id', ?, true)` at the start of every transaction, with the third argument true, so the value is transaction-local and cannot survive into the next borrower of the pooled connection. Policies read it through `current_setting('app.tenant_id', true)`.

**Q2. Why `FORCE ROW LEVEL SECURITY`?**
Table owners bypass RLS by default. If the application connects as the owner (common in small setups) policies would be ignored. `FORCE` applies them to the owner too. Superusers and `BYPASSRLS` roles still bypass, so the app role must be neither.

**Q3. What did you test, and what did you not?**
Be honest: the policies and the Docker integration test exist, but no PostgreSQL instance was available, so none of it was executed. What was tested on H2 is the application layer: the Hibernate filter, explicit tenant predicates, `@PrePersist` guard, composite foreign keys and `TenantIsolationTest`.

**Q4. Where does RLS not help?**
Tables without a tenant column or deliberately excluded (`organization`, `outbox_event`, `revoked_token`); a role with `BYPASSRLS`; and statements that run outside a transaction where the setting is not bound.

### Troubleshooting exercise 2 (RLS returns zero rows)

After enabling RLS in staging, every list endpoint returns an empty page, but writes appear to succeed. Hypotheses: (1) the app runs a `JdbcTemplate` read outside a transaction, so the transaction-local setting is gone; (2) the `TenantContext` is not bound on a scheduler thread; (3) the policy compares `tenant_id` to a NULL setting. Checks (**To-run**): `SELECT current_setting('app.tenant_id', true);` in the same transaction as the failing query; `SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname='ticket';`; `SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user;`; `SELECT policyname, qual FROM pg_policies;`. Fix by wrapping the call in `@Transactional` or binding the tenant, not by disabling RLS.

## F.12 Flyway migrations

### What it is and why

Flyway applies versioned SQL scripts in order and records them in a history table, so every environment gets the same schema evolution and the application can verify what is installed. The alternative, `hbm2ddl=update`, guesses changes and cannot do data migrations, indexes with predicates, or reviews.

### How it works internally

Flyway scans the configured locations, reads script names (`V<version>__<description>.sql`), compares them with the `flyway_schema_history` table (version, checksum, success flag), takes a lock (on PostgreSQL an advisory lock, so two instances starting together do not collide), and applies pending scripts in version order, each in a transaction by default. If an applied script's checksum changed, `validate` fails. Repeatable migrations (`R__`) re-run when their checksum changes. Out-of-order versions are rejected unless enabled.

### How THIS PROJECT uses it

**Project-implemented behaviour.**

| Item | Detail |
|---|---|
| Portable scripts | `core-service/src/main/resources/db/migration` V1 to V15, V20 to V22; V16 to V19 are intentionally unused (reserved note in V15) |
| PostgreSQL-only scripts | `db/migration-pg`: V1001 (partial and search indexes), V1002 (RLS), V1003 (audit append-only trigger), V1010 (KB full text), V1011 (RLS for KB and AI) |
| Location switch | `application.yml`: `classpath:db/migration`; `application-docker.yml` overrides to `classpath:db/migration,classpath:db/migration-pg` |
| Ordering trick | PG-only versions start at 1001, so they always run after the portable set, and they can reference tables from V1 to V22 |
| Hibernate relation | `ddl-auto: none`; test profile uses `validate` so drift fails the suite |
| Notification service | its own Flyway in `db/notification-migration` with history table `notif_schema_history` (`V1__notif_init.sql`, tables prefixed `notif_`) so the two services can share one database server without clashing |

Everything in the portable path ran on H2 in every test run. The PG-only path never ran.

Portability notes visible in the SQL: `timestamp with time zone`, `varchar` lengths, `uuid`, `boolean`, `bigint`, named constraints, and `check` constraints work on both H2 (PostgreSQL mode) and PostgreSQL. PostgreSQL-specific syntax (`using gin`, partial index `where`, `create extension`, `plpgsql`) lives only in `migration-pg`. Be aware that "H2 in PostgreSQL mode" is a compatibility layer, not PostgreSQL: differences in null ordering (the URL sets `DEFAULT_NULL_ORDERING=HIGH` to match), type coercion, locking behaviour, plan choice and error codes remain.

### Rules for safe migrations

1. Never edit an applied script (checksum mismatch). Add a new version.
2. Make changes backward compatible: expand, migrate, contract. Add a nullable column, deploy code that writes both, backfill, then add `NOT NULL` or drop the old column in a later release.
3. Avoid long `ACCESS EXCLUSIVE` locks: add constraints as `NOT VALID` then `VALIDATE`; create indexes `CONCURRENTLY` in a non-transactional script; set `lock_timeout` for DDL.
4. Run Flyway with the owner role and the application with a restricted role.
5. Test migrations against a copy of production-sized data.

### Implemented versus not implemented

Not implemented: `flyway:validate` in CI against a real PostgreSQL, undo scripts (a Teams-edition feature; plan forward fixes instead), `baselineOnMigrate` strategy, a migration lint, `CONCURRENTLY` index scripts, `lock_timeout` settings.

### Performance, security, mistakes, debugging

- Common mistake: two developers add `V23` on separate branches. Flyway refuses the second one unless out-of-order is enabled. Coordinate version numbers or use timestamps.
- Mistake: placing seed or test data in migrations that run in production. The project seeds demo data through `cx.seed.enabled` (true in `local`, configurable in docker), not through Flyway.
- Security: migration scripts contain DDL privileges; do not give the runtime role the right to run them. No secrets in scripts (the RLS IT builds its role password inline only for an ephemeral container).
- Debugging: `select * from flyway_schema_history order by installed_rank desc;`, `mvn flyway:info`, and the startup log lines "Successfully applied N migrations".

### Alternatives

Liquibase (XML/YAML changelogs, rollback support), Atlas or sqitch (declarative or plan based), and hand-run scripts (not recommended). Flyway is chosen for plain SQL review.

### Interview Q&A

**Q1. Why separate `db/migration` and `db/migration-pg`?**
To keep one portable core that runs on both H2 (fast tests) and PostgreSQL, while features that exist only in PostgreSQL (partial indexes, GIN, RLS, triggers) are applied only in the docker profile. The cost is that H2 tests do not exercise those features.

**Q2. How would you deploy a column rename with zero downtime?**
Expand and contract: add the new column, write both, backfill in batches, switch reads, stop writing the old column, drop it in a later release. A direct `RENAME` breaks old application instances that are still running.

**Q3. What happens when two application instances start at the same time?**
Flyway takes a database-level lock so only one applies pending migrations, the other waits and then finds nothing to do.

**Q4. Why is "tests pass on H2" not enough for these migrations?**
H2's PostgreSQL mode differs in planner, locking, types and syntax, and it cannot run `using gin`, `create policy` or `plpgsql`. The project says so openly and ships the Docker IT as the missing proof.

## F.13 Putting it together: one ticket create, end to end (H2 behaviour, PostgreSQL design)

**Project-implemented behaviour (executed on H2 in tests, design for PostgreSQL).**

1. `TicketCommandService.create` validates the `Idempotency-Key` (1 to 100 characters) and looks up `idempotency_key` for `(tenant, user, key)`. A replay returns the stored response; the same key with a different body hash is HTTP 409 `DUPLICATE_SUBMISSION`.
2. `TicketNumberAllocator.next(tenant)` runs its own small transaction: `update ticket_counter set next_number = next_number + 1`. It must run with no open transaction.
3. A business transaction starts. On PostgreSQL with RLS enabled, `TenantAwareJpaTransactionManager.doBegin` executes `set_config('app.tenant_id', ..., true)`; on every database the Hibernate `tenantFilter` is enabled on the session.
4. Rows inserted in that single transaction: `ticket`, `ticket_activity`, `audit_event`, `outbox_event` (via `OutboxService.publish`, `MANDATORY`), `idempotency_key`. Composite foreign keys and check constraints validate them.
5. Commit. After commit, `TxHooks.afterCommit` evicts the tenant's analytics cache entries.
6. Later and separately, `OutboxRelay` claims the row with the CAS update and publishes (Part H).

Failure handling in that sequence: a validation failure after step 2 burns a number (gap); a duplicate idempotency race makes the loser replay the winner; a rollback leaves no outbox row and no cache eviction (`OutboxTest.failedBusinessOperationLeavesNoEvent`, on H2).

## F.14 Comparison tables

| Question | Optimistic `@Version` | Pessimistic `FOR UPDATE` | CAS single UPDATE | Unique constraint |
|---|---|---|---|---|
| Conflict assumption | rare | frequent | rare to moderate | any |
| Lock held | none | until commit | row lock only during the statement | during insert check |
| Failure signal | exception at flush | waiting or timeout | rows updated = 0 | constraint violation |
| Project use | `Ticket`, `KbArticle` | historical ticket counter (removed) | outbox claim, counter increment, AI approval | idempotency keys, notification claim |

| Index choice | Selectivity need | Write cost | Project example |
|---|---|---|---|
| Composite B-tree | any, tenant first | per-index per insert | V15 ticket indexes |
| Partial B-tree | filter a hot subset | smaller than full | `ix_ticket_open_queue` (not executed) |
| GIN tsvector | text search | high on insert | `ix_ticket_fts` (not executed) |
| GIN trigram | substring match | high on insert | `ix_ticket_title_trgm` (not executed) |

| Isolation level | Dirty read | Non-repeatable | Phantom | Write skew |
|---|---|---|---|---|
| READ COMMITTED (default, used by project) | no | yes | yes | yes |
| REPEATABLE READ | no | no | no (PostgreSQL) | yes |
| SERIALIZABLE | no | no | no | no (with retries) |

## F.15 Additional troubleshooting exercises

### Exercise 3: the ticket list got slow only for the largest tenant

Symptom: p95 for `GET /api/v1/tickets` is 40 ms for most tenants and 900 ms for one tenant with 2 million tickets. Steps: (1) run the **To-run** `EXPLAIN (ANALYZE, BUFFERS)` from F.4 for that tenant. (2) Check `Sort` and `Rows Removed by Filter` (the signature of an index that does not match the order). (3) Check whether the count query dominates (`select count(*) ... where tenant_id = ?`). (4) Compare `n_distinct` and the most-common-values list for `status`. Likely fix: add `(tenant_id, status, created_at desc, id desc)`, replace exact counts with a capped count or `Slice`, move deep paging to keyset.

### Exercise 4: duplicate key on `uq_ticket_tenant_number`

Symptom: sporadic 500 errors with a unique violation on `(tenant_id, number)`. In this design the allocator's single `UPDATE` makes duplicates impossible, so check whether someone seeded tickets with fixed numbers, restored a backup that rolled the counter back (counter lower than `max(number)`), or called an alternative create path. Query: `select c.next_number, (select max(number) from ticket where tenant_id = c.tenant_id) from ticket_counter c where c.tenant_id = '<uuid>';`. Fix: set `next_number = max(number) + 1`.

### Exercise 5: lost update that the version column did not catch

A user complains that their change to `sla_status` was overwritten. Reason: SLA columns are updated by `updateSlaState` bulk UPDATE and `@DynamicUpdate`, deliberately outside the version. The correct answer is that those columns are system-owned and user edits do not write them; if a user edit appears to, check for a code path that calls `save` on a stale entity without `@DynamicUpdate` semantic.

### Exercise 6: Flyway checksum mismatch on startup

Someone fixed a typo in V15. All environments that already applied V15 now fail validation. Fix: revert the edit and add V23 with the correction; if the change was already deployed and truly harmless, `flyway repair` updates the stored checksum (only after reviewing the difference).

## F.16 Quick interview recap for Part F

- State the layered tenant defence: composite FKs, Hibernate filter, explicit predicates, `@PrePersist` guard, RLS (written, not executed).
- State the measured story: ticket-create tail latency, differential experiment, atomic allocator fix, trade-off (gaps), H2 only.
- State the N+1 story: batch `IN` assembly, six statements regardless of page size, test guard.
- State the limits: default READ COMMITTED, no SERIALIZABLE, no keyset in the API, no explicit Hikari tuning, PostgreSQL paths never executed.
- Name the To-run commands you would execute first when Docker becomes available: `PostgresRlsDockerIT`, the F.4 `EXPLAIN` commands, the `pg_stat_statements` top-20 query.
