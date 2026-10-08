ALTER TABLE players
    ADD COLUMN onboarding_variant VARCHAR(16),
    ADD CONSTRAINT players_onboarding_variant_ck
        CHECK (onboarding_variant IS NULL OR onboarding_variant IN ('legacy', 'guided_v1'));

CREATE TABLE first_mission_recommendations (
    player_telegram_id BIGINT PRIMARY KEY REFERENCES players(telegram_id) ON DELETE CASCADE,
    recommendation_version SMALLINT NOT NULL CHECK (recommendation_version > 0),
    onboarding_variant VARCHAR(16) NOT NULL CHECK (onboarding_variant = 'guided_v1'),
    offer_version BIGINT NOT NULL CHECK (offer_version >= 0),
    offer_slot SMALLINT NOT NULL CHECK (offer_slot >= 0),
    preset_no SMALLINT NOT NULL CHECK (preset_no > 0),
    group_version INTEGER NOT NULL CHECK (group_version > 0),
    entry_id VARCHAR(32) NOT NULL,
    objective_id VARCHAR(32) NOT NULL,
    tactic VARCHAR(32) NOT NULL,
    shown_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_battle_id UUID REFERENCES battles(id),
    accepted_at TIMESTAMP WITH TIME ZONE
);

ALTER TABLE battles
    ADD COLUMN order_selection_source VARCHAR(16) NOT NULL DEFAULT 'manual'
        CHECK (order_selection_source IN ('manual', 'recommended', 'customized')),
    ADD COLUMN onboarding_variant VARCHAR(16)
        CHECK (onboarding_variant IS NULL OR onboarding_variant IN ('legacy', 'guided_v1')),
    ADD COLUMN recommendation_version SMALLINT
        CHECK (recommendation_version IS NULL OR recommendation_version > 0);

COMMENT ON TABLE first_mission_recommendations IS
    'Latest guided first-mission card shown to a player. The offer version and recommended orders make restart and stale-callback handling reproducible.';

COMMENT ON COLUMN battles.order_selection_source IS
    'Whether accepted orders came from the one-tap recommendation, were customized from that card, or used the regular battle flow.';
