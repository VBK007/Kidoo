-- Individual singers split out of the raw artist credit, for the artists grid and an
-- artist's own catalog page. See MediaItem#getArtistNames and ArtistNames — a
-- collaboration credits every singer on it, so "Anirudh Ravichander, Badshah" has to
-- reach both artists' tiles, which a single delimited column cannot do portably.
--
-- Mirrors media_item_languages (V4): a plain collection table, not a foreign-keyed
-- lookup of distinct artists, for the same reason — nothing here needs an artist to be
-- its own first-class row with an id, only a queryable multi-valued column.

create table if not exists media_item_artist_names (
    artist_name varchar(256),
    media_item_id varchar(255) not null,
    unique (media_item_id, artist_name)
);

create index if not exists idx_media_item_artist_name on media_item_artist_names (artist_name);

alter table media_item_artist_names drop constraint if exists fk_item_artist_names_item;
alter table media_item_artist_names add constraint fk_item_artist_names_item
    foreign key (media_item_id) references media_items;
