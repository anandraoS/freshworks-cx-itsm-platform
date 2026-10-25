-- Per-tenant ticket numbering. The row is locked (SELECT ... FOR UPDATE) inside the ticket-creating transaction.
create table ticket_counter (
    tenant_id   uuid   not null,
    next_number bigint not null,
    constraint pk_ticket_counter primary key (tenant_id),
    constraint fk_ticket_counter_tenant foreign key (tenant_id) references organization (id),
    constraint ck_ticket_counter_positive check (next_number >= 1)
);
