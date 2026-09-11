#!/usr/bin/env bash
set -euo pipefail

: "${TELEGRAM_BOT_TOKEN:?TELEGRAM_BOT_TOKEN is required}"
: "${TELEGRAM_WEBHOOK_SECRET:?TELEGRAM_WEBHOOK_SECRET is required}"

public_base_url="${PUBLIC_BASE_URL:-https://frontline-nations.tg-games.com}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
avatar="$repo_root/assets/brand/frontline-nations-bot-avatar.jpg"

api_url="https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}"

set_commands() {
  local language_code="$1"
  local commands="$2"
  local args=(--request POST --data-urlencode "commands=$commands")
  if [[ -n "$language_code" ]]; then
    args+=(--data-urlencode "language_code=$language_code")
  fi
  curl --fail --silent --show-error "${args[@]}" "$api_url/setMyCommands" >/dev/null
}

commands_en='[{"command":"start","description":"Start and choose a country"},{"command":"battle","description":"Choose one of five operations"},{"command":"army","description":"Build and select combat groups"},{"command":"shop","description":"Buy or craft equipment"},{"command":"upgrade","description":"Upgrade owned equipment"},{"command":"development","description":"Spend research and expand command capacity"},{"command":"profile","description":"Progress, record, and resources"},{"command":"front","description":"Weekly matchup, timer, and result"},{"command":"contribute","description":"Support the weekly front"},{"command":"country","description":"Browse or search countries"},{"command":"language","description":"Change language"},{"command":"nickname","description":"Choose or change nickname"},{"command":"settings","description":"Language and profile settings"},{"command":"help","description":"Command help"}]'
commands_ru='[{"command":"start","description":"Начать игру и выбрать страну"},{"command":"battle","description":"Выбрать одну из пяти операций"},{"command":"army","description":"Собрать и выбрать боевую группу"},{"command":"shop","description":"Купить или произвести технику"},{"command":"upgrade","description":"Прокачать свою технику"},{"command":"development","description":"Расширить вместимость за очки исследований"},{"command":"profile","description":"Прогресс, статистика и ресурсы"},{"command":"front","description":"Матч недели, таймер и результат"},{"command":"contribute","description":"Поддержать недельный фронт"},{"command":"country","description":"Каталог и поиск стран"},{"command":"language","description":"Сменить язык"},{"command":"nickname","description":"Выбрать или сменить ник"},{"command":"settings","description":"Язык и настройки профиля"},{"command":"help","description":"Справка по командам"}]'
commands_es='[{"command":"start","description":"Iniciar y elegir país"},{"command":"battle","description":"Elegir una de cinco operaciones"},{"command":"army","description":"Preparar grupos de combate"},{"command":"shop","description":"Comprar o fabricar equipo"},{"command":"upgrade","description":"Mejorar el equipo"},{"command":"development","description":"Ampliar capacidad con investigación"},{"command":"profile","description":"Progreso y recursos"},{"command":"front","description":"Batalla semanal"},{"command":"contribute","description":"Apoyar el frente"},{"command":"country","description":"Buscar países"},{"command":"language","description":"Cambiar idioma"},{"command":"nickname","description":"Cambiar apodo"},{"command":"settings","description":"Ajustes del perfil"},{"command":"help","description":"Ayuda"}]'
commands_pt='[{"command":"start","description":"Iniciar e escolher país"},{"command":"battle","description":"Escolher uma de cinco operações"},{"command":"army","description":"Montar grupos de combate"},{"command":"shop","description":"Comprar ou produzir equipamento"},{"command":"upgrade","description":"Melhorar equipamento"},{"command":"development","description":"Expandir capacidade com pesquisa"},{"command":"profile","description":"Progresso e recursos"},{"command":"front","description":"Batalha semanal"},{"command":"contribute","description":"Apoiar a frente"},{"command":"country","description":"Buscar países"},{"command":"language","description":"Alterar idioma"},{"command":"nickname","description":"Alterar apelido"},{"command":"settings","description":"Ajustes do perfil"},{"command":"help","description":"Ajuda"}]'
commands_ar='[{"command":"start","description":"البدء واختيار البلد"},{"command":"battle","description":"اختيار واحدة من خمس عمليات"},{"command":"army","description":"إعداد مجموعات القتال"},{"command":"shop","description":"شراء أو تصنيع المعدات"},{"command":"upgrade","description":"ترقية المعدات"},{"command":"development","description":"توسيع سعة القيادة"},{"command":"profile","description":"التقدم والموارد"},{"command":"front","description":"المعركة الأسبوعية"},{"command":"contribute","description":"دعم الجبهة"},{"command":"country","description":"البحث عن البلدان"},{"command":"language","description":"تغيير اللغة"},{"command":"nickname","description":"تغيير الاسم"},{"command":"settings","description":"إعدادات الملف"},{"command":"help","description":"المساعدة"}]'
commands_id='[{"command":"start","description":"Mulai dan pilih negara"},{"command":"battle","description":"Pilih satu dari lima operasi"},{"command":"army","description":"Susun grup tempur"},{"command":"shop","description":"Beli atau rakit unit"},{"command":"upgrade","description":"Tingkatkan unit"},{"command":"development","description":"Perluas kapasitas dengan riset"},{"command":"profile","description":"Progres dan sumber daya"},{"command":"front","description":"Pertempuran mingguan"},{"command":"contribute","description":"Dukung front"},{"command":"country","description":"Cari negara"},{"command":"language","description":"Ganti bahasa"},{"command":"nickname","description":"Ganti nama"},{"command":"settings","description":"Pengaturan profil"},{"command":"help","description":"Bantuan"}]'
commands_hi='[{"command":"start","description":"शुरू करें और देश चुनें"},{"command":"battle","description":"पाँच अभियानों में से चुनें"},{"command":"army","description":"युद्ध समूह बनाएँ"},{"command":"shop","description":"उपकरण खरीदें या बनाएँ"},{"command":"upgrade","description":"उपकरण उन्नत करें"},{"command":"development","description":"अनुसंधान से कमान क्षमता बढ़ाएँ"},{"command":"profile","description":"प्रगति और संसाधन"},{"command":"front","description":"साप्ताहिक युद्ध"},{"command":"contribute","description":"मोर्चे का समर्थन"},{"command":"country","description":"देश खोजें"},{"command":"language","description":"भाषा बदलें"},{"command":"nickname","description":"नाम बदलें"},{"command":"settings","description":"प्रोफ़ाइल सेटिंग"},{"command":"help","description":"सहायता"}]'
commands_tr='[{"command":"start","description":"Başla ve ülke seç"},{"command":"battle","description":"Beş operasyondan birini seç"},{"command":"army","description":"Muharebe grupları kur"},{"command":"shop","description":"Teçhizat satın al veya üret"},{"command":"upgrade","description":"Teçhizatı yükselt"},{"command":"development","description":"Araştırmayla komuta kapasitesini büyüt"},{"command":"profile","description":"İlerleme ve kaynaklar"},{"command":"front","description":"Haftalık savaş"},{"command":"contribute","description":"Cepheyi destekle"},{"command":"country","description":"Ülke ara"},{"command":"language","description":"Dili değiştir"},{"command":"nickname","description":"Adı değiştir"},{"command":"settings","description":"Profil ayarları"},{"command":"help","description":"Yardım"}]'

