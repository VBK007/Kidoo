-- The H2 half of postgresql/V7__cast_photos.sql. See there for why.
--
-- V6 here against V7 there, for the reason V2 gives: the two dialects have never shared
-- a numbering, and Flyway reads one location, so only the order within this directory
-- matters. No @Lob columns here, so unlike earlier pairs there is no type difference at
-- all -- this file exists only to keep the two directories in step.

create table if not exists cast_photos (
    id varchar(255) not null,
    display_name varchar(255) not null,
    state varchar(32) not null check (state in ('READY','NOT_FOUND','FAILED')),
    tmdb_person_id integer,
    photo_path varchar(1024),
    error varchar(512),
    fetched_at timestamp(6) with time zone not null,
    primary key (id)
);
