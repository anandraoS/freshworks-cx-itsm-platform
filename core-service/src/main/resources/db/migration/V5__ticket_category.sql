create table ticket_category (
    id               uuid                     not null,
    tenant_id        uuid                     not null,
    name             varchar(80)              not null,
    type             varchar(20)              not null,
    default_priority varchar(10)              not null,
    created_at       timestamp with time zone not null,
    constraint pk_ticket_category primary key (id),
    constraint fk_category_tenant foreign key (tenant_id) references organization (id),
    constraint uq_category_tenant_name unique (tenant_id, name),
    constraint uq_category_tenant_id unique (tenant_id, id),
    constraint ck_category_type check (type in ('SUPPORT', 'IT_SERVICE')),
    constraint ck_category_priority check (default_priority in ('LOW', 'MEDIUM', 'HIGH', 'URGENT'))
);
