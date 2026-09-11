ALTER TABLE players
    DROP CONSTRAINT IF EXISTS players_commander_level_check;

ALTER TABLE players
    ADD CONSTRAINT players_commander_level_check CHECK (commander_level >= 1),
    ADD COLUMN battle_offer_version BIGINT NOT NULL DEFAULT 0 CHECK (battle_offer_version >= 0),
    ADD COLUMN daily_reward_streak INTEGER NOT NULL DEFAULT 0 CHECK (daily_reward_streak BETWEEN 0 AND 100),
    ADD COLUMN daily_reward_last_claim DATE,
    ADD COLUMN daily_reward_claims BIGINT NOT NULL DEFAULT 0 CHECK (daily_reward_claims >= 0);

UPDATE players
   SET commander_level = 1 + CAST(xp / 1000 AS INTEGER),
       command_capacity = LEAST(1000, 10 + CAST(xp / 1000 AS INTEGER)),
       combat_orders = 5;

ALTER TABLE player_units
    DROP CONSTRAINT IF EXISTS player_units_origin_check;

ALTER TABLE player_units
    ADD CONSTRAINT player_units_origin_check
        CHECK (origin IN ('STARTER', 'PURCHASE', 'CRAFT', 'DAILY_REWARD')),
    ADD COLUMN reserved_week_key VARCHAR(16) REFERENCES campaign_weeks(week_key),
    ADD COLUMN destroyed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN destroyed_in_type VARCHAR(16),
    ADD COLUMN destroyed_reference_id UUID,
    ADD CONSTRAINT player_units_destruction_check CHECK (
        (destroyed_at IS NULL AND destroyed_in_type IS NULL AND destroyed_reference_id IS NULL)
        OR
        (destroyed_at IS NOT NULL AND destroyed_in_type IN ('PERSONAL', 'WEEKLY') AND destroyed_reference_id IS NOT NULL)
    );

CREATE INDEX player_units_available_idx
    ON player_units(player_telegram_id, unit_code, level)
    WHERE destroyed_at IS NULL;

CREATE INDEX player_units_weekly_reservation_idx
    ON player_units(reserved_week_key, player_telegram_id)
    WHERE reserved_week_key IS NOT NULL AND destroyed_at IS NULL;

ALTER TABLE equipment_transactions
    DROP CONSTRAINT IF EXISTS equipment_transactions_action_check;

ALTER TABLE equipment_transactions
    ADD CONSTRAINT equipment_transactions_action_check
        CHECK (action IN ('STARTER', 'PURCHASE', 'CRAFT', 'UPGRADE', 'DAILY_REWARD', 'BATTLE_LOSS', 'CAMPAIGN_LOSS'));

ALTER TABLE battles
    ADD COLUMN player_units_survived INTEGER NOT NULL DEFAULT 0 CHECK (player_units_survived >= 0),
    ADD COLUMN player_units_lost INTEGER NOT NULL DEFAULT 0 CHECK (player_units_lost >= 0);

ALTER TABLE campaign_contributions
    ADD COLUMN units_survived INTEGER NOT NULL DEFAULT 0 CHECK (units_survived >= 0),
    ADD COLUMN units_lost INTEGER NOT NULL DEFAULT 0 CHECK (units_lost >= 0);

-- Old snapshots did not reserve concrete owned-unit ids. They cannot safely take
-- part in destructive battles, so open contributions must be submitted again.
UPDATE campaign_contributions contribution
   SET voided_at = CURRENT_TIMESTAMP
  FROM campaign_weeks week
 WHERE contribution.week_key = week.week_key
   AND week.status = 'OPEN'
   AND contribution.contribution_type = 'EQUIPMENT_SNAPSHOT'
   AND contribution.voided_at IS NULL;
