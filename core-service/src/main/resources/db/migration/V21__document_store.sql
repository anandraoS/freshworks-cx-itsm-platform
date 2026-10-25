-- JPA-table implementation of the DocumentStore (local profile / any SQL deployment). Flexible JSON documents keyed by
-- (tenant, collection, key). The MongoDB implementation (docker profile) replaces this table; nothing authoritative lives here.
create table document_store (
    tenant_id  uuid                     not null,
    collection varchar(40)              not null,
    doc_key    varchar(120)             not null,
    doc_json   varchar(32000)           not null,
    created_at timestamp with time zone not null,
    constraint pk_document_store primary key (tenant_id, collection, doc_key),
    constraint fk_document_store_tenant foreign key (tenant_id) references organization (id)
);
