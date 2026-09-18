-- MediaType gained SERIES and VIDEO_SONG (2026-09-18), but the baseline migration
-- baked the original five values into a check constraint, so rows of either new type
-- were rejected outright rather than just missing metadata.
alter table media_items drop constraint media_items_media_type_check;
alter table media_items add constraint media_items_media_type_check
    check (media_type in ('FILM', 'ANIME', 'SERIES', 'VIDEO_SONG', 'HOME_VIDEO', 'MUSIC', 'PHOTO'));
