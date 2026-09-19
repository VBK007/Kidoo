-- Mirrors postgresql/V10.
alter table media_items add column music_director varchar(512);
alter table media_items add column mood varchar(32);
alter table media_items add column activity varchar(32);
alter table media_items add column audio_bpm float(53);
alter table media_items add column audio_energy_rms float(53);
alter table media_items add column audio_spectral_centroid float(53);
alter table media_items add column audio_analyzed_at timestamp(6) with time zone;

create index idx_media_item_mood on media_items (mood);
create index idx_media_item_activity on media_items (activity);
create index idx_media_item_music_director on media_items (music_director);
