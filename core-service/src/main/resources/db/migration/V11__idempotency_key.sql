-- Idempotency-Key support: same key + same request hash replays the stored response; different hash -> 409.
create table idempotency_key (
    id              uuid                     not null,
    tenant_id       uuid                     not null,
    user_id         uuid                     not null,
    idem_key        varchar(100)             not null,
    request_hash    varchar(64)              not null,
    response_status integer                  not null,
    response_body   varchar(20000)           not null,
    created_at      timestamp with time zone not null,
    constraint pk_idempotency_key primary key (id),
    constraint fk_idem_tenant foreign key (tenant_id) references organization (id),
    constraint uq_idem_scope unique (tenant_id, user_id, idem_key)
);
create index ix_idem_created on idempotency_key (created_at);
