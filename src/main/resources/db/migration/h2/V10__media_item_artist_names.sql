-- The H2 half of postgresql/V11__media_item_artist_names.sql. See there for why.

create table if not exists media_item_artist_names (
    artist_name varchar(256),
    media_item_id varchar(255) not null,
    unique (media_item_id, artist_name)
);

create index if not exists idx_media_item_artist_name on media_item_artist_names (artist_name);

alter table media_item_artist_names add constraint if not exists fk_item_artist_names_item
    foreign key (media_item_id) references media_items;
