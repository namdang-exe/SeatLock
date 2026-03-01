CREATE TABLE slots (
    slot_id    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id   UUID        NOT NULL REFERENCES venues (venue_id),
    start_time TIMESTAMPTZ NOT NULL,
    status     VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE'
                           CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_slots_venue_start  ON slots (venue_id, start_time);
CREATE INDEX idx_slots_venue_status ON slots (venue_id, status);
