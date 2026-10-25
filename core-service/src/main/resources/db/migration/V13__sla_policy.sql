create table sla_policy (
    id                       uuid                     not null,
    tenant_id                uuid                     not null,
    priority                 varchar(10)              not null,
    first_response_minutes   integer                  not null,
    resolution_minutes       integer                  not null,
    escalation_after_percent varchar(60)              not null,
    updated_at               timestamp with time zone not null,
    constraint pk_sla_policy primary key (id),
    constraint fk_sla_policy_tenant foreign key (tenant_id) references organization (id),
    constraint uq_sla_policy_tenant_priority unique (tenant_id, priority),
    constraint ck_sla_priority check (priority in ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    constraint ck_sla_minutes check (first_response_minutes > 0 and resolution_minutes >= first_response_minutes)
);
