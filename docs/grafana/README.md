# Frontline Nations observability

Import `dashboard.json` into Grafana and select the Prometheus-compatible datasource and scrape job for the production bot. The layout follows the operating pattern used by RiverKing, while its views and metric definitions are specific to Frontline Nations.

Prometheus must scrape `GET /actuator/prometheus` on management port `9090` of the application container. The public game and webhook server remains on port `8080`, so the metrics endpoint is not routed through the public domain. The dashboard intentionally combines two kinds of measurements:

- event counters for commands, inline-button actions, checkout stages, equipment actions, and personal-battle results;
- database-backed gauges refreshed once per minute for player activity, registration attribution, Stars payments, equipment transactions, and battle outcomes.

Database gauges remain correct after an application restart. Event panels describe traffic observed by Prometheus and should be read over the selected dashboard time range. Registration sources are deliberately bounded to `telegram` for a direct start and `referral` for a `/start` link carrying a payload; the payload itself is never exported as a metric label.

Recommended scrape configuration:

```yaml
scrape_configs:
  - job_name: frontline_nations_prod
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: [frontline-nations-prod-app:9090]
```

Keep the metrics endpoint on the private Docker network or protect it at the reverse proxy. Do not expose production metrics publicly.
