-- A concrete owned unit can serve in only one reusable preset. Preserve the
-- preset whose active weekly contribution currently reserves that unit. For
-- all other legacy duplicates, prefer the active preset and then the lowest
-- preset number so cleanup is deterministic.
WITH ranked_memberships AS (
    SELECT membership.group_id,
           membership.player_unit_id,
           ROW_NUMBER() OVER (
               PARTITION BY membership.player_unit_id
               ORDER BY
                   CASE
                       WHEN EXISTS (
                           SELECT 1
                             FROM campaign_contributions contribution
                            WHERE contribution.player_telegram_id = battle_group.player_telegram_id
                              AND contribution.preset_no = battle_group.preset_no
                              AND contribution.week_key = player_unit.reserved_week_key
                              AND contribution.contribution_type = 'EQUIPMENT_SNAPSHOT'
                              AND contribution.voided_at IS NULL
                              AND membership.player_unit_id = ANY(contribution.unit_ids)
                       ) THEN 0
                       WHEN battle_group.active THEN 1
                       ELSE 2
                   END,
                   battle_group.preset_no,
                   battle_group.id
           ) AS keep_order
      FROM battle_group_units membership
      JOIN battle_groups battle_group ON battle_group.id = membership.group_id
      JOIN player_units player_unit ON player_unit.id = membership.player_unit_id
), deleted_memberships AS (
    DELETE FROM battle_group_units membership
     USING ranked_memberships ranked
     WHERE membership.group_id = ranked.group_id
       AND membership.player_unit_id = ranked.player_unit_id
       AND ranked.keep_order > 1
    RETURNING membership.group_id
)
UPDATE battle_groups
   SET version = version + 1,
       updated_at = CURRENT_TIMESTAMP
 WHERE id IN (SELECT DISTINCT group_id FROM deleted_memberships);

ALTER TABLE battle_group_units
    ADD CONSTRAINT battle_group_units_one_group_per_unit UNIQUE (player_unit_id);
