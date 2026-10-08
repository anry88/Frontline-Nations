CREATE TABLE battle_next_actions (
    battle_id UUID PRIMARY KEY REFERENCES battles(id) ON DELETE CASCADE,
    player_telegram_id BIGINT NOT NULL REFERENCES players(telegram_id) ON DELETE CASCADE,
    action VARCHAR(24) NOT NULL CHECK (action IN ('next_battle', 'restore_group', 'choose_group')),
    reason VARCHAR(32) NOT NULL CHECK (reason IN ('group_ready', 'group_empty', 'below_minimum', 'group_reserved')),
    summary_version SMALLINT NOT NULL CHECK (summary_version > 0),
    daily_suggested BOOLEAN NOT NULL DEFAULT FALSE,
    details_text TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP WITH TIME ZONE,
    details_opened_at TIMESTAMP WITH TIME ZONE,
    details_open_count INTEGER NOT NULL DEFAULT 0 CHECK (details_open_count >= 0),
    clicked_at TIMESTAMP WITH TIME ZONE,
    click_count INTEGER NOT NULL DEFAULT 0 CHECK (click_count >= 0),
    last_click_result VARCHAR(24) CHECK (last_click_result IN ('opened', 'already_ready', 'unavailable')),
    completed_at TIMESTAMP WITH TIME ZONE,
    completion_battle_id UUID REFERENCES battles(id),
    CHECK (completion_battle_id IS NULL OR completion_battle_id <> battle_id)
);

CREATE INDEX battle_next_actions_player_created_idx
    ON battle_next_actions(player_telegram_id, created_at DESC);

CREATE INDEX battle_next_actions_pending_idx
    ON battle_next_actions(player_telegram_id, action, created_at DESC)
    WHERE completed_at IS NULL;

COMMENT ON TABLE battle_next_actions IS
    'Versioned primary post-battle recommendation. sent_at is confirmed Telegram delivery; details_opened_at is explicit engagement, not a passive read receipt.';
