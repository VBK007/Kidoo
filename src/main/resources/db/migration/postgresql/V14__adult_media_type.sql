-- MediaType.ADULT has existed in code since the 18+ library filter was built, but
-- nobody ever migrated the check constraint to allow it — the baseline (and V8's
-- fix for SERIES/VIDEO_SONG) both predate it, so any attempt to classify a row as
-- ADULT has been silently impossible at the database layer until now.
alter table media_items drop constraint media_items_media_type_check;
alter table media_items add constraint media_items_media_type_check
    check (media_type in ('FILM', 'ANIME', 'SERIES', 'VIDEO_SONG', 'HOME_VIDEO', 'MUSIC', 'PHOTO', 'ADULT'));
