CREATE TABLE holds (
    hold_id    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    slot_id    UUID        NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status     VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE', 'CONFIRMED', 'EXPIRED', 'RELEASED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_holds_session_slot UNIQUE (session_id, slot_id)
);
CREATE INDEX idx_holds_session ON holds (session_id);
CREATE INDEX idx_holds_slot    ON holds (slot_id);
CREATE INDEX idx_holds_expiry  ON holds (expires_at, status);
