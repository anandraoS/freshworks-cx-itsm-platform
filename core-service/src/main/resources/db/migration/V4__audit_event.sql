-- Append-only (application exposes no update/delete; migration-pg adds a trigger that rejects UPDATE/DELETE).
create table audit_event (
    id             uuid                     not null,
    tenant_id      uuid                     not null,
    occurred_at    timestamp with time zone not null,
    actor_id       uuid,
    actor_email    varchar(200),
    action         varchar(60)              not null,
    entity_type    varchar(40)              not null,
    entity_id      varchar(64),
    details        varchar(4000),
    correlation_id varchar(64),
    constraint pk_audit_event primary key (id),
    constraint fk_audit_event_tenant foreign key (tenant_id) references organization (id)
);
create index ix_audit_tenant_time on audit_event (tenant_id, occurred_at);
create index ix_audit_tenant_entity on audit_event (tenant_id, entity_type, occurred_at);
create index ix_audit_tenant_actor on audit_event (tenant_id, actor_id, occurred_at);
