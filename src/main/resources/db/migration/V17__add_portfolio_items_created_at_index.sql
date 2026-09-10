-- Explore page defaults to sorting portfolio items by createdAt desc with pagination;
-- no index existed on that column, forcing a full-table sort on every page.

CREATE INDEX IF NOT EXISTS idx_portfolio_items_created_at ON portfolio_items (created_at);
