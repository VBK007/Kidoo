-- TeaserClip.error was mapped @Lob, which Hibernate stores as a Postgres large object
-- (oid reference) rather than inline text. Writing one correctly needs an explicit
-- transaction, which every save that sets this column runs without on purpose (see
-- TeaserClipService's fail()/complete() docs) — so the column has never held anything
-- but the large object's own numeric id, not the message itself. Nothing in it is
-- worth preserving through the type change.
alter table media_teaser_clips drop column error;
alter table media_teaser_clips add column error text;
