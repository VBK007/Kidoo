-- Language and genre answers from the client's first-run questions.
--
-- Until now those three taps were written to the handset and nowhere else, so
-- reinstalling the app or picking up a second device lost them. awayMaxHeight
-- already lived here; these two belong beside it for the same reason — they
-- describe a person, and a person uses more than one device.
--
-- IF NOT EXISTS throughout, matching V2: this has to be a safe no-op on a database
-- that has already been patched by hand.

alter table media_profile_settings add column if not exists preferred_language varchar(16);

create table if not exists media_settings_genres (
    genre varchar(128),
    settings_id varchar(255) not null,
    unique (settings_id, genre)
);

create index if not exists idx_media_settings_genre on media_settings_genres (genre);

-- Named explicitly rather than left to Hibernate's hash, so it can be dropped by a
-- later migration without first looking it up in the live database.
alter table media_settings_genres drop constraint if exists fk_settings_genres_settings;
alter table media_settings_genres add constraint fk_settings_genres_settings
    foreign key (settings_id) references media_profile_settings;
