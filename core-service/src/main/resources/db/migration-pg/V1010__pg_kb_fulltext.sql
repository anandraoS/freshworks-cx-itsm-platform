-- PostgreSQL-ONLY (docker profile). NOT executed in the authoring environment (no Docker/PG available).
-- Full-text index for the knowledge base. The PORTABLE search (LIKE + keyword scoring, runs on H2 and PG) does not need it;
-- this GIN index serves a native `to_tsvector(...) @@ plainto_tsquery(...)` path and is the scale-up route (see docs/kb-storage-notes.md).
create index ix_kb_article_fts on kb_article
    using gin (to_tsvector('english', coalesce(title, '') || ' ' || coalesce(summary, '') || ' ' || coalesce(body, '')));
create extension if not exists pg_trgm;
create index ix_kb_article_title_trgm on kb_article using gin (lower(title) gin_trgm_ops);
