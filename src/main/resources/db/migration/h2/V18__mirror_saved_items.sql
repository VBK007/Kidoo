-- Mirrors postgresql/V20.
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
