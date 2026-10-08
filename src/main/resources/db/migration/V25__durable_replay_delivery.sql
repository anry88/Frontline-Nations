CREATE TABLE replay_delivery_jobs (
    id UUID PRIMARY KEY,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('personal', 'weekly')),
    resource_id UUID NOT NULL,
    player_telegram_id BIGINT NOT NULL REFERENCES players(telegram_id),
    chat_id BIGINT NOT NULL,
    language VARCHAR(8) NOT NULL,
    alliance_code VARCHAR(8),
    is_internal BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('initializing', 'queued', 'rendering', 'ready', 'delivered', 'failed')),
    attempt_id UUID,
    render_attempts INTEGER NOT NULL DEFAULT 0 CHECK (render_attempts >= 0),
    delivery_attempts INTEGER NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
    last_error VARCHAR(64),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ready_at TIMESTAMP WITH TIME ZONE,
    delivered_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT replay_delivery_jobs_resource_player_uk UNIQUE (kind, resource_id, player_telegram_id)
);

CREATE INDEX replay_delivery_jobs_pending_idx
    ON replay_delivery_jobs(next_attempt_at, requested_at)
    WHERE status IN ('queued', 'ready');

COMMENT ON TABLE replay_delivery_jobs IS
    'Durable, idempotent personal replay requests and Telegram delivery state. Rendering remains single-worker and downstream of saved battle events.';
