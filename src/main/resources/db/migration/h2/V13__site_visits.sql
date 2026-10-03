-- Mirrors postgresql/V16.
create table site_visits (
    id varchar(255) not null,
    path varchar(512) not null,
    referrer varchar(512),
    user_agent varchar(512),
    created_at timestamp(6) with time zone not null,
    primary key (id)
);
create index idx_site_visits_created_at on site_visits (created_at);
