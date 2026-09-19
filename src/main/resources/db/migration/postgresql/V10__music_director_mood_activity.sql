-- Backs the Spotify-style music home screen: composer credit plus the audio-derived
-- mood/activity rails and the raw features they were computed from (see
-- AudioFeatureService). Nullable throughout, same as the other music-only columns —
-- populated on scan for MUSIC/VIDEO_SONG items, untouched for everything else.
alter table media_items add column music_director varchar(512);
alter table media_items add column mood varchar(32);
alter table media_items add column activity varchar(32);
alter table media_items add column audio_bpm double precision;
alter table media_items add column audio_energy_rms double precision;
alter table media_items add column audio_spectral_centroid double precision;
alter table media_items add column audio_analyzed_at timestamp(6) with time zone;

create index idx_media_item_mood on media_items (mood);
create index idx_media_item_activity on media_items (activity);
create index idx_media_item_music_director on media_items (music_director);
