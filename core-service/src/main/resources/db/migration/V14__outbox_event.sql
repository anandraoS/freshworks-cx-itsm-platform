-- Transactional outbox: rows are inserted in the SAME transaction as the business change.
-- status: PENDING -> PROCESSING (claimed by a relay, with lease) -> SENT | back to PENDING (retry w/ backoff) | FAILED (dead).
create table outbox_event (
    id              uuid                     not null,
    tenant_id       uuid                     not null,
    event_type      varchar(60)              not null,
    payload         varchar(20000)           not null,
    status          varchar(12)              not null,
    attempts        integer                  not null default 0,
    next_attempt_at timestamp with time zone not null,
    locked_by       varchar(64),
    locked_until    timestamp with time zone,
    last_error      varchar(500),
    created_at      timestamp with time zone not null,
    sent_at         timestamp with time zone,
    constraint pk_outbox_event primary key (id),
    constraint fk_outbox_tenant foreign key (tenant_id) references organization (id),
    constraint ck_outbox_status check (status in ('PENDING', 'PROCESSING', 'SENT', 'FAILED'))
);
create index ix_outbox_poll on outbox_event (status, next_attempt_at);
create index ix_outbox_tenant on outbox_event (tenant_id, created_at);
