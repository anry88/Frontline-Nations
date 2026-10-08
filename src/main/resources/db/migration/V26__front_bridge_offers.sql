CREATE TABLE front_bridge_offers (
    player_telegram_id BIGINT PRIMARY KEY REFERENCES players(telegram_id) ON DELETE CASCADE,
    battle_id UUID NOT NULL REFERENCES battles(id),
    week_key VARCHAR(16) NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN ('contribute', 'front')),
    shown_at TIMESTAMP WITH TIME ZONE,
    clicked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX front_bridge_offers_external_funnel_idx
    ON front_bridge_offers(shown_at, clicked_at);

COMMENT ON TABLE front_bridge_offers IS
    'One lifetime post-activation bridge from personal battles to the weekly front; delivery and click are tracked separately.';
