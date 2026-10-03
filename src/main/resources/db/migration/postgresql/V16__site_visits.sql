-- Page views on the public GitHub Pages site — a beacon fired from the static
-- landing page itself, not from the paired app or an authenticated session,
-- so there is no profile or account to hang this off. Deliberately no IP: the
-- admin traffic view only ever needs a count and a path/referrer breakdown,
-- and this table is reachable by anyone who loads the page, so it holds as
-- little as answers that.
create table site_visits (
    id varchar(255) not null,
    path varchar(512) not null,
    referrer varchar(512),
    user_agent varchar(512),
    created_at timestamp(6) with time zone not null,
    primary key (id)
);
create index idx_site_visits_created_at on site_visits (created_at);
