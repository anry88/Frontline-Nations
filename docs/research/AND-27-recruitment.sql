-- AND-27 research support.
--
-- Run against the production-shaped PostgreSQL database after V22. The default
-- views exclude confirmed internal accounts. Candidate output contains only a
-- surrogate analytics identity; never paste Telegram IDs into research files.

-- Candidates who finished exactly one battle and did not start a second one.
-- Inactivity is a recruiting filter, not proof that the player churned.
SELECT analytics_player_id,
       registered_at,
       first_battle_finished_at,
       first_battle_result,
       first_battle_lost_units,
       last_user_action_at,
       session_count,
       observed_technical_context
  FROM analytics_dropoff_report
 WHERE first_battle_finished_at IS NOT NULL
   AND second_battle_started_at IS NULL
   AND session_inactive_30m
 ORDER BY first_battle_finished_at DESC;

-- Candidates who registered but never started a battle.
SELECT analytics_player_id,
       registered_at,
       last_activation_stage,
       last_user_action_at,
       session_count,
       observed_technical_context
  FROM analytics_dropoff_report
 WHERE first_battle_started_at IS NULL
   AND session_inactive_30m
 ORDER BY registered_at DESC;

-- Event reconciliation for one consenting participant. Keep the result in the
-- restricted working environment and record only the P01-P05 code in the report.
SELECT *
  FROM analytics_external_event_sequences
 WHERE analytics_player_id = :analytics_player_id
 ORDER BY session_id, event_order;

-- Technical battle stages for the participant's sessions.
SELECT attempts.operation,
       attempts.mode,
       attempts.resource_id,
       attempts.started_at,
       attempts.completed_at,
       attempts.last_stage,
       attempts.failure_class,
       attempts.cache_status,
       stages.stage,
       stages.result,
       stages.occurred_at
  FROM technical_operation_attempts attempts
  JOIN technical_operation_stages stages ON stages.attempt_id = attempts.attempt_id
 WHERE attempts.session_id IN (
       SELECT DISTINCT session_id
         FROM analytics_external_event_sequences
        WHERE analytics_player_id = :analytics_player_id
 )
 ORDER BY attempts.started_at, stages.occurred_at, stages.id;

-- Contact resolution is intentionally separate and must only be used by the
-- owner for an individually authorized invitation. Do not export its output.
SELECT player_telegram_id
  FROM analytics_players
 WHERE id = :analytics_player_id
   AND NOT is_internal;
