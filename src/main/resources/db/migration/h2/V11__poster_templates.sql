-- The H2 half of postgresql/V12__poster_templates.sql. See there for why.
--
-- V11 here against V12 there, for the reason V2 gives: the two dialects have never
-- shared a numbering, and Flyway reads one location, so only the order within this
-- directory matters.
--
-- Two differences, both of them Hibernate mapping the same field differently per
-- dialect: the JSON columns are `json` here and `jsonb` there, and an enum is H2's
-- native `enum (...)` here against a varchar with a check constraint there. H2's enum
-- declaration is sorted alphabetically, which is how Hibernate writes it.

create table if not exists poster_templates (
    published boolean not null,
    sort_order integer not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    name varchar(160) not null,
    thumbnail varchar(512),
    id varchar(255) not null,
    category enum ('ANNIVERSARY','BABY_SHOWER','BIRTHDAY','ENGAGEMENT','HOUSE_WARMING','MARRIAGE','NAMING_CEREMONY') not null,
    layout json not null,
    color_themes json not null,
    primary key (id)
);

create index if not exists idx_poster_template_category on poster_templates (category);

create table if not exists poster_components (
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    name varchar(160) not null,
    url varchar(512),
    id varchar(255) not null,
    component_type enum ('FONT','FRAME','STICKER') not null,
    properties json not null,
    primary key (id),
    constraint uk_poster_component_type_name unique (component_type, name)
);
