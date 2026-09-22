-- Mirrors postgresql/V13.
alter table media_items add column remuxed_path varchar(1024);
alter table media_items add column remux_checked_at timestamp(6) with time zone;
