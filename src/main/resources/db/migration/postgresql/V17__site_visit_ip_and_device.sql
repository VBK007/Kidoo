-- Adds IP address and device classification to site_visits, per explicit request
-- (2026-10-01) to see who is visiting the public site, not just how many. ip_address
-- is the real client address ClientAddress#resolve already extracted for the rate
-- throttle but previously discarded; device_type/os/browser are parsed server-side
-- from the same user_agent already stored, so no new data leaves the browser for
-- those three. screen_width/height are the only genuinely new signal, reported by
-- the beacon itself since no header carries that.
alter table site_visits add column ip_address varchar(64);
alter table site_visits add column device_type varchar(20);
alter table site_visits add column os varchar(100);
alter table site_visits add column browser varchar(100);
alter table site_visits add column screen_width integer;
alter table site_visits add column screen_height integer;
