-- PostgreSQL-ONLY, docker profile only. NOT executed in the authoring environment (no Docker/PG available).
--
-- Row-level security = the database enforcing the same tenant predicate as the application layer (defence in depth).
-- The application sets the tenant per TRANSACTION:   select set_config('app.tenant_id', '<uuid>', true)   -- true = SET LOCAL
-- (see TenantAwareJpaTransactionManager). Because the value is transaction-local, a pooled connection can never carry one
-- tenant's id into another request. If no tenant is set, current_setting(..., true) is NULL and the policy matches no rows
-- (fail closed).
--
-- IMPORTANT CAVEATS
--  * RLS does not apply to superusers or to the table OWNER unless FORCE ROW LEVEL SECURITY is set (done below). Run the
--    application with a dedicated NON-superuser role that does not have BYPASSRLS; run Flyway as the owner.
--  * Deliberately NOT under RLS: organization (login resolves slug -> tenant before any tenant is known), revoked_token
--    (no tenant column), outbox_event (the relay claims rows across all tenants). Those are protected by application logic.

create or replace function cx_current_tenant() returns uuid
    language sql stable
as $$ select nullif(current_setting('app.tenant_id', true), '')::uuid $$;

do $$
declare
    t text;
begin
    foreach t in array array['app_user', 'audit_event', 'ticket_category', 'ticket_counter', 'ticket',
                             'ticket_attachment', 'ticket_comment', 'ticket_activity', 'idempotency_key',
                             'workflow', 'sla_policy']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('drop policy if exists tenant_isolation on %I', t);
        execute format('create policy tenant_isolation on %I using (tenant_id = cx_current_tenant()) with check (tenant_id = cx_current_tenant())', t);
    end loop;
end
$$;
