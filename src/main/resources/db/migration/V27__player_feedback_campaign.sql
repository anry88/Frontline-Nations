CREATE TABLE feedback_campaigns (
    campaign_id VARCHAR(32) PRIMARY KEY,
    version SMALLINT NOT NULL DEFAULT 1 CHECK (version > 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO feedback_campaigns(campaign_id, version, active)
VALUES ('activation_v1', 1, TRUE);

CREATE TABLE player_feedback_responses (
    campaign_id VARCHAR(32) NOT NULL REFERENCES feedback_campaigns(campaign_id),
    player_telegram_id BIGINT NOT NULL REFERENCES players(telegram_id),
    is_internal BOOLEAN NOT NULL DEFAULT FALSE,
    first_battle_id UUID REFERENCES battles(id),
    inline_offered_at TIMESTAMP WITH TIME ZONE,
    nudge_trigger VARCHAR(32) CHECK (nudge_trigger IN ('inactive_before_first', 'inactive_after_first')),
    nudge_status VARCHAR(16) CHECK (nudge_status IN ('sending', 'sent', 'failed', 'unavailable')),
    nudge_sent_at TIMESTAMP WITH TIME ZONE,
    opened_at TIMESTAMP WITH TIME ZONE,
    response_surface VARCHAR(16) CHECK (response_surface IN ('inline', 'nudge')),
    response_reason VARCHAR(32) CHECK (
        response_reason IN ('unclear_next', 'unclear_result_losses', 'too_long', 'not_interesting', 'no_time', 'technical_problem', 'other')
    ),
    comment_text VARCHAR(500),
    comment_requested_at TIMESTAMP WITH TIME ZONE,
    answered_at TIMESTAMP WITH TIME ZONE,
    skipped_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (campaign_id, player_telegram_id),
    CHECK (comment_text IS NULL OR char_length(comment_text) BETWEEN 1 AND 500)
);

CREATE INDEX player_feedback_nudge_idx
    ON player_feedback_responses(campaign_id, nudge_status, updated_at);

CREATE VIEW analytics_feedback_responses_all AS
SELECT response.campaign_id,
       analytics.id AS analytics_player_id,
       analytics.is_internal,
       response.first_battle_id,
       response.inline_offered_at,
       response.nudge_trigger,
       response.nudge_status,
       response.nudge_sent_at,
       response.opened_at,
       response.response_surface,
       response.response_reason,
       response.comment_text IS NOT NULL AS commented,
       response.answered_at,
       response.skipped_at,
       response.created_at,
       response.updated_at
  FROM player_feedback_responses response
  JOIN analytics_players analytics ON analytics.player_telegram_id = response.player_telegram_id;

CREATE VIEW analytics_feedback_responses AS
SELECT *
  FROM analytics_feedback_responses_all
 WHERE NOT is_internal;

CREATE VIEW analytics_feedback_comments_restricted AS
SELECT analytics.id AS analytics_player_id,
       response.campaign_id,
       response.response_reason,
       response.comment_text,
       response.answered_at,
       response.updated_at
  FROM player_feedback_responses response
  JOIN analytics_players analytics ON analytics.player_telegram_id = response.player_telegram_id
 WHERE NOT analytics.is_internal
   AND response.comment_text IS NOT NULL;

COMMENT ON TABLE player_feedback_responses IS
    'One bounded feedback interaction per campaign and player. A nudge claim is terminal to prevent repeated Telegram messages.';

COMMENT ON VIEW analytics_feedback_responses IS
    'External feedback responses with a surrogate analytics identity and no comment text.';

COMMENT ON VIEW analytics_feedback_comments_restricted IS
    'Restricted external comments by surrogate identity. Never export comment text to logs or metric labels.';
