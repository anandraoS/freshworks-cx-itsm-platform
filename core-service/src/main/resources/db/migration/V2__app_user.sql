create table app_user (
    id            uuid                     not null,
    tenant_id     uuid                     not null,
    email         varchar(200)             not null,
    display_name  varchar(120)             not null,
    password_hash varchar(100)             not null,
    roles         varchar(100)             not null,
    active        boolean                  not null default true,
    created_at    timestamp with time zone not null,
    last_login_at timestamp with time zone,
    constraint pk_app_user primary key (id),
    constraint fk_app_user_tenant foreign key (tenant_id) references organization (id),
    constraint uq_app_user_tenant_email unique (tenant_id, email),
    -- lets child tables use a composite FK (tenant_id, user_id): a row can never reference another tenant's user
    constraint uq_app_user_tenant_id unique (tenant_id, id),
    constraint ck_app_user_email_lower check (email = lower(email))
);
create index ix_app_user_tenant_active on app_user (tenant_id, active);
