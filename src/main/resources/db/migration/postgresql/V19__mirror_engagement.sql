-- Likes and comments on mirror titles — the siblings of media_item_likes and
-- media_item_comments for titles that live on the mirror (an external catalogue)
-- rather than in this server's own media_items graph. The target is a mirror_id,
-- an opaque external token (a Netflix/Hotstar id, a Prime ASIN), never a
-- media_items id, so nothing here is a foreign key: a like or comment stands
-- whether or not the mirror still carries the title.

-- One profile's thumbs-up on one mirror title. No denormalised count column; the
-- total for a title is a count(*) over these rows, which the indexes below serve.
create table mirror_likes (
    id varchar(255) not null,
    profile_id varchar(255) not null,
    mirror_id varchar(128) not null,
    created_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_mirror_like_profile_item unique (profile_id, mirror_id)
);
create index idx_mirror_like_item on mirror_likes (mirror_id);
create index idx_mirror_like_profile on mirror_likes (profile_id, created_at);

-- One thing somebody in the household said about a mirror title. Keyed on profile,
-- not account, so "dad thought it dragged" and "the eight-year-old loved it" can be
-- told apart under one shared login; owner_id decides who may moderate it;
-- author_name snapshotted so a deleted child still leaves "Maya said…" behind.
create table mirror_comments (
    id varchar(255) not null,
    mirror_id varchar(128) not null,
    profile_id varchar(255) not null,
    owner_id varchar(255) not null,
    author_name varchar(255) not null,
    body varchar(1000) not null,
    created_at timestamp(6) with time zone not null,
    edited_at timestamp(6) with time zone,
    primary key (id)
);
create index idx_mirror_comment_item on mirror_comments (mirror_id, created_at);
create index idx_mirror_comment_profile on mirror_comments (profile_id);
