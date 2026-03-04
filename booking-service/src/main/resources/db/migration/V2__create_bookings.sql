CREATE TABLE bookings (
    booking_id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          UUID        NOT NULL,
    confirmation_number VARCHAR(20) NOT NULL,
    user_id             UUID        NOT NULL REFERENCES users (user_id),
    slot_id             UUID        NOT NULL REFERENCES slots (slot_id),
    hold_id             UUID        NOT NULL REFERENCES holds (hold_id),
    status              VARCHAR(10) NOT NULL DEFAULT 'CONFIRMED'
                                    CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at        TIMESTAMPTZ,
    CONSTRAINT uq_bookings_session_slot UNIQUE (session_id, slot_id)
);
CREATE INDEX idx_bookings_user         ON bookings (user_id);
CREATE INDEX idx_bookings_confirmation ON bookings (confirmation_number);
CREATE INDEX idx_bookings_session      ON bookings (session_id);
CREATE INDEX idx_bookings_slot         ON bookings (slot_id);
