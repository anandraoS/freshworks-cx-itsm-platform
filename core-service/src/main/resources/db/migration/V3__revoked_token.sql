-- Logout revocation list (jti). Global: jti is unique across tenants. Rows are purged after the token expires.
create table revoked_token (
    jti        varchar(64)              not null,
    expires_at timestamp with time zone not null,
    revoked_at timestamp with time zone not null,
    constraint pk_revoked_token primary key (jti)
);
create index ix_revoked_token_expires on revoked_token (expires_at);
