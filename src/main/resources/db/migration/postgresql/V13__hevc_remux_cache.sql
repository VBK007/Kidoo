-- Caches a one-time H264 remux for files whose source codec (HEVC) this server's own
-- GPU cannot hardware-decode and would otherwise force a live software transcode on
-- every playback from a client that cannot take the original (e.g. iOS + Matroska).
-- Same checked-at gating as music_artwork_checked_at: null is the only thing that
-- means "try again", so a file the remux genuinely cannot handle is not re-attempted
-- every pass.
alter table media_items add column remuxed_path varchar(1024);
alter table media_items add column remux_checked_at timestamp(6) with time zone;
