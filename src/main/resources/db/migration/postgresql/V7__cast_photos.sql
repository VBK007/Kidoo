-- Cache of cast/crew photos fetched from TMDB, keyed by a slug of the person's name
-- rather than a surrogate id: cast members have no identity anywhere else in this
-- schema (media_items.cast_members is a flat comma-separated string, see
-- MediaItem.java), so the name itself -- normalised -- is the only stable key
-- available. Two different movies crediting "Tom Cruise" share one row and one fetch.
--
-- state distinguishes a real "TMDB has no such person" answer (NOT_FOUND, worth
-- remembering so the same typo/obscure name is not re-queried on every page load)
-- from a transient failure (FAILED, deliberately not cached long -- the service
-- retries these rather than trusting a network blip forever).
--
-- IF NOT EXISTS throughout, matching V2 to V6: safe to re-run on a database that has
-- already been patched by hand.

create table if not exists cast_photos (
    id varchar(255) not null,
    display_name varchar(255) not null,
    state varchar(32) not null check (state in ('READY','NOT_FOUND','FAILED')),
    tmdb_person_id integer,
    photo_path varchar(1024),
    error varchar(512),
    fetched_at timestamp(6) with time zone not null,
    primary key (id)
);
