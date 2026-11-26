-- Mirror catalogue cache (H2 — test & local-h2 profiles). Mirrors postgresql/V22 exactly, with the
-- one dialect swap the rest of this tree uses: a large-text column is `varchar` (unbounded) on H2
-- where it is `text` on Postgres. Both validate against a plain String field under ddl-auto=validate.

create table mirror_catalogue_item (
    id              varchar(255) not null,
    catalogue_scope varchar(64)  not null,
    ott             varchar(64)  not null,
    mirror_id       varchar(128) not null,
    name            varchar(512) not null,
    poster_url      varchar(2048),
    genre           varchar(128),
    release_year    integer,
    type            varchar(64),
    first_seen      timestamp(6) with time zone not null,
    last_seen       timestamp(6) with time zone not null,
    constraint pk_mirror_catalogue_item primary key (id),
    constraint uk_mirror_catalogue_item unique (catalogue_scope, ott, mirror_id)
);
create index idx_mirror_catalogue_item_scope on mirror_catalogue_item (catalogue_scope, ott);

create table mirror_catalogue_relation (
    id              varchar(255) not null,
    catalogue_scope varchar(64)  not null,
    ott             varchar(64)  not null,
    source_ref      varchar(256) not null,
    related_id      varchar(128) not null,
    kind            varchar(64)  not null,
    ordinal         integer      not null,
    updated_at      timestamp(6) with time zone not null,
    constraint pk_mirror_catalogue_relation primary key (id),
    constraint uk_mirror_catalogue_relation unique (catalogue_scope, ott, source_ref, related_id, kind)
);
create index idx_mirror_catalogue_relation_src on mirror_catalogue_relation (catalogue_scope, ott, source_ref);

-- rails_json is unbounded varchar: the sanitized List<MirrorRailDto> JSON (playlists stripped).
create table mirror_catalogue_snapshot (
    id                      varchar(255) not null,
    catalogue_scope         varchar(64) not null,
    ott                     varchar(64) not null,
    rails_json              varchar     not null,
    complete                boolean     not null,
    build_seq               bigint      not null,
    rail_count              integer     not null,
    item_count              integer     not null,
    last_successful_refresh timestamp(6) with time zone not null,
    last_attempted_refresh  timestamp(6) with time zone not null,
    last_access             timestamp(6) with time zone not null,
    version                 bigint      not null,
    constraint pk_mirror_catalogue_snapshot primary key (id),
    constraint uk_mirror_catalogue_snapshot unique (catalogue_scope, ott)
);
