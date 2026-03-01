CREATE TABLE venues (
    venue_id   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    address    VARCHAR(500) NOT NULL,
    city       VARCHAR(100) NOT NULL,
    state      VARCHAR(50)  NOT NULL,
    status     VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_venues_status ON venues (status);
