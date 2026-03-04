-- Test-only: minimal stub tables to satisfy cross-service FK constraints.
-- In production the shared Postgres cluster has real tables owned by user-service and venue-service.
CREATE TABLE IF NOT EXISTS users (
    user_id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS slots (
    slot_id UUID PRIMARY KEY,
    status  VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
);
