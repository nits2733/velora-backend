-- Business-model change: Velora is admin-controlled assignment, not a marketplace -
-- customers never browse/select a professional directly, so bookmarking one (or a
-- portfolio item) no longer has a purpose. Drops the tables added in V13.

DROP TABLE IF EXISTS saved_portfolio_items;
DROP TABLE IF EXISTS saved_professionals;
