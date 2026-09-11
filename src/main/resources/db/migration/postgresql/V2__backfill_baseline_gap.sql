-- V1__baseline.sql assumed an existing database already matched it exactly, which is
-- what baseline-on-migrate relies on: it stamps V1 as applied without running it. That
-- assumption was wrong for any database that adopted Flyway before the same release
-- that introduced media_item_comments, refresh_tokens and media_items.catalog_only —
-- ddl-auto=update never got a chance to create them, baseline-on-migrate then skipped
-- them too, and Hibernate's schema validator (ddl-auto=validate) refused to start.
--
-- IF NOT EXISTS throughout: on a database that already has these (a fresh install,
-- where V1 ran for real and created everything in one pass, or one already hand-patched
-- after hitting this) this is a safe no-op. On one still carrying the gap, it is the fix.

create table if not exists media_item_comments (created_at timestamp(6) with time zone not null, edited_at timestamp(6) with time zone, body varchar(1000) not null, author_name varchar(255) not null, id varchar(255) not null, media_item_id varchar(255) not null, owner_id varchar(255) not null, profile_id varchar(255) not null, primary key (id));
create index if not exists idx_comment_item on media_item_comments (media_item_id, created_at);
create index if not exists idx_comment_profile on media_item_comments (profile_id);

create table if not exists refresh_tokens (expires_at timestamp(6) with time zone not null, issued_at timestamp(6) with time zone not null, revoked_at timestamp(6) with time zone, token_hash varchar(64) not null unique, id varchar(255) not null, user_id varchar(255) not null, primary key (id));
create index if not exists idx_refresh_token_user on refresh_tokens (user_id);

alter table media_items add column if not exists catalog_only boolean not null default false;
