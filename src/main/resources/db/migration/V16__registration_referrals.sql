ALTER TABLE players
    ADD COLUMN registration_referral VARCHAR(64);

-- All referral registrations before payload storage came from the initial RiverKing campaign.
UPDATE players
   SET registration_referral = 'riverking'
 WHERE registration_source = 'referral';

ALTER TABLE players
    ADD CONSTRAINT players_registration_referral_check CHECK (
        (registration_source = 'telegram' AND registration_referral IS NULL)
        OR
        (registration_source = 'referral' AND registration_referral IS NOT NULL)
    );

CREATE INDEX players_registration_referral_created_idx
    ON players(registration_referral, created_at DESC)
    WHERE registration_source = 'referral';
