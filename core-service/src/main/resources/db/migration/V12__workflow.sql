-- Versioned workflow definitions (constrained rule model serialised as JSON; no scripts/expressions).
create table workflow (
    id         uuid                     not null,
    tenant_id  uuid                     not null,
    name       varchar(120)             not null,
    version    integer                  not null,
    active     boolean                  not null default false,
    definition varchar(20000)           not null,
    created_by uuid,
    created_at timestamp with time zone not null,
    constraint pk_workflow primary key (id),
    constraint fk_workflow_tenant foreign key (tenant_id) references organization (id),
    constraint uq_workflow_tenant_version unique (tenant_id, version),
    constraint ck_workflow_version check (version >= 1)
);
create index ix_workflow_tenant_active on workflow (tenant_id, active);
