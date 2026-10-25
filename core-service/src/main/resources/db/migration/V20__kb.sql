-- Knowledge base: authoritative metadata + CURRENT content live here (relational). Version snapshots go to the DocumentStore.
create table kb_category (
    id         uuid                     not null,
    tenant_id  uuid                     not null,
    name       varchar(80)              not null,
    slug       varchar(100)             not null,
    created_at timestamp with time zone not null,
    constraint pk_kb_category primary key (id),
    constraint fk_kb_category_tenant foreign key (tenant_id) references organization (id),
    constraint uq_kb_category_tenant_name unique (tenant_id, name),
    constraint uq_kb_category_tenant_slug unique (tenant_id, slug),
    constraint uq_kb_category_tenant_id unique (tenant_id, id)
);

create table kb_article (
    id          uuid                     not null,
    tenant_id   uuid                     not null,
    title       varchar(200)             not null,
    slug        varchar(220)             not null,
    summary     varchar(500)             not null default '',
    body        varchar(15000)           not null,
    category_id uuid,
    visibility  varchar(10)              not null,
    status      varchar(10)              not null,
    author_id   uuid                     not null,
    created_at  timestamp with time zone not null,
    updated_at  timestamp with time zone not null,
    published_at timestamp with time zone,
    version     bigint                   not null default 0,
    constraint pk_kb_article primary key (id),
    constraint fk_kb_article_tenant foreign key (tenant_id) references organization (id),
    constraint fk_kb_article_category foreign key (tenant_id, category_id) references kb_category (tenant_id, id),
    constraint fk_kb_article_author foreign key (tenant_id, author_id) references app_user (tenant_id, id),
    constraint uq_kb_article_tenant_slug unique (tenant_id, slug),
    constraint uq_kb_article_tenant_id unique (tenant_id, id),
    constraint ck_kb_article_visibility check (visibility in ('PUBLIC', 'INTERNAL')),
    constraint ck_kb_article_status check (status in ('DRAFT', 'PUBLISHED'))
);
create index ix_kb_article_list on kb_article (tenant_id, status, visibility, updated_at);
create index ix_kb_article_category on kb_article (tenant_id, category_id);

create table kb_article_ticket (
    id         uuid                     not null,
    tenant_id  uuid                     not null,
    article_id uuid                     not null,
    ticket_id  uuid                     not null,
    linked_by  uuid                     not null,
    created_at timestamp with time zone not null,
    constraint pk_kb_article_ticket primary key (id),
    constraint fk_kbat_article foreign key (tenant_id, article_id) references kb_article (tenant_id, id),
    constraint fk_kbat_ticket foreign key (tenant_id, ticket_id) references ticket (tenant_id, id),
    constraint uq_kbat_article_ticket unique (article_id, ticket_id)
);
create index ix_kbat_ticket on kb_article_ticket (tenant_id, ticket_id);
create index ix_kbat_article on kb_article_ticket (tenant_id, article_id);
