-- Saved searches with names on them.
--
-- query_json holds a CatalogQuery, which is the same object the browse endpoint builds
-- from its query parameters — so a collection cannot describe a search the catalog
-- could not already run, and the row needs no schema of its own beyond a name.
--
-- Two kinds of row live here. A collection somebody wrote carries query_json and a
-- name. A row carrying only builtin_key exists to remember that a built-in was pinned
-- or renamed: those definitions are code, so a fresh install has no rows at all and
-- still shows every built-in, and a later version can improve one without a data
-- migration chasing existing households.
--
-- profile_id is null for the household and set only when the query asks about a person
-- — the watch and like filters — because "4K" means the same thing to everyone and
-- "films I liked" does not.
--
-- IF NOT EXISTS throughout, matching V2 to V4: safe to re-run on a database that has
-- already been patched by hand.

create table if not exists media_collections (
    id varchar(255) not null,
    owner_id varchar(255) not null,
    profile_id varchar(255),
    name varchar(128),
    icon varchar(32),
    query_json oid,
    builtin_key varchar(64),
    pinned boolean not null,
    sort_order integer not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id)
);

create index if not exists idx_collection_owner on media_collections (owner_id);
create index if not exists idx_collection_profile on media_collections (profile_id);

-- One row per built-in per account: the pin state is a fact about the pair, and a
-- second row would make "is it pinned" ambiguous.
create unique index if not exists uq_collection_owner_builtin
    on media_collections (owner_id, builtin_key);
