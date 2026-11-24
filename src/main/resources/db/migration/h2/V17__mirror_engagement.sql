-- Mirrors postgresql/V19.
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
