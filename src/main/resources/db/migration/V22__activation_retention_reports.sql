CREATE VIEW analytics_player_activation_all AS
WITH registration AS (
    SELECT DISTINCT ON (analytics_player_id)
           analytics_player_id,
           is_internal,
           occurred_at AS registered_at,
           registration_source,
           registration_referral,
           locale
      FROM player_journey_events
     WHERE event_name = 'registration_completed'
     ORDER BY analytics_player_id, occurred_at, id
),
distinct_battle_starts AS (
    SELECT analytics_player_id, battle_id, MIN(occurred_at) AS started_at
      FROM player_journey_events
     WHERE event_name = 'battle_started'
       AND battle_id IS NOT NULL
     GROUP BY analytics_player_id, battle_id
),
ranked_battle_starts AS (
    SELECT analytics_player_id,
           battle_id,
           started_at,
           ROW_NUMBER() OVER (PARTITION BY analytics_player_id ORDER BY started_at, battle_id) AS battle_number
      FROM distinct_battle_starts
),
battle_milestones AS (
    SELECT analytics_player_id,
           (MAX(battle_id::text) FILTER (WHERE battle_number = 1))::uuid AS first_battle_id,
           MAX(started_at) FILTER (WHERE battle_number = 1) AS first_battle_started_at,
           MAX(started_at) FILTER (WHERE battle_number = 2) AS second_battle_started_at
      FROM ranked_battle_starts
     WHERE battle_number <= 2
     GROUP BY analytics_player_id
)
SELECT r.analytics_player_id,
       r.is_internal,
       r.registered_at,
       (r.registered_at AT TIME ZONE 'UTC')::date AS registration_cohort,
       r.registration_source,
       r.registration_referral,
       r.locale,
       country.country_code,
       cta.variant AS onboarding_variant,
       country.occurred_at AS country_selected_at,
       offer.occurred_at AS offer_viewed_at,
       deployment.occurred_at AS deployment_completed_at,
       battles.first_battle_id,
       battles.first_battle_started_at,
       finish.occurred_at AS first_battle_finished_at,
       finish.result AS first_battle_result,
       finish.quantity AS first_battle_lost_units,
       sent.occurred_at AS first_result_sent_at,
       battles.second_battle_started_at,
       CASE
           WHEN battles.second_battle_started_at >= finish.occurred_at
            AND battles.second_battle_started_at < finish.occurred_at + INTERVAL '24 hours'
           THEN TRUE ELSE FALSE
       END AS second_battle_within_24h_after_finish,
       CASE
           WHEN battles.second_battle_started_at >= r.registered_at
            AND battles.second_battle_started_at < r.registered_at + INTERVAL '24 hours'
           THEN TRUE ELSE FALSE
       END AS second_battle_within_24h_after_registration,
       CASE
           WHEN battles.first_battle_started_at IS NULL THEN NULL
           ELSE (EXTRACT(EPOCH FROM (battles.first_battle_started_at - r.registered_at)) * 1000)::bigint
       END AS ttfb_ms
  FROM registration r
  LEFT JOIN battle_milestones battles ON battles.analytics_player_id = r.analytics_player_id
  LEFT JOIN LATERAL (
      SELECT occurred_at, country_code
        FROM player_journey_events
       WHERE analytics_player_id = r.analytics_player_id
         AND event_name = 'onboarding_country_selected'
         AND occurred_at >= r.registered_at
       ORDER BY occurred_at, id
       LIMIT 1
  ) country ON TRUE
  LEFT JOIN LATERAL (
      SELECT occurred_at, variant
        FROM player_journey_events
       WHERE analytics_player_id = r.analytics_player_id
         AND event_name = 'onboarding_cta_viewed'
         AND occurred_at >= r.registered_at
       ORDER BY occurred_at, id
       LIMIT 1
  ) cta ON TRUE
  LEFT JOIN LATERAL (
      SELECT occurred_at
        FROM player_journey_events
       WHERE analytics_player_id = r.analytics_player_id
         AND event_name = 'battle_offer_viewed'
         AND occurred_at >= r.registered_at
       ORDER BY occurred_at, id
       LIMIT 1
  ) offer ON TRUE
  LEFT JOIN LATERAL (
      SELECT occurred_at
        FROM player_journey_events
       WHERE analytics_player_id = r.analytics_player_id
         AND event_name = 'battle_deployment_selected'
         AND surface = 'tactic'
         AND occurred_at >= r.registered_at
       ORDER BY occurred_at, id
       LIMIT 1
  ) deployment ON TRUE
  LEFT JOIN LATERAL (
      SELECT occurred_at, result, quantity
        FROM player_journey_events
       WHERE analytics_player_id = r.analytics_player_id
         AND event_name = 'battle_finished'
         AND battle_id = battles.first_battle_id
       ORDER BY occurred_at, id
       LIMIT 1
  ) finish ON TRUE
  LEFT JOIN LATERAL (
      SELECT stages.occurred_at
        FROM technical_operation_attempts attempts
        JOIN technical_operation_stages stages ON stages.attempt_id = attempts.attempt_id
       WHERE attempts.operation = 'battle'
         AND attempts.resource_id = battles.first_battle_id
         AND stages.stage = 'result_sent'
         AND stages.result = 'success'
       ORDER BY stages.occurred_at, stages.id
       LIMIT 1
  ) sent ON TRUE;

CREATE VIEW analytics_player_activation AS
SELECT *
  FROM analytics_player_activation_all
 WHERE NOT is_internal;

