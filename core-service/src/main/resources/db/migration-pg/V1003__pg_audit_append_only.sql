-- PostgreSQL-ONLY. NOT executed in the authoring environment. Makes audit_event append-only at the database level.
create or replace function cx_audit_event_immutable() returns trigger
    language plpgsql
as $$
begin
    raise exception 'audit_event is append-only';
end
$$;

create trigger trg_audit_event_no_update_delete
    before update or delete on audit_event
    for each row execute function cx_audit_event_immutable();
