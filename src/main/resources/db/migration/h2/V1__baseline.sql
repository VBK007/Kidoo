-- Baseline (H2): the same schema as postgresql/V1__baseline.sql, in H2's own types.
--
-- Two copies exist because a @Lob String is `oid` on PostgreSQL and `clob` on H2, so
-- one file cannot validate against both. The tests run the migrations rather than
-- letting Hibernate build the schema, which is what makes an entity change without a
-- matching migration fail the suite instead of surfacing in production — and that only
-- works if H2 has a baseline of its own. spring.flyway.locations picks the right
-- directory per profile.
--
-- Generated from the entity model, not written by hand. Nothing here should be edited.
-- To change the schema, add V2__*.sql to BOTH directories.

-- Tables
create table activity_events (seconds integer not null, at timestamp(6) with time zone, id varchar(255) not null, profile_id varchar(255) not null, world varchar(255) not null, primary key (id));
create table content_items (published boolean not null, content_version bigint not null, updated_at timestamp(6) with time zone, content_key varchar(255) not null, content_type varchar(255) not null, id varchar(255) not null, body clob, primary key (id), constraint uk_content_type_key unique (content_type, content_key));
create table login_events (at timestamp(6) with time zone, user_agent varchar(512), app_version varchar(255), country varchar(255), device_id varchar(255) not null, id varchar(255) not null, ip varchar(255) not null, platform varchar(255), region varchar(255), user_id varchar(255) not null, primary key (id));
create table media_chapters (chapter_index integer not null, end_seconds float(53), start_seconds float(53) not null, title varchar(512), id varchar(255) not null, media_item_id varchar(255) not null, primary key (id), constraint uk_chapter_item_index unique (media_item_id, chapter_index));
create table media_download_jobs (percent float(53) not null, source_height integer, target_height integer, completed_at timestamp(6) with time zone, created_at timestamp(6) with time zone not null, estimated_bytes bigint, expires_at timestamp(6) with time zone, file_size bigint, last_fetched_at timestamp(6) with time zone, started_at timestamp(6) with time zone, item_title varchar(512), output_path varchar(1024), source_path varchar(1024), id varchar(255) not null, media_item_id varchar(255) not null, profile_id varchar(255) not null, error clob, mode enum ('CONVERT','PASSTHROUGH') not null, state enum ('CANCELLED','CONVERTING','EXPIRED','FAILED','QUEUED','READY') not null, primary key (id));
create table media_item_comments (created_at timestamp(6) with time zone not null, edited_at timestamp(6) with time zone, body varchar(1000) not null, author_name varchar(255) not null, id varchar(255) not null, media_item_id varchar(255) not null, owner_id varchar(255) not null, profile_id varchar(255) not null, primary key (id));
create table media_item_genres (genre varchar(128), media_item_id varchar(255) not null, unique (media_item_id, genre));
create table media_item_likes (created_at timestamp(6) with time zone not null, id varchar(255) not null, media_item_id varchar(255) not null, profile_id varchar(255) not null, primary key (id), constraint uk_like_profile_item unique (profile_id, media_item_id));
create table media_item_people (person varchar(128), media_item_id varchar(255) not null, unique (media_item_id, person));
create table media_items (catalog_only boolean not null, hidden boolean not null, missing boolean not null, probe_audio_channels integer, probe_duration_seconds float(53), probe_height integer, probe_width integer, rating float(53), release_date date, release_year integer, runtime_minutes integer, track_number integer, type_locked boolean not null, added_at timestamp(6) with time zone, captured_at timestamp(6) with time zone, direct_play_count bigint not null, file_modified_at timestamp(6) with time zone, file_size bigint, last_played_at timestamp(6) with time zone, like_count bigint not null, probe_bitrate bigint, probed_at timestamp(6) with time zone, transcode_count bigint not null, updated_at timestamp(6) with time zone, certification varchar(64), imdb_id varchar(64), tmdb_id varchar(64), library_name varchar(128), quality varchar(128), place varchar(256), album varchar(512), artist varchar(512), file_name varchar(512) not null, original_title varchar(512), sort_title varchar(512), studio varchar(512), title varchar(512) not null, backdrop_path varchar(1024), directors varchar(1024), file_path varchar(1024) not null, folder_path varchar(1024), poster_path varchar(1024), tagline varchar(1024), probe_audio_tracks varchar(2000), probe_subtitles varchar(2000), id varchar(255) not null, probe_audio_codecs varchar(255), probe_container varchar(255), probe_video_codec varchar(255), probe_video_profile varchar(255), cast_members clob, media_type enum ('ANIME','FILM','HOME_VIDEO','MUSIC','PHOTO') not null, metadata_source enum ('FILENAME','MANUAL','NFO'), plot clob, primary key (id), constraint uk_media_item_file_path unique (file_path));
create table media_playback_progress (audio_track_index integer, duration_seconds float(53), position_seconds float(53) not null, subtitle_offset_seconds float(53), subtitle_track_index integer, watched boolean not null, updated_at timestamp(6) with time zone not null, id varchar(255) not null, media_item_id varchar(255) not null, profile_id varchar(255) not null, primary key (id), constraint uk_progress_profile_item unique (profile_id, media_item_id));
create table media_profile_settings (away_max_height integer, download_height integer, show_technical_badges boolean not null, updated_at timestamp(6) with time zone not null, id varchar(255) not null, profile_id varchar(255) not null, away_behaviour enum ('ASK','SAVED_ONLY','STREAM') not null, primary key (id), constraint uk_media_settings_profile unique (profile_id));
create table media_trickplay (columns integer not null, frame_count integer, interval_seconds integer not null, row_count integer not null, sheet_count integer, tile_height integer, tile_width integer, generated_at timestamp(6) with time zone, updated_at timestamp(6) with time zone not null, error varchar(512), cache_dir varchar(1024), id varchar(255) not null, media_item_id varchar(255) not null, state enum ('FAILED','GENERATING','READY') not null, primary key (id), constraint uk_trickplay_item unique (media_item_id));
create table media_watch_events (seconds_watched float(53) not null, occurred_at timestamp(6) with time zone not null, id varchar(255) not null, media_item_id varchar(255) not null, profile_id varchar(255) not null, primary key (id));
create table profile_badges (badge varchar(255), profile_id varchar(255) not null, unique (profile_id, badge));
create table profile_pals (pal varchar(255), profile_id varchar(255) not null, unique (profile_id, pal));
create table profile_worlds (pct integer, profile_id varchar(255) not null, world varchar(255) not null, primary key (profile_id, world));
create table profiles (chess_hints boolean not null, chess_lesson_step integer, chess_wins integer, memory_best integer, narration boolean not null, puzzles_solved integer, stars integer, streak integer, created_at timestamp(6) with time zone, avatar_tint varchar(255), id varchar(255) not null, name varchar(255) not null, owner_id varchar(255) not null, age_mode enum ('OLDER','YOUNG'), primary key (id));
create table refresh_tokens (expires_at timestamp(6) with time zone not null, issued_at timestamp(6) with time zone not null, revoked_at timestamp(6) with time zone, token_hash varchar(64) not null unique, id varchar(255) not null, user_id varchar(255) not null, primary key (id));
create table users (ask_before_purchases boolean, chess_wins integer, daily_screen_time_min integer, memory_best integer, premium boolean, stars integer, weekly_email_summary boolean, created_at timestamp(6) with time zone, premium_until timestamp(6) with time zone, display_name varchar(255), email varchar(255) not null unique, id varchar(255) not null, language varchar(255), parent_pin_hash varchar(255), password_hash varchar(255) not null, sub_plan varchar(255), username varchar(255) not null unique, role enum ('CHILD','PARENT'), primary key (id));
create table watch_parties (max_members integer not null, require_approval_for_guests boolean not null, join_code varchar(6) not null, created_at timestamp(6) with time zone not null, ended_at timestamp(6) with time zone, host_account_id varchar(255) not null, host_profile_id varchar(255) not null, id varchar(255) not null, media_item_id varchar(255) not null, primary key (id), constraint uk_party_join_code unique (join_code));
create table watch_party_members (joined_at timestamp(6) with time zone, last_seen_at timestamp(6) with time zone, requested_at timestamp(6) with time zone not null, display_name varchar(40) not null, guest_token_id varchar(64), poll_token varchar(64), account_id varchar(255), id varchar(255) not null, party_id varchar(255) not null, profile_id varchar(255), role enum ('GUEST','HOST','MEMBER') not null, state enum ('ADMITTED','DENIED','LEFT','PENDING') not null, primary key (id), constraint uk_party_account unique (party_id, account_id));

