create table ticket_comment (
    id           uuid                     not null,
    tenant_id    uuid                     not null,
    ticket_id    uuid                     not null,
    author_id    uuid                     not null,
    body         varchar(8000)            not null,
    visibility   varchar(10)              not null,
    ai_generated boolean                  not null default false,
    created_at   timestamp with time zone not null,
    constraint pk_ticket_comment primary key (id),
    constraint fk_comment_ticket foreign key (tenant_id, ticket_id) references ticket (tenant_id, id),
    constraint fk_comment_author foreign key (tenant_id, author_id) references app_user (tenant_id, id),
    constraint ck_comment_visibility check (visibility in ('PUBLIC', 'INTERNAL'))
);
create index ix_comment_ticket_time on ticket_comment (ticket_id, created_at);
create index ix_comment_tenant_time on ticket_comment (tenant_id, created_at);
