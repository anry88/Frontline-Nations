ALTER TABLE campaign_weeks
    ADD COLUMN pairing_version INTEGER NOT NULL DEFAULT 1;

ALTER TABLE campaign_matchups
    ADD COLUMN map_id VARCHAR(128),
    ADD COLUMN map_version INTEGER,
    ADD COLUMN map_snapshot_json JSONB,
    ADD COLUMN max_ticks INTEGER,
    ADD COLUMN completed_ticks INTEGER,
    ADD COLUMN end_reason VARCHAR(32),
    ADD COLUMN objective_score_a BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN objective_score_b BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN destroyed_score_a BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN destroyed_score_b BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN survivor_score_a BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN survivor_score_b BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN remaining_power_a BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN remaining_power_b BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN rating_before_a BIGINT,
    ADD COLUMN rating_before_b BIGINT,
    ADD COLUMN rating_after_a BIGINT,
    ADD COLUMN rating_after_b BIGINT,
    ADD COLUMN formations_json JSONB NOT NULL DEFAULT '[]'::JSONB,
    ADD COLUMN objective_state_json JSONB NOT NULL DEFAULT '[]'::JSONB,
    ADD COLUMN npc_snapshot_a JSONB NOT NULL DEFAULT '[]'::JSONB,
    ADD COLUMN npc_snapshot_b JSONB NOT NULL DEFAULT '[]'::JSONB;

CREATE TABLE alliance_ratings (
    alliance_code VARCHAR(8) PRIMARY KEY,
    english_name VARCHAR(128) NOT NULL,
    rating BIGINT NOT NULL DEFAULT 0 CHECK (rating >= 0),
    games_played INTEGER NOT NULL DEFAULT 0 CHECK (games_played >= 0),
    wins INTEGER NOT NULL DEFAULT 0 CHECK (wins >= 0),
    losses INTEGER NOT NULL DEFAULT 0 CHECK (losses >= 0),
    last_battle_score BIGINT NOT NULL DEFAULT 0 CHECK (last_battle_score >= 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE campaign_contributions
    ALTER COLUMN credits DROP NOT NULL,
    ADD COLUMN contribution_type VARCHAR(32) NOT NULL DEFAULT 'LEGACY_CREDITS',
    ADD COLUMN group_id UUID,
    ADD COLUMN group_version INTEGER,
    ADD COLUMN group_snapshot_json JSONB,
    ADD COLUMN voided_at TIMESTAMP WITH TIME ZONE;

CREATE UNIQUE INDEX campaign_contributions_week_player_equipment_idx
    ON campaign_contributions(week_key, player_telegram_id)
    WHERE contribution_type = 'EQUIPMENT_SNAPSHOT' AND voided_at IS NULL;

-- Return Credits committed to still-open v1 battles before retiring that input model.
UPDATE players player
SET credits = player.credits + refund.total
FROM (
    SELECT contribution.player_telegram_id, SUM(contribution.credits) AS total
    FROM campaign_contributions contribution
    JOIN campaign_weeks week ON week.week_key = contribution.week_key
    WHERE week.status = 'OPEN'
      AND contribution.contribution_type = 'LEGACY_CREDITS'
      AND contribution.voided_at IS NULL
    GROUP BY contribution.player_telegram_id
) refund
WHERE player.telegram_id = refund.player_telegram_id;

INSERT INTO wallet_transactions(player_telegram_id, resource_type, delta, reason)
SELECT contribution.player_telegram_id, 'CREDITS', SUM(contribution.credits), 'CAMPAIGN_V1_REFUND'
FROM campaign_contributions contribution
JOIN campaign_weeks week ON week.week_key = contribution.week_key
WHERE week.status = 'OPEN'
  AND contribution.contribution_type = 'LEGACY_CREDITS'
  AND contribution.voided_at IS NULL
GROUP BY contribution.player_telegram_id;

UPDATE campaign_contributions contribution
SET voided_at = CURRENT_TIMESTAMP
FROM campaign_weeks week
WHERE contribution.week_key = week.week_key
  AND week.status = 'OPEN'
  AND contribution.contribution_type = 'LEGACY_CREDITS'
  AND contribution.voided_at IS NULL;

-- Pairings created by engine v1 cannot be reused because v2 includes every country,
-- a versioned map assignment and rating order. Resolved rows remain immutable history.
DELETE FROM campaign_matchups matchup
USING campaign_weeks week
WHERE matchup.week_key = week.week_key
  AND week.status = 'OPEN'
  AND matchup.resolved_at IS NULL;

UPDATE campaign_weeks
SET pairing_version = 2
WHERE status = 'OPEN';
