-- My List for mirror titles: a watchlist the catalogue side has no equivalent of,
-- because a mirror title lives in an external catalogue and has no media_items row
-- to carry a "saved" flag. One row per (profile, mirror title), keyed on profile so
-- a household sharing one login keeps separate lists, with a small title snapshot
-- alongside so the list can draw its posters without a round trip to the mirror per
-- tile. Nothing here is a foreign key: the row stands whether or not the mirror still
-- carries the title, exactly as a like or comment on it does.
create table mirror_saved_items (
    id varchar(255) not null,
    profile_id varchar(255) not null,
    mirror_id varchar(128) not null,
    name varchar(512) not null,
    poster_url varchar(2048),
    release_year integer,
    ott varchar(64),
    type varchar(64),
    created_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_mirror_saved_profile_item unique (profile_id, mirror_id)
);
create index idx_mirror_saved_profile on mirror_saved_items (profile_id, created_at);
