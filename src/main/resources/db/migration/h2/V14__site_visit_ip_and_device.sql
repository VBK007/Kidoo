-- Mirrors postgresql/V17.
alter table site_visits add column ip_address varchar(64);
alter table site_visits add column device_type varchar(20);
alter table site_visits add column os varchar(100);
alter table site_visits add column browser varchar(100);
alter table site_visits add column screen_width integer;
alter table site_visits add column screen_height integer;
