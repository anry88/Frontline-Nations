#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf 'Usage: %s --env-file /absolute/path/to/prod.env [--tag TAG]\n' "$0"
}

env_file=""
image_tag=""
remote_host="${HDC_REMOTE_HOST:-hdc}"
docker_context="${HDC_DOCKER_CONTEXT:-hdc}"
windows_root="${HDC_FRONTLINE_ROOT:-D:\\Apps\\FrontlineNations}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) env_file="$2"; shift 2 ;;
    --tag) image_tag="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; usage; exit 1 ;;
  esac
done

[[ -n "$env_file" && -f "$env_file" ]] || { usage; exit 1; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -z "$image_tag" ]]; then
  image_tag="hdc-$(git -C "$repo_root" rev-parse --short HEAD)"
fi

for name in TELEGRAM_BOT_TOKEN TELEGRAM_WEBHOOK_SECRET BATTLE_SERVER_SALT POSTGRES_PASSWORD; do
  grep -q "^${name}=." "$env_file" || { printf 'Missing %s in %s\n' "$name" "$env_file" >&2; exit 1; }
done

remote_deploy="$windows_root\\deploy\\prod"
remote_state="$windows_root\\state\\prod"
remote_env="$windows_root\\env\\prod.env"
image="frontline-nations:$image_tag"

printf '[frontline-hdc] Building %s on Docker context %s\n' "$image" "$docker_context"
docker --context "$docker_context" build --platform linux/amd64 --tag "$image" "$repo_root"

printf '[frontline-hdc] Preparing remote directories\n'
ssh "$remote_host" "powershell -NoProfile -Command \"New-Item -ItemType Directory -Force -Path '$remote_deploy','$remote_state\\postgres','$windows_root\\env' | Out-Null\""

scp "$repo_root/compose.yml" "$remote_host:$(printf '%s' "${remote_deploy//\\//}")/compose.yml"
scp "$env_file" "$remote_host:$(printf '%s' "${remote_env//\\//}")"

printf '[frontline-hdc] Starting application and PostgreSQL\n'
ssh "$remote_host" "powershell -NoProfile -Command \"\$env:FRONTLINE_IMAGE='$image'; Set-Location -LiteralPath '$remote_deploy'; docker compose --env-file '$remote_env' -f compose.yml up -d --remove-orphans\""

printf '[frontline-hdc] Waiting for container health\n'
for _ in {1..24}; do
  status="$(ssh "$remote_host" "powershell -NoProfile -Command \"docker inspect frontline-nations-prod-app-1 --format '{{.State.Health.Status}}' 2>\$null\"" | tr -d '\r' || true)"
  [[ "$status" == "healthy" ]] && break
  sleep 5
done

[[ "$status" == "healthy" ]] || { printf 'Application did not become healthy; status=%s\n' "$status" >&2; exit 1; }
printf '[frontline-hdc] Deployment healthy: %s\n' "$image"
