-- The H2 half of postgresql/V3__profile_media_preferences.sql. See there for why.
--
-- V2 rather than V3 because the two dialects have never shared a numbering: the
-- postgresql directory carries a backfill for a production database that H2 — built
-- fresh for every test run — never needed. Flyway reads one location, so only the
-- order within this directory matters.

alter table media_profile_settings add column if not exists preferred_language varchar(16);

create table if not exists media_settings_genres (
    genre varchar(128),
    settings_id varchar(255) not null,
    unique (settings_id, genre)
);

create index if not exists idx_media_settings_genre on media_settings_genres (genre);

alter table media_settings_genres add constraint if not exists fk_settings_genres_settings
    foreign key (settings_id) references media_profile_settings;