-- Indexes
create index idx_activity_profile_at on activity_events (profile_id, at);
create index idx_login_events_user_at on login_events (user_id, at);
create index idx_chapter_item on media_chapters (media_item_id);
create index idx_download_profile on media_download_jobs (profile_id, created_at);
create index idx_download_state on media_download_jobs (state);
create index idx_comment_item on media_item_comments (media_item_id, created_at);
create index idx_comment_profile on media_item_comments (profile_id);
create index idx_media_item_genre on media_item_genres (genre);
create index idx_like_item on media_item_likes (media_item_id);
create index idx_like_profile on media_item_likes (profile_id, created_at);
create index idx_media_item_person on media_item_people (person);
create index idx_media_item_sort_title on media_items (sort_title);
create index idx_media_item_type on media_items (media_type);
create index idx_media_item_missing on media_items (missing);
create index idx_media_item_captured on media_items (captured_at);
create index idx_media_item_likes on media_items (like_count);
create index idx_progress_profile_updated on media_playback_progress (profile_id, updated_at);
create index idx_watch_event_occurred on media_watch_events (occurred_at);
create index idx_watch_event_profile on media_watch_events (profile_id, occurred_at);
create index idx_profile_owner on profiles (owner_id);
create index idx_refresh_token_user on refresh_tokens (user_id);
create index idx_party_host on watch_parties (host_account_id, created_at);
create index idx_party_item on watch_parties (media_item_id);
create index idx_member_party on watch_party_members (party_id, state);
create index idx_member_poll on watch_party_members (poll_token);

-- Foreign keys for the element-collection tables
alter table if exists media_item_genres add constraint FK5dt0x9estdihebbt1hyyx6dqn foreign key (media_item_id) references media_items;
alter table if exists media_item_people add constraint FKdkhjylniilxe4lmkexabqf3yi foreign key (media_item_id) references media_items;
alter table if exists profile_badges add constraint FK7v7w4aroo8c0r9fjfr62mvvry foreign key (profile_id) references profiles;
alter table if exists profile_pals add constraint FKfxk9ar84el6iye5pw6qwli3wp foreign key (profile_id) references profiles;
alter table if exists profile_worlds add constraint FKsfywxwlh07u8lihb43ls9k119 foreign key (profile_id) references profiles;
