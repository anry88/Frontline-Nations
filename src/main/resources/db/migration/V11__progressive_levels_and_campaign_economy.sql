-- Rebase the live Credits balance to the compact 1:100 denomination while
-- retaining historical reward rows in their original engine denomination.
INSERT INTO wallet_transactions(player_telegram_id, resource_type, delta, reason)
SELECT telegram_id,
       'CREDITS',
       CEIL(credits / 100.0)::BIGINT - credits,
       'CURRENCY_REBASE'
  FROM players
 WHERE CEIL(credits / 100.0)::BIGINT <> credits;

UPDATE players
   SET credits = CEIL(credits / 100.0)::BIGINT,
       updated_at = CURRENT_TIMESTAMP;

ALTER TABLE players
    ALTER COLUMN credits SET DEFAULT 5;

-- Level L -> L + 1 costs 1,000 * L XP. Existing accumulated XP is retained.
UPDATE players
   SET commander_level = FLOOR((1 + SQRT(1 + 8.0 * xp / 1000.0)) / 2)::INTEGER,
       command_capacity = LEAST(
           1000,
           9 + FLOOR((1 + SQRT(1 + 8.0 * xp / 1000.0)) / 2)::INTEGER
       ),
       updated_at = CURRENT_TIMESTAMP;

ALTER TABLE campaign_matchups
    ADD COLUMN contribution_performance_json JSONB NOT NULL DEFAULT '[]'::JSONB;

ALTER TABLE battles
    ADD COLUMN outcome_credits INTEGER NOT NULL DEFAULT 0 CHECK (outcome_credits >= 0),
    ADD COLUMN destruction_credits INTEGER NOT NULL DEFAULT 0 CHECK (destruction_credits >= 0),
    ADD COLUMN outcome_xp INTEGER NOT NULL DEFAULT 0 CHECK (outcome_xp >= 0),
    ADD COLUMN destruction_xp INTEGER NOT NULL DEFAULT 0 CHECK (destruction_xp >= 0),
    ADD COLUMN enemy_units_destroyed INTEGER NOT NULL DEFAULT 0 CHECK (enemy_units_destroyed >= 0);

ALTER TABLE campaign_rewards
    ADD COLUMN destroyed_power BIGINT NOT NULL DEFAULT 0 CHECK (destroyed_power >= 0),
    ADD COLUMN captured_objectives INTEGER NOT NULL DEFAULT 0 CHECK (captured_objectives >= 0),
    ADD COLUMN destruction_credits INTEGER NOT NULL DEFAULT 0 CHECK (destruction_credits >= 0),
    ADD COLUMN capture_credits INTEGER NOT NULL DEFAULT 0 CHECK (capture_credits >= 0),
    ADD COLUMN destruction_xp INTEGER NOT NULL DEFAULT 0 CHECK (destruction_xp >= 0),
    ADD COLUMN capture_xp INTEGER NOT NULL DEFAULT 0 CHECK (capture_xp >= 0);

CREATE TABLE alliance_economy_bonuses (
    alliance_code VARCHAR(8) PRIMARY KEY REFERENCES alliance_ratings(alliance_code),
    source_week_key VARCHAR(16) NOT NULL REFERENCES campaign_weeks(week_key),
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
    credits_percent INTEGER NOT NULL CHECK (credits_percent >= 100),
    xp_percent INTEGER NOT NULL CHECK (xp_percent >= 100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (ends_at > starts_at)
);

CREATE INDEX alliance_economy_bonuses_active_idx
    ON alliance_economy_bonuses(ends_at);
