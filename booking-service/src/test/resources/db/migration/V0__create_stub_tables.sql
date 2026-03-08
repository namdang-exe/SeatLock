-- Test-only: users stub for test data setup (user_id is stored as plain UUID in holds/bookings).
-- venues and slots are created by V3__create_local_tables.sql (booking-service's own tables).
CREATE TABLE IF NOT EXISTS users (
    user_id UUID PRIMARY KEY
);
