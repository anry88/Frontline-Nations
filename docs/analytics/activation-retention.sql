-- Frontline Nations activation and retention baseline (AND-26)
--
-- Coverage begins with V20. Missing historical steps stay missing. The default
-- views below exclude confirmed internal accounts. Use the *_all views only for
-- an explicit internal-vs-external audit.
--
-- Telegram does not expose a reliable "opened the bot before /start" event.
-- That acquisition step is NOT MEASURED and must not be inserted into this funnel.

-- Coverage and sample size. Treat segments with n < 30 as descriptive only.
SELECT MIN(registered_at) AS registration_coverage_start,
       MAX(registered_at) AS registration_coverage_end,
       COUNT(*) AS external_registrations,
       COUNT(*) < 30 AS small_sample
  FROM analytics_player_activation;

-- Activation funnel, split by onboarding variant. The guided first-mission card
-- bypasses the five-offer list, so offer_viewed is a regular-path-only step:
-- guided_v1 players go from country selection straight to deployment, while
-- legacy players go through the offer list. Never read offer_viewed as a
-- mandatory guided step. Registration and country selection are both shown as
-- explicit denominators per variant. Daily/shop/upgrade/replay/front/contribution are
-- separate branches and are deliberately absent from the mandatory sequence.
WITH variant AS (
    SELECT *,
           CASE WHEN onboarding_variant IN ('guided_v1', 'legacy')
                THEN onboarding_variant ELSE 'unassigned' END AS funnel_variant
      FROM analytics_player_activation
), counts AS (
    SELECT funnel_variant, 1 AS step_order, 'registration' AS step, COUNT(*) AS players FROM variant GROUP BY 1
    UNION ALL SELECT funnel_variant, 2, 'country_selected', COUNT(*) FROM variant WHERE country_selected_at IS NOT NULL GROUP BY 1
    UNION ALL SELECT funnel_variant, 3, 'offer_viewed', COUNT(*) FROM variant WHERE offer_viewed_at IS NOT NULL GROUP BY 1
    UNION ALL SELECT funnel_variant, 4, 'deployment_completed', COUNT(*) FROM variant WHERE deployment_completed_at IS NOT NULL GROUP BY 1
    UNION ALL SELECT funnel_variant, 5, 'first_battle_started', COUNT(*) FROM variant WHERE first_battle_started_at IS NOT NULL GROUP BY 1
    UNION ALL SELECT funnel_variant, 6, 'first_battle_finished', COUNT(*) FROM variant WHERE first_battle_finished_at IS NOT NULL GROUP BY 1
    UNION ALL SELECT funnel_variant, 7, 'result_sent', COUNT(*) FROM variant WHERE first_result_sent_at IS NOT NULL GROUP BY 1
    UNION ALL SELECT funnel_variant, 8, 'second_battle_started', COUNT(*) FROM variant WHERE second_battle_started_at IS NOT NULL GROUP BY 1
), denominators AS (
    SELECT funnel_variant,
           MAX(players) FILTER (WHERE step = 'registration') AS registrations,
           MAX(players) FILTER (WHERE step = 'country_selected') AS country_selected
      FROM counts
     GROUP BY funnel_variant
)
SELECT counts.funnel_variant AS variant,
       step,
       players,
       LAG(players) OVER (PARTITION BY counts.funnel_variant ORDER BY step_order) - players AS dropoff_from_previous,
       ROUND(players::numeric / NULLIF(LAG(players) OVER (PARTITION BY counts.funnel_variant ORDER BY step_order), 0), 4) AS conversion_from_previous,
       ROUND(players::numeric / NULLIF(denominators.registrations, 0), 4) AS conversion_from_registration,
       ROUND(players::numeric / NULLIF(denominators.country_selected, 0), 4) AS conversion_from_country_selected,
       players < 30 AS small_sample
  FROM counts
  JOIN denominators USING (funnel_variant)
 ORDER BY variant, step_order;

-- Time to first distinct battle and players who never started one.
SELECT percentile_cont(0.50) WITHIN GROUP (ORDER BY ttfb_ms) / 1000.0 AS ttfb_p50_seconds,
       percentile_cont(0.90) WITHIN GROUP (ORDER BY ttfb_ms) / 1000.0 AS ttfb_p90_seconds,
       COUNT(*) FILTER (WHERE first_battle_started_at IS NULL) AS never_started,
       COUNT(*) AS registration_denominator
  FROM analytics_player_activation;

-- The two repeat definitions are intentionally separate.
SELECT COUNT(*) FILTER (WHERE second_battle_within_24h_after_finish) AS second_battle_within_24h_after_first_finish,
       COUNT(*) FILTER (WHERE second_battle_within_24h_after_registration) AS second_battle_within_24h_after_registration,
       COUNT(*) FILTER (WHERE first_battle_finished_at IS NOT NULL) AS first_finish_denominator,
       COUNT(*) AS registration_denominator
  FROM analytics_player_activation;

