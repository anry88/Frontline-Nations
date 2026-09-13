# Home Data Center Deployment

Frontline Nations production runs as an isolated Docker Compose project on the shared Home Data Center host.

## Endpoints

- Public URL: `https://frontline-nations.tg-games.com`
- Telegram webhook: `https://frontline-nations.tg-games.com/bot`
- Cloudflare Tunnel origin: `http://frontline-nations-prod-app:8080`
- Telegram bot: `@frontline_nations_bot`

## Runtime Layout

```text
D:\Apps\FrontlineNations\env\prod.env
D:\Apps\FrontlineNations\state\prod\postgres\
D:\Apps\FrontlineNations\data\replays\
D:\Apps\FrontlineNations\deploy\prod\compose.yml
```

The environment file contains the Telegram token, webhook secret, private `TELEGRAM_ADMIN_CHAT_ID`, battle salt, and PostgreSQL password. It must never be committed. The payment-support chat ID is the same private admin identity used by RiverKing; the bot requires both the chat and message author to match it before accepting refund commands.

## Telegram Stars

Flyway must be applied through V17 before purchases, campaign-level referral reporting, and first-objective front orders are enabled. Open `/stars`, confirm all five packages and a native `XTR` invoice, and use a controlled purchase to verify one charge creates one Credit ledger entry even after webhook retry. Run `/paysupport` and confirm the request reaches only the configured private admin chat; verify `/ask`, `/reject`, and a controlled `/refund` notify the player and reverse Credits.

## Observability

The public bot port remains `8080`; Spring Actuator and Prometheus are exposed only inside Docker on management port `9090`. Add this private scrape target to the existing VictoriaMetrics configuration:

```yaml
- job_name: frontline_nations_prod
  metrics_path: /actuator/prometheus
  static_configs:
    - targets: [frontline-nations-prod-app:9090]
```

Import `docs/grafana/dashboard.json` into Grafana and select the VictoriaMetrics datasource. The dashboard combines persistent database gauges with event counters for commands, callbacks, Telegram Stars purchases, equipment operations, battles, and registration attribution. Registration gauges expose `telegram` separately and use normalized, bounded Telegram start parameters such as `riverking` for individual referral campaigns; only the 24 largest active referral series are published and the remainder is grouped as `other`.

## Deploy

Build the image using the `hdc` Docker context, copy `compose.yml` and a production environment file to the host, then run Compose from `D:\Apps\FrontlineNations\deploy\prod`.

The app joins the existing external `hdc-tunnel` network with alias `frontline-nations-prod-app`. Configure the Cloudflare Tunnel public hostname to send `frontline-nations.tg-games.com` to `http://frontline-nations-prod-app:8080`.

After the public health endpoint succeeds, run `scripts/set-telegram-webhook.sh` from a shell containing the production secrets. On the Windows HDC host, use `scripts/set-telegram-webhook.ps1 -EnvFile D:\Apps\FrontlineNations\env\prod.env`; it reads secrets in place and synchronizes the webhook and localized command menus without copying the environment file. The Bash script also installs the bot name, descriptions, and generated avatar from `assets/brand/`.

## Verification

```bash
curl --fail https://frontline-nations.tg-games.com/health
curl --fail https://frontline-nations.tg-games.com/
```

Confirm `getWebhookInfo` reports the expected URL and no recent delivery error. Then send `/start`, select an alliance, claim `/daily`, and verify the gift button disappears from later main keyboards that day. Inspect the starter presets with `/army`; reserved front equipment must not appear as an available add/remove choice. Open an illustrated card through `/shop`, verify unlocked classes precede locked classes, characteristics use label-value rows, the 1/5/25 purchase buttons keep the player in the shop, and `/upgrade` is present in the main keyboard. Run `/battle`; verify the upright rectangular 9×12 field inside the square image, distinct A–C/X–Z edge entries, seamless hexes, connected roads, mixed terrain regions, and a `?v=<mapVersion>` cache-busting suffix in the delivered image URL. Choose an entry and first objective, choose a behavior doctrine, and confirm the result reports its CP category, both deployed strengths, battle steps, end reason, remaining units, objective control, and returned/lost equipment. Press `▶️ Battle replay`; confirm Telegram plays a square video at the slower 3 FPS pace with moving equipment icons, fire, damage/loss markers, and changing objective control. Then inspect `/profile`, `/rankings`, and every page of `/guide`. In `/contribute`, send two distinct presets with different map-marked entries, first objectives, and tactics, verify both are listed in `/front`, withdraw one, and verify only its equipment becomes available again. After a weekly result produced by engine v7, verify `/front` includes the aggregate replay button and both result text and the country-specific replay reach a non-contributing member of each participating country.

The weekly resolver runs at Sunday 15:00 UTC. A ten-minute recovery trigger enqueues a dedicated worker that resolves overdue campaigns, prepares one shared replay per matchup, and delivers each outbox recipient sequentially without blocking bot commands. The backend image includes FFmpeg; replay MP4s default to 3 FPS, are cached under `/var/cache/frontline-replays`, and are served only through signed URLs. Production binds that path to `D:\Apps\FrontlineNations\data\replays` through `REPLAY_DATA_DIR`, keeping rendered media off the system disk and across app-container replacement. Personal artifacts default to 48 hours; weekly artifacts are retained for at least 168 hours. Production health checks should confirm `GAME_TIMEZONE=UTC` and Flyway has applied migrations through V17. V8 removes only unresolved v1 pairings from open weeks and regenerates them as the 250-country ranked schedule; V9 upgrades unresolved weekly battles to 96 turns. Application recovery refreshes unresolved matchups to current 15×21 map version 5 without changing pairings or contributions. `/battle` and `/front` append the map version to image URLs so Telegram does not reuse pre-v5 cached maps in current messages. V11 rebases live Credit balances at 1:100, recalculates levels from retained total XP, and adds contributor performance plus weekly winner bonuses. V12 adds per-preset contribution orders and concrete unit membership while preserving existing open reservations. V13 stages result and replay delivery separately and suppresses broadcasts to permanently unreachable Telegram accounts until they contact the bot again. V14 adds audited Stars charges and payment-support state. V15 records direct/referral registration classes; V16 stores normalized campaign codes and exports bounded campaign-level gauges. V17 adds nullable first-objective orders without modifying active legacy contributions. Resolved results and rewards remain audit history. After command or locale changes, rerun `scripts/set-telegram-webhook.sh` so Telegram receives the localized command menus and English bot description.
