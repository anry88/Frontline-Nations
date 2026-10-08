# Player feedback

The active feedback campaign offers a short, optional reason picker after the first personal-battle result and sends one inactivity prompt after 24 hours when a new external player has not reached the first result or started a second battle. A campaign starts when its database row is created, so deployment does not message the historical player base.

`player_feedback_responses` allows one row per campaign and player. Claiming an inactivity prompt is terminal (`sending`, `sent`, `failed`, or `unavailable`) to prevent repeated messages after restarts or uncertain Telegram failures. A skip is final for that campaign. Configured internal accounts can exercise the inline flow but are excluded from scheduler candidates and default analytics.

Responses use a fixed reason enum. A player may explicitly open a 30-minute window for one optional comment of at most 500 characters; commands are never captured. Comments are removed after the configured retention period and must remain in restricted database reports. Prometheus exports only aggregate statuses and fixed reasons.
