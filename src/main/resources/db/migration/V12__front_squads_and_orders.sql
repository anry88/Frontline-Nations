ALTER TABLE campaign_contributions
    ADD COLUMN preset_no INTEGER,
    ADD COLUMN deployment_entry VARCHAR(32),
    ADD COLUMN tactic VARCHAR(32),
    ADD COLUMN unit_ids UUID[] NOT NULL DEFAULT ARRAY[]::UUID[];

UPDATE campaign_contributions contribution
   SET preset_no = battle_group.preset_no,
       tactic = 'maneuver',
       unit_ids = COALESCE(
           (
               SELECT ARRAY_AGG(unit.id ORDER BY unit.id)
                 FROM player_units unit
                WHERE unit.player_telegram_id = contribution.player_telegram_id
                  AND unit.reserved_week_key = contribution.week_key
                  AND unit.destroyed_at IS NULL
           ),
           ARRAY[]::UUID[]
       )
  FROM battle_groups battle_group
 WHERE contribution.group_id = battle_group.id
   AND contribution.contribution_type = 'EQUIPMENT_SNAPSHOT';

UPDATE campaign_contributions
   SET preset_no = 1,
       tactic = 'maneuver'
 WHERE contribution_type = 'EQUIPMENT_SNAPSHOT'
   AND preset_no IS NULL;

ALTER TABLE campaign_contributions
    ADD CONSTRAINT campaign_contributions_preset_check
        CHECK (preset_no IS NULL OR preset_no BETWEEN 1 AND 3),
    ADD CONSTRAINT campaign_contributions_tactic_check
        CHECK (tactic IS NULL OR tactic IN ('assault', 'defense', 'ambush', 'maneuver', 'recon'));

DROP INDEX campaign_contributions_week_player_equipment_idx;

CREATE UNIQUE INDEX campaign_contributions_week_player_preset_idx
    ON campaign_contributions(week_key, player_telegram_id, preset_no)
    WHERE contribution_type = 'EQUIPMENT_SNAPSHOT' AND voided_at IS NULL;

CREATE INDEX campaign_contributions_active_player_idx
    ON campaign_contributions(player_telegram_id, week_key, preset_no)
    WHERE contribution_type = 'EQUIPMENT_SNAPSHOT' AND voided_at IS NULL;
