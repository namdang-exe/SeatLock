-- booking-service owns these tables as its local read-model of venue-service data.
-- Slot/venue metadata is written through from venue-service on first hold creation.
-- Slot status (AVAILABLE/HELD/BOOKED) is managed exclusively by booking-service.
CREATE TABLE IF NOT EXISTS venues (
    venue_id UUID         PRIMARY KEY,
    name     VARCHAR(200)
);

CREATE TABLE IF NOT EXISTS slots (
    slot_id    UUID        PRIMARY KEY,
    venue_id   UUID,
    status     VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    start_time TIMESTAMPTZ,
    CONSTRAINT fk_slots_venue FOREIGN KEY (venue_id) REFERENCES venues (venue_id)
);

CREATE INDEX IF NOT EXISTS idx_slots_venue  ON slots (venue_id);
CREATE INDEX IF NOT EXISTS idx_slots_status ON slots (status);
