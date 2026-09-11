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
D:\Apps\FrontlineNations\deploy\prod\compose.yml
```

The environment file contains the Telegram token, webhook secret, battle salt, and PostgreSQL password. It must never be committed.

## Deploy

Build the image using the `hdc` Docker context, copy `compose.yml` and a production environment file to the host, then run Compose from `D:\Apps\FrontlineNations\deploy\prod`.

The app joins the existing external `hdc-tunnel` network with alias `frontline-nations-prod-app`. Configure the Cloudflare Tunnel public hostname to send `frontline-nations.tg-games.com` to `http://frontline-nations-prod-app:8080`.

After the public health endpoint succeeds, run `scripts/set-telegram-webhook.sh` from a shell containing the production secrets. The script installs the command menu, bot name, descriptions, generated avatar from `assets/brand/`, and the webhook.

## Verification

```bash
curl --fail https://frontline-nations.tg-games.com/health
curl --fail https://frontline-nations.tg-games.com/
```

Confirm `getWebhookInfo` reports the expected URL and no recent delivery error. Then send `/start`, select an alliance, run `/battle`, choose an operation and tactic, inspect the round highlights and `/profile`, contribute with `/contribute 100`, and inspect `/front`.

The weekly resolver runs at Sunday 15:00 in `Europe/Belgrade`. A ten-minute recovery job resolves any overdue open campaign after downtime, and notification delivery retries from the PostgreSQL outbox. Production health checks should confirm the application clock uses the configured timezone and Flyway has applied migrations through V4. After locale changes, rerun `scripts/set-telegram-webhook.sh` so Telegram receives the localized command menus.
