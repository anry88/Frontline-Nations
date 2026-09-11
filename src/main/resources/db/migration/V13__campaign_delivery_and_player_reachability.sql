ALTER TABLE players
    ADD COLUMN telegram_unavailable_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN telegram_unavailable_reason VARCHAR(128);

ALTER TABLE campaign_notifications
    ADD COLUMN matchup_id UUID REFERENCES campaign_matchups(id),
    ADD COLUMN alliance_code VARCHAR(8),
    ADD COLUMN message_sent_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN media_sent_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN abandoned_at TIMESTAMP WITH TIME ZONE;

UPDATE campaign_notifications notification
   SET matchup_id = (
           SELECT matchup.id
             FROM campaign_matchups matchup
             JOIN players player ON player.telegram_id = notification.player_telegram_id
            WHERE matchup.week_key = notification.week_key
              AND player.alliance_code IN (matchup.alliance_a, matchup.alliance_b)
            ORDER BY matchup.pair_index
            LIMIT 1
       ),
       alliance_code = (
           SELECT player.alliance_code
             FROM players player
            WHERE player.telegram_id = notification.player_telegram_id
       ),
       message_sent_at = sent_at,
       media_sent_at = sent_at;

ALTER TABLE campaign_notifications
    ALTER COLUMN matchup_id SET NOT NULL,
    ALTER COLUMN alliance_code SET NOT NULL;

DROP INDEX campaign_notifications_pending_idx;

CREATE INDEX campaign_notifications_pending_idx
    ON campaign_notifications(created_at, id)
    WHERE sent_at IS NULL AND abandoned_at IS NULL;

CREATE INDEX players_telegram_reachable_idx
    ON players(alliance_code, telegram_id)
    WHERE telegram_unavailable_at IS NULL;