-- Exact-window D1/D3/D7. Immature cohorts do not enter the denominator.
SELECT registration_cohort,
       COUNT(*) FILTER (WHERE d1_matured) AS d1_matured,
       COUNT(*) FILTER (WHERE d1_matured AND d1_retained) AS d1_retained,
       ROUND(COUNT(*) FILTER (WHERE d1_matured AND d1_retained)::numeric /
             NULLIF(COUNT(*) FILTER (WHERE d1_matured), 0), 4) AS d1,
       COUNT(*) FILTER (WHERE d3_matured) AS d3_matured,
       COUNT(*) FILTER (WHERE d3_matured AND d3_retained) AS d3_retained,
       ROUND(COUNT(*) FILTER (WHERE d3_matured AND d3_retained)::numeric /
             NULLIF(COUNT(*) FILTER (WHERE d3_matured), 0), 4) AS d3,
       COUNT(*) FILTER (WHERE d7_matured) AS d7_matured,
       COUNT(*) FILTER (WHERE d7_matured AND d7_retained) AS d7_retained,
       ROUND(COUNT(*) FILTER (WHERE d7_matured AND d7_retained)::numeric /
             NULLIF(COUNT(*) FILTER (WHERE d7_matured), 0), 4) AS d7,
       COUNT(*) < 30 AS small_sample
  FROM analytics_retention_cohorts
 GROUP BY registration_cohort
 ORDER BY registration_cohort;

-- Bounded diagnostic cuts. Add WHERE predicates for a focused cohort instead
-- of publishing this cross-product as Prometheus labels.
SELECT registration_cohort,
       registration_source,
       COALESCE(registration_referral, 'direct') AS registration_referral,
       locale,
       COALESCE(country_code, 'not_selected') AS country_code,
       COALESCE(first_battle_result, 'not_finished') AS first_battle_result,
       COALESCE(onboarding_variant, 'unassigned') AS onboarding_variant,
       COUNT(*) AS players,
       COUNT(*) FILTER (WHERE first_battle_started_at IS NOT NULL) AS activated,
       COUNT(*) FILTER (WHERE second_battle_within_24h_after_finish) AS repeated_after_finish,
       AVG(first_battle_lost_units) FILTER (WHERE first_battle_lost_units IS NOT NULL) AS average_first_battle_losses,
       COUNT(*) < 30 AS small_sample
  FROM analytics_player_activation
 GROUP BY registration_cohort, registration_source, registration_referral, locale,
          country_code, first_battle_result, onboarding_variant
 ORDER BY registration_cohort, players DESC;

-- Observed stopping context. session_inactive_30m is a session boundary, not
-- proof of churn. last_activation_stage alone must not be presented as a reason.
SELECT last_activation_stage,
       observed_technical_context,
       COUNT(*) AS players,
       COUNT(*) FILTER (WHERE returned_in_later_session) AS returned_players,
       ROUND(COUNT(*) FILTER (WHERE returned_in_later_session)::numeric / NULLIF(COUNT(*), 0), 4) AS return_share,
       COUNT(*) FILTER (WHERE session_inactive_30m) AS inactive_sessions,
       COUNT(*) < 30 AS small_sample
  FROM analytics_dropoff_report
 GROUP BY last_activation_stage, observed_technical_context
 ORDER BY players DESC, last_activation_stage, observed_technical_context;

-- Restricted diagnostic sequence. This view contains only the surrogate
-- analytics identity and bounded event context; it has no Telegram player ID,
-- chat text, raw payload, nickname, or invented device/client fields.
SELECT *
  FROM analytics_external_event_sequences
 WHERE analytics_player_id = :analytics_player_id
 ORDER BY session_id, event_order;

-- Feedback collection starts at the campaign row's started_at boundary. These
-- aggregate views exclude internal accounts and never expose comment text.
SELECT campaign_id,
       COUNT(*) FILTER (WHERE inline_offered_at IS NOT NULL) AS inline_offered,
       COUNT(*) FILTER (WHERE nudge_sent_at IS NOT NULL) AS nudge_sent,
       COUNT(*) FILTER (WHERE opened_at IS NOT NULL) AS opened,
       COUNT(*) FILTER (WHERE answered_at IS NOT NULL) AS answered,
       COUNT(*) FILTER (WHERE skipped_at IS NOT NULL) AS skipped,
       COUNT(*) FILTER (WHERE commented) AS commented,
       COUNT(*) < 30 AS small_sample
  FROM analytics_feedback_responses
 GROUP BY campaign_id
 ORDER BY campaign_id;

SELECT campaign_id, response_surface, nudge_trigger, response_reason,
       COUNT(*) AS players, COUNT(*) < 30 AS small_sample
  FROM analytics_feedback_responses
 WHERE response_reason IS NOT NULL
 GROUP BY campaign_id, response_surface, nudge_trigger, response_reason
 ORDER BY campaign_id, players DESC;

-- Restricted qualitative review: surrogate identity only, no Telegram ID.
SELECT *
  FROM analytics_feedback_comments_restricted
 ORDER BY updated_at DESC;
