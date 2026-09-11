ALTER TABLE battles
    ADD COLUMN map_id VARCHAR(64),
    ADD COLUMN map_version INTEGER,
    ADD COLUMN deployment_entry VARCHAR(16),
    ADD COLUMN primary_objective VARCHAR(32),
    ADD COLUMN enemy_entry VARCHAR(16),
    ADD COLUMN enemy_objective VARCHAR(32),
    ADD COLUMN enemy_tactic VARCHAR(32),
    ADD COLUMN end_reason VARCHAR(32),
    ADD COLUMN map_snapshot_json JSONB NOT NULL DEFAULT '{}'::JSONB,
    ADD COLUMN enemy_group_snapshot_json JSONB NOT NULL DEFAULT '{}'::JSONB,
    ADD COLUMN spatial_events_json JSONB NOT NULL DEFAULT '[]'::JSONB,
    ADD COLUMN objective_state_json JSONB NOT NULL DEFAULT '[]'::JSONB;

ALTER TABLE battles
    ADD CONSTRAINT battles_map_version_positive CHECK (map_version IS NULL OR map_version > 0),
    ADD CONSTRAINT battles_spatial_end_reason CHECK (
        end_reason IS NULL OR end_reason IN ('ALL_OBJECTIVES_CAPTURED', 'ARMY_DESTROYED', 'ARMY_ROUTED')
    );
