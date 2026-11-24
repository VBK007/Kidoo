-- Continue Watching for mirror titles: the resume marker the catalogue keeps as
-- playback progress on a media_items row, for a title that has no such row. One row
-- per (profile, mirror title), keyed on profile so a household sharing one login
-- resumes each to its own place, with the same small title snapshot mirror_saved_items
-- carries so the rail draws its posters without a round trip to the mirror per tile.
-- A new position on a title already started overwrites its row and bumps updated_at,
-- which is the order the rail reads in; finished rows stay behind (so a re-watch starts
-- from zero) but drop out of the rail, which lists finished = false only. Nothing here
-- is a foreign key, exactly as with a like, a comment or a saved item on a mirror title.
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
