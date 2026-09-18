-- Curated, admin-triggered vertical (9:16) teaser clips cut from a movie's own
-- source file via ffmpeg -- never third-party footage. A movie may have many
-- clips (unlike media_trickplay's one-per-item), so this follows the
-- media_download_jobs shape: a persisted queue with a state machine that is
-- recovered after a restart rather than trusted to survive one.
--
-- The source movie is referenced by id AND by path (denormalised, revalidated
-- through MediaPaths.requireWithinRoots on every use), matching
-- media_download_jobs.source_path and media_item_comments.media_item_id: a
-- clip survives its movie later being marked missing while a disk is
-- unplugged.
--
-- "published" gates visibility in the shorts feed and on movie cards
-- separately from state = READY: a clip can finish encoding and sit
-- unpublished while someone checks the crop looks right (a centre-crop guess
-- can clip a face) before it reaches users. sort_order lets a published clip
-- be pinned near the top of the global feed; 0 is "unranked", ordered by
-- created_at after that.
--
-- IF NOT EXISTS throughout, matching V2 to V5: safe to re-run on a database
-- that has already been patched by hand.

create table if not exists media_teaser_clips (
    id varchar(255) not null,
    media_item_id varchar(255) not null,
    state varchar(32) not null check (state in ('QUEUED','GENERATING','READY','FAILED')),
    start_seconds double precision not null,
    end_seconds double precision not null,
    horizontal_offset double precision not null default 0,
    label varchar(256),
    source_note varchar(1024),
    source_path varchar(1024) not null,
    output_path varchar(1024),
    output_width integer,
    output_height integer,
    file_size bigint,
    published boolean not null default false,
    sort_order integer not null default 0,
    error oid,
    created_by varchar(255),
    created_at timestamp(6) with time zone not null,
    started_at timestamp(6) with time zone,
    completed_at timestamp(6) with time zone,
    primary key (id)
);

create index if not exists idx_teaser_item on media_teaser_clips (media_item_id, sort_order);
create index if not exists idx_teaser_state on media_teaser_clips (state);
create index if not exists idx_teaser_feed on media_teaser_clips (published, sort_order, created_at);
