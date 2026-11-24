-- Mirrors postgresql/V21.
create table mirror_progress (
    id varchar(255) not null,
    profile_id varchar(255) not null,
    mirror_id varchar(128) not null,
    name varchar(512) not null,
    poster_url varchar(2048),
    release_year integer,
    ott varchar(64),
    type varchar(64),
    episode_id varchar(128),
    position_seconds double precision not null,
    duration_seconds double precision,
    finished boolean not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_mirror_progress_profile_item unique (profile_id, mirror_id)
);
create index idx_mirror_progress_profile on mirror_progress (profile_id, updated_at);