CREATE VIEW analytics_retention_cohorts_all AS
SELECT activation.*,
       CURRENT_TIMESTAMP >= registered_at + INTERVAL '2 days' AS d1_matured,
       EXISTS (
           SELECT 1 FROM player_journey_events events
            WHERE events.analytics_player_id = activation.analytics_player_id
              AND events.event_name = 'user_action'
              AND events.occurred_at >= activation.registered_at + INTERVAL '1 day'
              AND events.occurred_at < activation.registered_at + INTERVAL '2 days'
       ) AS d1_retained,
       CURRENT_TIMESTAMP >= registered_at + INTERVAL '4 days' AS d3_matured,
       EXISTS (
           SELECT 1 FROM player_journey_events events
            WHERE events.analytics_player_id = activation.analytics_player_id
              AND events.event_name = 'user_action'
              AND events.occurred_at >= activation.registered_at + INTERVAL '3 days'
              AND events.occurred_at < activation.registered_at + INTERVAL '4 days'
       ) AS d3_retained,
       CURRENT_TIMESTAMP >= registered_at + INTERVAL '8 days' AS d7_matured,
       EXISTS (
           SELECT 1 FROM player_journey_events events
            WHERE events.analytics_player_id = activation.analytics_player_id
              AND events.event_name = 'user_action'
              AND events.occurred_at >= activation.registered_at + INTERVAL '7 days'
              AND events.occurred_at < activation.registered_at + INTERVAL '8 days'
       ) AS d7_retained
  FROM analytics_player_activation_all activation;

CREATE VIEW analytics_retention_cohorts AS
SELECT *
  FROM analytics_retention_cohorts_all
 WHERE NOT is_internal;

CREATE VIEW analytics_dropoff_report_all AS
WITH user_activity AS (
    SELECT analytics_player_id,
           MAX(occurred_at) FILTER (WHERE event_name = 'user_action') AS last_user_action_at,
           COUNT(DISTINCT session_id) FILTER (WHERE event_name = 'user_action') AS session_count
      FROM player_journey_events
     GROUP BY analytics_player_id
)
SELECT activation.*,
       CASE
           WHEN activation.first_result_sent_at IS NOT NULL THEN 'result_sent'
           WHEN activation.first_battle_finished_at IS NOT NULL THEN 'first_battle_finished'
           WHEN activation.first_battle_started_at IS NOT NULL THEN 'first_battle_started'
           WHEN activation.deployment_completed_at IS NOT NULL THEN 'deployment_completed'
           WHEN activation.offer_viewed_at IS NOT NULL THEN 'offer_viewed'
           WHEN activation.country_selected_at IS NOT NULL THEN 'country_selected'
           ELSE 'registration'
       END AS last_activation_stage,
       activity.last_user_action_at,
       COALESCE(activity.session_count, 0) AS session_count,
       COALESCE(activity.session_count, 0) > 1 AS returned_in_later_session,
       activity.last_user_action_at < CURRENT_TIMESTAMP - INTERVAL '30 minutes' AS session_inactive_30m,
       technical.last_stage AS last_technical_stage,
       CASE
           WHEN technical.attempt_id IS NULL THEN 'none'
           WHEN technical.completed_at IS NULL THEN 'incomplete_attempt'
           WHEN technical.failure_class IS NOT NULL THEN technical.failure_class
           ELSE 'none'
       END AS observed_technical_context
  FROM analytics_player_activation_all activation
  LEFT JOIN user_activity activity ON activity.analytics_player_id = activation.analytics_player_id
  LEFT JOIN LATERAL (
      SELECT attempts.attempt_id, attempts.last_stage, attempts.completed_at, attempts.failure_class
        FROM technical_operation_attempts attempts
       WHERE attempts.session_id IN (
           SELECT DISTINCT events.session_id
             FROM player_journey_events events
            WHERE events.analytics_player_id = activation.analytics_player_id
       )
       ORDER BY COALESCE(attempts.last_stage_at, attempts.started_at) DESC, attempts.attempt_id
       LIMIT 1
  ) technical ON TRUE;

CREATE VIEW analytics_dropoff_report AS
SELECT *
  FROM analytics_dropoff_report_all
 WHERE NOT is_internal;

CREATE VIEW analytics_external_event_sequences AS
SELECT events.analytics_player_id,
       events.session_id,
       ROW_NUMBER() OVER (PARTITION BY events.session_id ORDER BY events.occurred_at, events.id) AS event_order,
       events.event_name,
       events.schema_version,
       events.occurred_at,
       events.registration_source,
       events.registration_referral,
       events.locale,
       events.country_code,
       events.surface,
       events.result,
       events.reason,
       events.variant,
       events.offer_slot,
       events.preset_no,
       events.used_cp,
       events.entry_id,
       events.objective_id,
       events.tactic,
       events.unit_code,
       events.quantity
  FROM player_journey_events events
 WHERE NOT events.is_internal;

COMMENT ON VIEW analytics_player_activation IS
    'External activation funnel based only on events captured after V20. A distinct battle_id counts once even when callbacks repeat.';

COMMENT ON VIEW analytics_retention_cohorts IS
    'External exact-window retention: Dk is a user_action in [k*24h,(k+1)*24h); immature windows remain excluded by d*_matured.';

COMMENT ON VIEW analytics_dropoff_report IS
    'Observed last stage and technical context. Thirty minutes ends a session; it does not prove churn or a reason for leaving.';

COMMENT ON VIEW analytics_external_event_sequences IS
    'Restricted diagnostic sequence using surrogate identities only; no Telegram player identity, chat text, raw payload, or device guesses.';
