-- Spoken languages per title, derived from the probed audio tracks.
--
-- The data already existed: every probed row carries its tracks as
-- "index:codec:language:title" in probe_audio_tracks, so nothing here needs a rescan
-- or a scraper — only a backfill that reads a column nobody was querying
-- (POST /api/media/admin/backfill-languages).
--
-- A table rather than a column because a dual-audio file is genuinely two languages,
-- and primary_language beside it because the common filter is "Tamil films", not
-- "films with a Tamil track somewhere" — those differ on exactly the dual-audio files
-- that motivated the table.
--
-- IF NOT EXISTS throughout, matching V2 and V3: this has to be a safe no-op on a
-- database that has already been patched by hand.

alter table media_items add column if not exists primary_language varchar(16);

create table if not exists media_item_languages (
    language varchar(16),
    media_item_id varchar(255) not null,
    unique (media_item_id, language)
);

create index if not exists idx_media_item_language on media_item_languages (language);

-- The filter sorts by title within a language, so the pair is worth one index.
create index if not exists idx_media_item_primary_language
    on media_items (primary_language, sort_title);

-- Named explicitly rather than left to Hibernate's hash, so a later migration can drop
-- it without first looking it up in the live database.
alter table media_item_languages drop constraint if exists fk_item_languages_item;
alter table media_item_languages add constraint fk_item_languages_item
    foreign key (media_item_id) references media_items;
