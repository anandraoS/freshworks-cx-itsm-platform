-- AI suggestions: status / tenant / ticket / decision are relational (authoritative). Flexible metadata (features, scores,
-- prompt hash, provider details) goes to the DocumentStore, keyed by suggestion id.
create table ai_suggestion (
    id                      uuid                     not null,
    tenant_id               uuid                     not null,
    ticket_id               uuid                     not null,
    status                  varchar(20)              not null,
    provider                varchar(30)              not null,
    summary                 varchar(2000)            not null,
    suggested_category_id   uuid,
    suggested_category_name varchar(80),
    suggested_priority      varchar(10),
    confidence              double precision         not null,
    explanation             varchar(2000)            not null,
    recommended_articles    varchar(2000)            not null default '[]',
    suggested_reply         varchar(4000),
    created_by              uuid                     not null,
    created_at              timestamp with time zone not null,
    decided_by              uuid,
    decided_at              timestamp with time zone,
    decision_reason         varchar(500),
    posted_comment_id       uuid,
    constraint pk_ai_suggestion primary key (id),
    constraint fk_ai_suggestion_ticket foreign key (tenant_id, ticket_id) references ticket (tenant_id, id),
    constraint fk_ai_suggestion_creator foreign key (tenant_id, created_by) references app_user (tenant_id, id),
    constraint ck_ai_suggestion_status check (status in ('PENDING_REVIEW', 'APPROVED', 'EDITED_APPROVED', 'REJECTED'))
);
create index ix_ai_suggestion_ticket on ai_suggestion (tenant_id, ticket_id, created_at);
create index ix_ai_suggestion_decided on ai_suggestion (tenant_id, status, decided_at);
