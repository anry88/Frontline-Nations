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

After the public health endpoint succeeds, run `scripts/set-telegram-webhook.sh` from a shell containing the production secrets. On the Windows HDC host, use `scripts/set-telegram-webhook.ps1 -EnvFile D:\Apps\FrontlineNations\env\prod.env`; it reads secrets in place and synchronizes the webhook and localized command menus without copying the environment file. The Bash script also installs the bot name, descriptions, and generated avatar from `assets/brand/`.

## Verification

```bash
curl --fail https://frontline-nations.tg-games.com/health
curl --fail https://frontline-nations.tg-games.com/
```

Confirm `getWebhookInfo` reports the expected URL and no recent delivery error. Then send `/start`, select an alliance, claim `/daily`, and verify the gift button disappears from later main keyboards that day. Inspect the starter presets with `/army`; reserved front equipment must not appear as an available add/remove choice. Open an illustrated card through `/shop`, verify unlocked classes precede locked classes, characteristics use label-value rows, the 1/5/25 purchase buttons keep the player in the shop, and `/upgrade` is present in the main keyboard. Run `/battle`; verify the upright rectangular 9×12 field inside the square image, distinct A–C/X–Z edge entries, seamless hexes, connected roads, mixed terrain regions, and a `?v=<mapVersion>` cache-busting suffix in the delivered image URL. Choose an entry and first objective, choose a behavior doctrine, and confirm the result reports its CP category, both deployed strengths, battle steps, end reason, remaining units, objective control, and returned/lost equipment. Press `▶️ Battle replay`; confirm Telegram plays a square video at the slower 3 FPS pace with moving equipment icons, fire, damage/loss markers, and changing objective control. Then inspect `/profile`, `/rankings`, and every page of `/guide`. In `/contribute`, send two distinct presets with different entries/tactics, verify both are listed in `/front`, withdraw one, and verify only its equipment becomes available again. After a weekly result produced by engine v6, verify `/front` includes the aggregate replay button and both result text and the country-specific replay reach a non-contributing member of each participating country.

The weekly resolver runs at Sunday 15:00 in `Europe/Belgrade`. A ten-minute recovery trigger enqueues a dedicated worker that resolves overdue campaigns, renders replays, and delivers each outbox recipient sequentially without blocking bot commands. The backend image includes FFmpeg; replay MP4s default to 3 FPS, are cached under `/tmp/frontline-replays`, served only through signed URLs, and expired after 48 hours. Production health checks should confirm the application clock uses the configured timezone and Flyway has applied migrations through V13. V8 removes only unresolved v1 pairings from open weeks and regenerates them as the 250-country ranked schedule; V9 upgrades unresolved weekly battles to 96 turns. Application recovery refreshes unresolved matchups to current 15×21 map version 5 without changing pairings or contributions. `/battle` and `/front` append the map version to image URLs so Telegram does not reuse pre-v5 cached maps in current messages. V11 rebases live Credit balances at 1:100, recalculates levels from retained total XP, and adds contributor performance plus weekly winner bonuses. V12 adds per-preset contribution orders and concrete unit membership while preserving existing open reservations. V13 stages result and replay delivery separately and suppresses broadcasts to permanently unreachable Telegram accounts until they contact the bot again. Resolved results and rewards remain audit history. After command or locale changes, rerun `scripts/set-telegram-webhook.sh` so Telegram receives the localized command menus.
