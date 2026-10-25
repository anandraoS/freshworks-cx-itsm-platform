-- PostgreSQL-ONLY (docker profile). NOT executed here. Same tenant RLS policy as V1002 for the KB / AI / document tables.
do $$
declare
    t text;
begin
    foreach t in array array['kb_category', 'kb_article', 'kb_article_ticket', 'ai_suggestion', 'document_store']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('drop policy if exists tenant_isolation on %I', t);
        execute format('create policy tenant_isolation on %I using (tenant_id = cx_current_tenant()) with check (tenant_id = cx_current_tenant())', t);
    end loop;
end
$$;
