UPDATE campaign_matchups
SET max_ticks = 96,
    map_version = 2
WHERE resolved_at IS NULL;
