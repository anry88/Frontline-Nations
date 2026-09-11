ALTER TABLE players
    ADD COLUMN language VARCHAR(8) NOT NULL DEFAULT 'en',
    ADD COLUMN telegram_language VARCHAR(16),
    ADD COLUMN nickname VARCHAR(30),
    ADD COLUMN pending_nickname VARCHAR(30);

-- Preserve the language of the previously Russian-only interface for existing players.
UPDATE players SET language = 'ru';

ALTER TABLE players
    ADD CONSTRAINT players_language_supported
        CHECK (language IN ('en', 'ru', 'es', 'pt', 'ar', 'id', 'hi', 'tr')),
    ADD CONSTRAINT players_nickname_not_blank
        CHECK (nickname IS NULL OR length(trim(nickname)) > 0),
    ADD CONSTRAINT players_pending_nickname_not_blank
        CHECK (pending_nickname IS NULL OR length(trim(pending_nickname)) > 0);
