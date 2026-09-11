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

Confirm `getWebhookInfo` reports the expected URL and no recent delivery error. Then send `/start`, select an alliance, claim `/daily`, inspect the starter presets with `/army`, open an illustrated card through `/shop`, verify the 1/5/25 purchase buttons, and upgrade a unit with `/upgrade`. Run `/battle`; verify the upright rectangular 9×12 field inside the square image, distinct A–C/X–Z edge entries, seamless hexes, connected roads, mixed terrain regions, and a `?v=<mapVersion>` cache-busting suffix in the delivered image URL. Choose an entry and first objective, choose a behavior doctrine, and confirm the result reports its CP category, both deployed strengths, battle steps, end reason, remaining units, objective control, and returned/lost equipment. Then inspect `/profile`, commit the active personal group with `/contribute`, verify that it cannot enter a personal battle while reserved, and inspect `/front`, including its upright 15×21 illustrated map.

The weekly resolver runs at Sunday 15:00 in `Europe/Belgrade`. A ten-minute recovery job resolves any overdue open campaign after downtime, and notification delivery retries from the PostgreSQL outbox. Production health checks should confirm the application clock uses the configured timezone and Flyway has applied migrations through V11. V8 removes only unresolved v1 pairings from open weeks and regenerates them as the 250-country ranked schedule; V9 upgrades unresolved weekly battles to 96 turns. Application recovery refreshes unresolved matchups to current 15×21 map version 4 without changing pairings or contributions. `/battle` and `/front` append the map version to image URLs so Telegram does not reuse pre-v4 cached maps in current messages. V11 rebases live Credit balances at 1:100, recalculates levels from retained total XP, and adds contributor performance plus weekly winner bonuses. Resolved results and rewards remain audit history. After command or locale changes, rerun `scripts/set-telegram-webhook.sh` so Telegram receives the localized command menus.
