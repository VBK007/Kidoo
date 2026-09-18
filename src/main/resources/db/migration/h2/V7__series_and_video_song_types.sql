-- MediaType gained SERIES and VIDEO_SONG (2026-09-18); mirrors postgresql/V8.
alter table media_items alter column media_type
    enum ('ANIME', 'FILM', 'HOME_VIDEO', 'MUSIC', 'PHOTO', 'SERIES', 'VIDEO_SONG') not null;
