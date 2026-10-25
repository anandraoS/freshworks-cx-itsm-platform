-- Synthetic attachment METADATA only (no file bytes are stored or accepted).
create table ticket_attachment (
    id           uuid                     not null,
    tenant_id    uuid                     not null,
    ticket_id    uuid                     not null,
    file_name    varchar(200)             not null,
    content_type varchar(100)             not null,
    size_bytes   bigint                   not null,
    created_at   timestamp with time zone not null,
    constraint pk_ticket_attachment primary key (id),
    constraint fk_attachment_ticket foreign key (tenant_id, ticket_id) references ticket (tenant_id, id),
    constraint ck_attachment_size check (size_bytes >= 0 and size_bytes <= 26214400)
);
create index ix_attachment_ticket on ticket_attachment (ticket_id);
