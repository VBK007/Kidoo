-- Mirrors PostgreSQL's V15: TeaserClip.error is a plain text column, not @Lob.
-- H2 maps the `clob`/`text` keyword to a character large object, so V5's
-- `error clob` validated as CLOB while the entity (a plain String, mapped VARCHAR)
-- expects character data. Re-type it as varchar so Hibernate's schema validation
-- passes. The column has only ever held a large object's numeric id, never a real
-- message (see TeaserClip.error's own comment), so nothing is worth preserving.
alter table media_teaser_clips drop column error;
alter table media_teaser_clips add column error varchar;
