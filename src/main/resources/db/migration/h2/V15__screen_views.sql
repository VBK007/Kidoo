-- Mirrors postgresql/V18.
create table screen_views (
    id varchar(255) not null,
    user_id varchar(255) not null,
    profile_id varchar(255),
    device_id varchar(255) not null,
    screen varchar(128) not null,
    item_id varchar(255),
    platform varchar(255),
    at timestamp(6) with time zone not null,
    primary key (id)
);
create index idx_screen_views_user_at on screen_views (user_id, at);
