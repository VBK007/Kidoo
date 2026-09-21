-- Ceremony poster creator: the template catalog and the pieces templates are built from.
--
-- layout, color_themes and properties are jsonb rather than tables of their own. A
-- layout is only ever read and written whole — the editor loads a template, the
-- renderer draws it — so a row per text box would buy joins and nothing else, and every
-- new kind of element a designer wanted would be a migration. See PosterLayout.
--
-- Components are shared instead of copied: the same gold mandala sits on a dozen
-- marriage templates, and as a row the client fetches and caches it once. The
-- references live inside the layout JSON and are checked in the service on every save,
-- which is also why deleting a component that a template still uses is refused there —
-- a foreign key cannot reach inside a JSON document to say the same thing.
--
-- IF NOT EXISTS throughout, matching V2 onwards: safe to re-run on a database that has
-- already been patched by hand.

create table if not exists poster_templates (
    published boolean not null,
    sort_order integer not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    category varchar(32) not null check ((category in ('MARRIAGE','ENGAGEMENT','BIRTHDAY','BABY_SHOWER','NAMING_CEREMONY','HOUSE_WARMING','ANNIVERSARY'))),
    name varchar(160) not null,
    thumbnail varchar(512),
    id varchar(255) not null,
    layout jsonb not null,
    color_themes jsonb not null,
    primary key (id)
);

create index if not exists idx_poster_template_category on poster_templates (category);

create table if not exists poster_components (
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    component_type varchar(32) not null check ((component_type in ('FONT','STICKER','FRAME'))),
    name varchar(160) not null,
    url varchar(512),
    id varchar(255) not null,
    properties jsonb not null,
    primary key (id),
    constraint uk_poster_component_type_name unique (component_type, name)
);
