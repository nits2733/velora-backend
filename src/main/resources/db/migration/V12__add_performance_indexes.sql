-- Additional performance indexes on high-frequency query columns and foreign keys.

CREATE INDEX IF NOT EXISTS idx_users_role ON users (role);
CREATE INDEX IF NOT EXISTS idx_bookings_category_id ON bookings (category_id);
CREATE INDEX IF NOT EXISTS idx_bookings_portfolio_item_id ON bookings (portfolio_item_id);
CREATE INDEX IF NOT EXISTS idx_professional_profiles_availability ON professional_profiles (availability_status);
