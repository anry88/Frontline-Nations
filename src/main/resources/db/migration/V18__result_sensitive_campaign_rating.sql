-- Preserve the rules that produced every weekly result. Existing matchups belong
-- to engine v7; only matchups created after this migration use engine v8.
UPDATE campaign_matchups
   SET engine_version = 7
 WHERE engine_version IS NULL;

ALTER TABLE campaign_matchups
    ALTER COLUMN engine_version SET DEFAULT 8,
    ALTER COLUMN engine_version SET NOT NULL;

ALTER TABLE campaign_weeks
    ADD COLUMN rating_version INTEGER NOT NULL DEFAULT 2 CHECK (rating_version > 0);

ALTER TABLE campaign_matchups
    ADD COLUMN rating_version INTEGER NOT NULL DEFAULT 2 CHECK (rating_version > 0),
    ADD COLUMN rating_minimum_loss_percent INTEGER NOT NULL DEFAULT 2
        CHECK (rating_minimum_loss_percent BETWEEN 1 AND 99),
    ADD COLUMN rating_maximum_loss_percent INTEGER NOT NULL DEFAULT 15
        CHECK (rating_maximum_loss_percent BETWEEN 1 AND 99),
    ADD CONSTRAINT campaign_matchups_rating_loss_range
        CHECK (rating_maximum_loss_percent >= rating_minimum_loss_percent);

ALTER TABLE alliance_ratings
    ALTER COLUMN rating SET DEFAULT 10000;

-- Rebuild cumulative ratings from immutable completed battle results. Version 2
-- starts every country from the same 10,000-point baseline and adds the winner's
-- score, while a loser gives up a result-sensitive percentage of accumulated
-- rating. The percentage grows linearly from 2% for an even score to 15% when
-- the opponent's score fully dominates.
DO $$
DECLARE
    battle RECORD;
    before_a BIGINT;
    before_b BIGINT;
    after_a BIGINT;
    after_b BIGINT;
    leading_score NUMERIC;
    defeat_margin NUMERIC;
    severity_basis_points NUMERIC;
    loss_basis_points NUMERIC;
    rating_loss BIGINT;
BEGIN
    UPDATE alliance_ratings
       SET rating = 10000,
           games_played = 0,
           wins = 0,
           losses = 0,
           last_battle_score = 0,
           updated_at = CURRENT_TIMESTAMP;

    FOR battle IN
        SELECT matchup.id,
               matchup.alliance_a,
               matchup.alliance_b,
               COALESCE(matchup.score_a, 0) AS score_a,
               COALESCE(matchup.score_b, 0) AS score_b,
               matchup.winner_code,
               matchup.rating_minimum_loss_percent,
               matchup.rating_maximum_loss_percent
          FROM campaign_matchups matchup
          JOIN campaign_weeks week ON week.week_key = matchup.week_key
         WHERE matchup.resolved_at IS NOT NULL
         ORDER BY week.scheduled_at, matchup.pair_index, matchup.id
    LOOP
        SELECT rating INTO before_a
          FROM alliance_ratings
         WHERE alliance_code = battle.alliance_a;
        SELECT rating INTO before_b
          FROM alliance_ratings
         WHERE alliance_code = battle.alliance_b;

        IF battle.winner_code = battle.alliance_a THEN
            after_a := before_a + battle.score_a;
            IF before_b = 0 THEN
                after_b := 0;
            ELSE
                leading_score := GREATEST(battle.score_a, battle.score_b, 1);
                defeat_margin := GREATEST(battle.score_a - battle.score_b, 0);
                severity_basis_points := FLOOR(defeat_margin * 10000 / leading_score);
                loss_basis_points := battle.rating_minimum_loss_percent * 100
                    + FLOOR(
                        (battle.rating_maximum_loss_percent - battle.rating_minimum_loss_percent)
                        * severity_basis_points / 100
                    );
                rating_loss := GREATEST(
                    1,
                    CEIL(before_b::NUMERIC * loss_basis_points / 10000)::BIGINT
                );
                after_b := GREATEST(1, before_b - rating_loss);
            END IF;
        ELSE
            after_b := before_b + battle.score_b;
            IF before_a = 0 THEN
                after_a := 0;
            ELSE
                leading_score := GREATEST(battle.score_a, battle.score_b, 1);
                defeat_margin := GREATEST(battle.score_b - battle.score_a, 0);
                severity_basis_points := FLOOR(defeat_margin * 10000 / leading_score);
                loss_basis_points := battle.rating_minimum_loss_percent * 100
                    + FLOOR(
                        (battle.rating_maximum_loss_percent - battle.rating_minimum_loss_percent)
                        * severity_basis_points / 100
                    );
                rating_loss := GREATEST(
                    1,
                    CEIL(before_a::NUMERIC * loss_basis_points / 10000)::BIGINT
                );
                after_a := GREATEST(1, before_a - rating_loss);
            END IF;
        END IF;

        UPDATE campaign_matchups
           SET rating_before_a = before_a,
               rating_before_b = before_b,
               rating_after_a = after_a,
               rating_after_b = after_b,
               rating_version = 2
         WHERE id = battle.id;

        UPDATE alliance_ratings
           SET rating = after_a,
               games_played = games_played + 1,
               wins = wins + CASE WHEN battle.winner_code = battle.alliance_a THEN 1 ELSE 0 END,
               losses = losses + CASE WHEN battle.winner_code = battle.alliance_a THEN 0 ELSE 1 END,
               last_battle_score = battle.score_a,
               updated_at = CURRENT_TIMESTAMP
         WHERE alliance_code = battle.alliance_a;

        UPDATE alliance_ratings
           SET rating = after_b,
               games_played = games_played + 1,
               wins = wins + CASE WHEN battle.winner_code = battle.alliance_b THEN 1 ELSE 0 END,
               losses = losses + CASE WHEN battle.winner_code = battle.alliance_b THEN 0 ELSE 1 END,
               last_battle_score = battle.score_b,
               updated_at = CURRENT_TIMESTAMP
         WHERE alliance_code = battle.alliance_b;
    END LOOP;

    UPDATE campaign_weeks
       SET rating_version = 2
     WHERE EXISTS (
         SELECT 1
           FROM campaign_matchups matchup
          WHERE matchup.week_key = campaign_weeks.week_key
            AND matchup.resolved_at IS NOT NULL
     );
END $$;
