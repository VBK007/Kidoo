-- Gates the iTunes album-art fallback lookup to at most once per track.
alter table media_items add column music_artwork_checked_at timestamp(6) with time zone;
