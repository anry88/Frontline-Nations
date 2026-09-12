ALTER TABLE players
    ADD COLUMN registration_source VARCHAR(16) NOT NULL DEFAULT 'telegram'
        CHECK (registration_source IN ('telegram', 'referral'));

CREATE INDEX players_registration_source_created_idx
    ON players(registration_source, created_at DESC);
