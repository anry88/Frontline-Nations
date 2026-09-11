ALTER TABLE players
    ADD COLUMN command_capacity INTEGER NOT NULL DEFAULT 10 CHECK (command_capacity BETWEEN 10 AND 1000);

-- Preserve the effective 10–14 CP limit used before capacity became persistent.
UPDATE players
   SET command_capacity = LEAST(14, 10 + (GREATEST(1, commander_level) - 1) / 10);

ALTER TABLE battle_group_units
    DROP CONSTRAINT IF EXISTS battle_group_units_slot_no_check;

ALTER TABLE battle_group_units
    ADD CONSTRAINT battle_group_units_slot_no_check CHECK (slot_no BETWEEN 1 AND 1000);

CREATE TABLE command_capacity_upgrades (
    id UUID PRIMARY KEY,
    player_telegram_id BIGINT NOT NULL REFERENCES players(telegram_id) ON DELETE CASCADE,
    tier_id VARCHAR(32) NOT NULL,
    capacity_before INTEGER NOT NULL CHECK (capacity_before BETWEEN 10 AND 1000),
    capacity_after INTEGER NOT NULL CHECK (capacity_after BETWEEN 10 AND 1000),
    research_cost INTEGER NOT NULL CHECK (research_cost > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (player_telegram_id, capacity_after),
    CHECK (capacity_after > capacity_before)
);

ALTER TABLE battles
    ADD COLUMN force_tier_id VARCHAR(32),
    ADD COLUMN player_deployed_cp INTEGER,
    ADD COLUMN enemy_deployed_cp INTEGER,
    ADD CONSTRAINT battles_deployed_cp_positive CHECK (
        (player_deployed_cp IS NULL OR player_deployed_cp BETWEEN 1 AND 1000)
        AND (enemy_deployed_cp IS NULL OR enemy_deployed_cp BETWEEN 1 AND 1000)
    );
