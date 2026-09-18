-- The H2 half of postgresql/V6__media_teaser_clips.sql. See there for why.
--
-- V5 here against V6 there, for the reason V2 gives: the two dialects have never shared
-- a numbering, and Flyway reads one location, so only the order within this directory
-- matters.
--
-- The one real difference is error: a @Lob String is oid on PostgreSQL and clob here,
-- which is the reason these two directories exist at all.

create table if not exists media_teaser_clips (
    id varchar(255) not null,
    media_item_id varchar(255) not null,
    state varchar(32) not null check (state in ('QUEUED','GENERATING','READY','FAILED')),
    start_seconds double precision not null,
    end_seconds double precision not null,
    horizontal_offset double precision not null default 0,
    label varchar(256),
    source_note varchar(1024),
    source_path varchar(1024) not null,
    output_path varchar(1024),
    output_width integer,
    output_height integer,
    file_size bigint,
    published boolean not null default false,
    sort_order integer not null default 0,
    error clob,
    created_by varchar(255),
    created_at timestamp(6) with time zone not null,
    started_at timestamp(6) with time zone,
    completed_at timestamp(6) with time zone,
    primary key (id)
);

create index if not exists idx_teaser_item on media_teaser_clips (media_item_id, sort_order);
create index if not exists idx_teaser_state on media_teaser_clips (state);
create index if not exists idx_teaser_feed on media_teaser_clips (published, sort_order, created_at);
