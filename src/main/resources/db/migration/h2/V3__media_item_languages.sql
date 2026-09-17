-- The H2 half of postgresql/V4__media_item_languages.sql. See there for why.
--
-- V3 here against V4 there, for the reason V2 gives: the two dialects have never
-- shared a numbering, and Flyway reads one location, so only the order within this
-- directory matters.
--
-- H2 spells the constraint guard "add constraint if not exists" where PostgreSQL needs
-- a drop-then-add pair; that difference is the whole reason for two directories.

alter table media_items add column if not exists primary_language varchar(16);

create table if not exists media_item_languages (
    language varchar(16),
    media_item_id varchar(255) not null,
    unique (media_item_id, language)
);

create index if not exists idx_media_item_language on media_item_languages (language);

create index if not exists idx_media_item_primary_language
    on media_items (primary_language, sort_title);

alter table media_item_languages add constraint if not exists fk_item_languages_item
    foreign key (media_item_id) references media_items;
