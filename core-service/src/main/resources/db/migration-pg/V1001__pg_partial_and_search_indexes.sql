-- PostgreSQL-ONLY (loaded only under the docker profile). NOT executed in the authoring environment (no Docker/PG available).

-- Partial index for the hot "open tickets" queries (dashboard, queues, SLA scan): resolved/closed rows are excluded.
create index ix_ticket_open_queue on ticket (tenant_id, priority, created_at)
    where status not in ('RESOLVED', 'CLOSED');

-- Full-text search (GIN over a tsvector expression). Serves native `to_tsvector(...) @@ plainto_tsquery(...)` queries.
create index ix_ticket_fts on ticket
    using gin (to_tsvector('english', coalesce(title, '') || ' ' || coalesce(description, '')));

-- The portable JPQL search uses lower(title) LIKE '%q%', which a btree cannot serve; a trigram GIN index can.
-- pg_trgm is a "trusted" extension on PostgreSQL 13+, so a database owner may create it.
create extension if not exists pg_trgm;
create index ix_ticket_title_trgm on ticket using gin (lower(title) gin_trgm_ops);
