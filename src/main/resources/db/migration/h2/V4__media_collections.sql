-- The H2 half of postgresql/V5__media_collections.sql. See there for why.
--
-- V4 here against V5 there, for the reason V2 gives: the two dialects have never shared
-- a numbering, and Flyway reads one location, so only the order within this directory
-- matters.
--
-- The one real difference is query_json: a @Lob String is oid on PostgreSQL and clob
-- here, which is the reason these two directories exist at all.

create table if not exists media_collections (
    id varchar(255) not null,
    owner_id varchar(255) not null,
    profile_id varchar(255),
    name varchar(128),
    icon varchar(32),
    query_json clob,
    builtin_key varchar(64),
    pinned boolean not null,
    sort_order integer not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id)
);

create index if not exists idx_collection_owner on media_collections (owner_id);
create index if not exists idx_collection_profile on media_collections (profile_id);

create unique index if not exists uq_collection_owner_builtin
    on media_collections (owner_id, builtin_key);
