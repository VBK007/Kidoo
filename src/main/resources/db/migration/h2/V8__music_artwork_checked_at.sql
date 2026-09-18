-- Mirrors postgresql/V9.
alter table media_items add column music_artwork_checked_at timestamp(6) with time zone;
