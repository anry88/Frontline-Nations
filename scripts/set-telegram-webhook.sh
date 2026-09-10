#!/usr/bin/env bash
set -euo pipefail

: "${TELEGRAM_BOT_TOKEN:?TELEGRAM_BOT_TOKEN is required}"
: "${TELEGRAM_WEBHOOK_SECRET:?TELEGRAM_WEBHOOK_SECRET is required}"

public_base_url="${PUBLIC_BASE_URL:-https://frontline-nations.tg-games.com}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
avatar="$repo_root/assets/brand/frontline-nations-bot-avatar.jpg"

api_url="https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}"

curl --fail --silent --show-error \
  --request POST \
  --data-urlencode 'name=Frontline Nations' \
  "$api_url/setMyName" >/dev/null

curl --fail --silent --show-error \
  --request POST \
  --data-urlencode 'commands=[{"command":"start","description":"Начать игру и выбрать альянс"},{"command":"battle","description":"Выбрать операцию и тактику"},{"command":"profile","description":"Прогресс, статистика и ресурсы"},{"command":"front","description":"Состояние недельного фронта"},{"command":"contribute","description":"Передать Credits на фронт"},{"command":"help","description":"Справка по командам"}]' \
  "$api_url/setMyCommands" >/dev/null

curl --fail --silent --show-error \
  --request POST \
  --data-urlencode 'description=Асинхронная стратегия: короткие операции, развитие командира и общий недельный фронт альянсов.' \
  "$api_url/setMyDescription" >/dev/null

curl --fail --silent --show-error \
  --request POST \
  --data-urlencode 'short_description=Командуй армией и усиливай свой альянс.' \
  "$api_url/setMyShortDescription" >/dev/null

if [[ -f "$avatar" ]]; then
  curl --fail --silent --show-error \
    --request POST \
    --form 'photo={"type":"static","photo":"attach://avatar"}' \
    --form "avatar=@$avatar;type=image/jpeg" \
    "$api_url/setMyProfilePhoto" >/dev/null
fi

curl --fail --silent --show-error \
  --request POST \
  --data-urlencode "url=${public_base_url%/}/bot" \
  --data-urlencode "secret_token=${TELEGRAM_WEBHOOK_SECRET}" \
  --data-urlencode 'allowed_updates=["message","callback_query"]' \
  "$api_url/setWebhook"
printf '\n'
