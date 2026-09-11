CREATE TABLE player_units (
    id UUID PRIMARY KEY,
    player_telegram_id BIGINT NOT NULL REFERENCES players(telegram_id) ON DELETE CASCADE,
    unit_code VARCHAR(32) NOT NULL,
    level INTEGER NOT NULL DEFAULT 1 CHECK (level BETWEEN 1 AND 5),
    durability INTEGER NOT NULL DEFAULT 100 CHECK (durability BETWEEN 0 AND 100),
    origin VARCHAR(16) NOT NULL CHECK (origin IN ('STARTER', 'PURCHASE', 'CRAFT')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX player_units_owner_idx ON player_units(player_telegram_id, created_at);

CREATE TABLE player_grants (
    player_telegram_id BIGINT NOT NULL REFERENCES players(telegram_id) ON DELETE CASCADE,
    grant_code VARCHAR(64) NOT NULL,
    granted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (player_telegram_id, grant_code)
);

CREATE TABLE battle_groups (
    id UUID PRIMARY KEY,
    player_telegram_id BIGINT NOT NULL REFERENCES players(telegram_id) ON DELETE CASCADE,
    preset_no INTEGER NOT NULL CHECK (preset_no BETWEEN 1 AND 3),
    name VARCHAR(64) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (player_telegram_id, preset_no)
);

CREATE UNIQUE INDEX battle_groups_one_active_idx ON battle_groups(player_telegram_id) WHERE active;

CREATE TABLE battle_group_units (
    group_id UUID NOT NULL REFERENCES battle_groups(id) ON DELETE CASCADE,
    player_unit_id UUID NOT NULL REFERENCES player_units(id),
    slot_no INTEGER NOT NULL CHECK (slot_no BETWEEN 1 AND 12),
    PRIMARY KEY (group_id, player_unit_id),
    UNIQUE (group_id, slot_no)
);

CREATE TABLE equipment_transactions (
    id UUID PRIMARY KEY,
    player_telegram_id BIGINT NOT NULL REFERENCES players(telegram_id) ON DELETE CASCADE,
    player_unit_id UUID NOT NULL REFERENCES player_units(id),
    action VARCHAR(16) NOT NULL CHECK (action IN ('STARTER', 'PURCHASE', 'CRAFT', 'UPGRADE')),
    credits_delta BIGINT NOT NULL DEFAULT 0,
    materials_delta BIGINT NOT NULL DEFAULT 0,
    level_before INTEGER,
    level_after INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE battles
    ADD COLUMN battle_group_id UUID REFERENCES battle_groups(id),
    ADD COLUMN battle_group_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN group_snapshot_json JSONB NOT NULL DEFAULT '{}'::JSONB,
    ADD COLUMN composition_power INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN tactic_fit INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN counter_bonus INTEGER NOT NULL DEFAULT 0;
