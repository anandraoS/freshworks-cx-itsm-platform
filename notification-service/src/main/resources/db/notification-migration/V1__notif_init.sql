-- Notification service schema. All tables prefixed notif_; tenant_id on every row.
-- Portable between PostgreSQL 16 and H2 (PostgreSQL mode). Own Flyway history table: notif_schema_history.

create table notif_processed_event (
    event_id      uuid                     not null,
    tenant_id     uuid                     not null,
    event_type    varchar(120)             not null,
    status        varchar(16)              not null,
    attempts      integer                  not null default 0,
    last_error    varchar(1000),
    first_seen_at timestamp with time zone not null,
    completed_at  timestamp with time zone,
    constraint pk_notif_processed_event primary key (event_id)
);
create index ix_notif_processed_event_tenant on notif_processed_event (tenant_id, first_seen_at);

create table notif_notification (
    id         uuid                     not null,
    tenant_id  uuid                     not null,
    user_id    uuid                     not null,
    event_id   uuid                     not null,
    type       varchar(120)             not null,
    title      varchar(300)             not null,
    body       varchar(2000)            not null,
    ticket_id  uuid,
    is_read    boolean                  not null default false,
    created_at timestamp with time zone not null,
    read_at    timestamp with time zone,
    constraint pk_notif_notification primary key (id),
    constraint uq_notif_notification_event_user unique (event_id, user_id)
);
create index ix_notif_notification_inbox on notif_notification (tenant_id, user_id, is_read, created_at);

create table notif_delivery (
    id              uuid                     not null,
    tenant_id       uuid                     not null,
    notification_id uuid,
    event_id        uuid                     not null,
    user_id         uuid,
    channel         varchar(16)              not null,
    status          varchar(16)              not null,
    attempts        integer                  not null default 0,
    last_error      varchar(1000),
    created_at      timestamp with time zone not null,
    updated_at      timestamp with time zone not null,
    sent_at         timestamp with time zone,
    constraint pk_notif_delivery primary key (id),
    constraint uq_notif_delivery_event_user_channel unique (event_id, user_id, channel),
    constraint fk_notif_delivery_notification foreign key (notification_id) references notif_notification (id)
);
create index ix_notif_delivery_status on notif_delivery (tenant_id, status);
