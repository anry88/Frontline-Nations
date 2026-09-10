ALTER TABLE players
    ADD COLUMN research_points BIGINT NOT NULL DEFAULT 0 CHECK (research_points >= 0),
    ADD COLUMN victories INTEGER NOT NULL DEFAULT 0 CHECK (victories >= 0),
    ADD COLUMN defeats INTEGER NOT NULL DEFAULT 0 CHECK (defeats >= 0),
    ADD COLUMN current_streak INTEGER NOT NULL DEFAULT 0 CHECK (current_streak >= 0),
    ADD COLUMN best_streak INTEGER NOT NULL DEFAULT 0 CHECK (best_streak >= 0);

ALTER TABLE battles
    ADD COLUMN battle_seed BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN commander_level_snapshot INTEGER NOT NULL DEFAULT 1 CHECK (commander_level_snapshot BETWEEN 1 AND 50),
    ADD COLUMN location VARCHAR(128) NOT NULL DEFAULT 'Неизвестный рубеж',
    ADD COLUMN biome VARCHAR(64) NOT NULL DEFAULT 'неизвестно',
    ADD COLUMN difficulty VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN enemy_archetype VARCHAR(32) NOT NULL DEFAULT 'ARMOR',
    ADD COLUMN tactic VARCHAR(32) NOT NULL DEFAULT 'ASSAULT',
    ADD COLUMN research_points_reward INTEGER NOT NULL DEFAULT 0 CHECK (research_points_reward >= 0),
    ADD COLUMN rounds INTEGER NOT NULL DEFAULT 1 CHECK (rounds > 0),
    ADD COLUMN events_json JSONB NOT NULL DEFAULT '[]'::JSONB;
