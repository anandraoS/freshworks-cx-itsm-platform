-- Tenant root. Its id is the tenantId carried in the JWT ("tid"). Also holds tenant settings.
create table organization (
    id                   uuid                     not null,
    slug                 varchar(40)              not null,
    name                 varchar(120)             not null,
    timezone             varchar(60)              not null default 'UTC',
    business_hours_start varchar(5)               not null default '09:00',
    business_hours_end   varchar(5)               not null default '17:00',
    business_days        varchar(40)              not null default 'MON,TUE,WED,THU,FRI',
    default_language     varchar(10)              not null default 'en',
    email_provider       varchar(40),
    slack_webhook_url    varchar(300),
    ai_provider          varchar(40),
    created_at           timestamp with time zone not null,
    constraint pk_organization primary key (id),
    constraint uq_organization_slug unique (slug),
    constraint ck_organization_slug_lower check (slug = lower(slug))
);