set_commands '' "$commands_en"
set_commands en "$commands_en"
set_commands ru "$commands_ru"
set_commands es "$commands_es"
set_commands pt "$commands_pt"
set_commands ar "$commands_ar"
set_commands id "$commands_id"
set_commands hi "$commands_hi"
set_commands tr "$commands_tr"

curl --fail --silent --show-error \
  --request POST \
  --data-urlencode "url=${public_base_url%/}/bot" \
  --data-urlencode "secret_token=${TELEGRAM_WEBHOOK_SECRET}" \
  --data-urlencode 'allowed_updates=["message","callback_query"]' \
  "$api_url/setWebhook"
printf '\n'

if ! curl --fail --silent --show-error \
  --request POST \
  --data-urlencode 'name=Frontline Nations' \
  "$api_url/setMyName" >/dev/null; then
  printf 'Warning: Telegram profile name update was rate-limited or rejected.\n' >&2
fi

if ! curl --fail --silent --show-error \
  --request POST \
  --data-urlencode 'description=Асинхронная стратегия: короткие операции, развитие командира и общий недельный фронт альянсов.' \
  "$api_url/setMyDescription" >/dev/null; then
  printf 'Warning: Telegram profile description update was rate-limited or rejected.\n' >&2
fi

if ! curl --fail --silent --show-error \
  --request POST \
  --data-urlencode 'short_description=Командуй армией и усиливай свой альянс.' \
  "$api_url/setMyShortDescription" >/dev/null; then
  printf 'Warning: Telegram short description update was rate-limited or rejected.\n' >&2
fi

if [[ -f "$avatar" ]]; then
  if ! curl --fail --silent --show-error \
    --request POST \
    --form 'photo={"type":"static","photo":"attach://avatar"}' \
    --form "avatar=@$avatar;type=image/jpeg" \
    "$api_url/setMyProfilePhoto" >/dev/null; then
    printf 'Warning: Telegram avatar update was rate-limited or rejected.\n' >&2
  fi
fi
