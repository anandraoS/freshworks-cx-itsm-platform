create table ticket_activity (
    id         uuid                     not null,
    tenant_id  uuid                     not null,
    ticket_id  uuid                     not null,
    actor_id   uuid,
    actor_name varchar(120),
    type       varchar(40)              not null,
    details    varchar(1000),
    created_at timestamp with time zone not null,
    constraint pk_ticket_activity primary key (id),
    constraint fk_activity_ticket foreign key (tenant_id, ticket_id) references ticket (tenant_id, id)
);
create index ix_activity_ticket_time on ticket_activity (ticket_id, created_at);
create index ix_activity_tenant_time on ticket_activity (tenant_id, created_at);
