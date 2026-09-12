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

commands_en='[{"command":"start","description":"Start and choose a country"},{"command":"battle","description":"Choose an operation"},{"command":"daily","description":"Claim Credits and streak bonus"},{"command":"army","description":"Build and select combat groups"},{"command":"shop","description":"Buy equipment"},{"command":"upgrade","description":"Upgrade owned equipment"},{"command":"profile","description":"Progress, record, and resources"},{"command":"front","description":"Weekly matchup, timer, and result"},{"command":"contribute","description":"Manage groups on the weekly front"},{"command":"rankings","description":"Alliance and player ratings"},{"command":"guide","description":"Game rules by section"},{"command":"country","description":"Browse or search countries"},{"command":"language","description":"Change language"},{"command":"nickname","description":"Choose or change nickname"},{"command":"settings","description":"Language and profile settings"},{"command":"help","description":"Command help"}]'
commands_ru='[{"command":"start","description":"Начать игру и выбрать страну"},{"command":"battle","description":"Выбрать операцию"},{"command":"daily","description":"Получить Credits и бонус серии"},{"command":"army","description":"Собрать и выбрать боевую группу"},{"command":"shop","description":"Купить технику"},{"command":"upgrade","description":"Прокачать свою технику"},{"command":"profile","description":"Прогресс, статистика и ресурсы"},{"command":"front","description":"Матч недели, таймер и результат"},{"command":"contribute","description":"Управлять отрядами на фронте"},{"command":"rankings","description":"Рейтинг стран и игроков"},{"command":"guide","description":"Правила игры по разделам"},{"command":"country","description":"Каталог и поиск стран"},{"command":"language","description":"Сменить язык"},{"command":"nickname","description":"Выбрать или сменить ник"},{"command":"settings","description":"Язык и настройки профиля"},{"command":"help","description":"Справка по командам"}]'
commands_es='[{"command":"start","description":"Iniciar y elegir país"},{"command":"battle","description":"Elegir una operación"},{"command":"daily","description":"Recibir Credits y bono diario"},{"command":"army","description":"Preparar grupos de combate"},{"command":"shop","description":"Comprar equipo"},{"command":"upgrade","description":"Mejorar el equipo"},{"command":"profile","description":"Progreso y recursos"},{"command":"front","description":"Batalla semanal"},{"command":"contribute","description":"Enviar equipo al frente"},{"command":"country","description":"Buscar países"},{"command":"language","description":"Cambiar idioma"},{"command":"nickname","description":"Cambiar apodo"},{"command":"settings","description":"Ajustes del perfil"},{"command":"help","description":"Ayuda"}]'
commands_pt='[{"command":"start","description":"Iniciar e escolher país"},{"command":"battle","description":"Escolher uma operação"},{"command":"daily","description":"Receber Credits e bônus diário"},{"command":"army","description":"Montar grupos de combate"},{"command":"shop","description":"Comprar equipamento"},{"command":"upgrade","description":"Melhorar equipamento"},{"command":"profile","description":"Progresso e recursos"},{"command":"front","description":"Batalha semanal"},{"command":"contribute","description":"Enviar equipamento à frente"},{"command":"country","description":"Buscar países"},{"command":"language","description":"Alterar idioma"},{"command":"nickname","description":"Alterar apelido"},{"command":"settings","description":"Ajustes do perfil"},{"command":"help","description":"Ajuda"}]'
commands_ar='[{"command":"start","description":"البدء واختيار البلد"},{"command":"battle","description":"اختيار عملية"},{"command":"daily","description":"استلام Credits ومكافأة السلسلة"},{"command":"army","description":"إعداد مجموعات القتال"},{"command":"shop","description":"شراء المعدات"},{"command":"upgrade","description":"ترقية المعدات"},{"command":"profile","description":"التقدم والموارد"},{"command":"front","description":"المعركة الأسبوعية"},{"command":"contribute","description":"إرسال المعدات إلى الجبهة"},{"command":"country","description":"البحث عن البلدان"},{"command":"language","description":"تغيير اللغة"},{"command":"nickname","description":"تغيير الاسم"},{"command":"settings","description":"إعدادات الملف"},{"command":"help","description":"المساعدة"}]'
commands_id='[{"command":"start","description":"Mulai dan pilih negara"},{"command":"battle","description":"Pilih operasi"},{"command":"daily","description":"Ambil Credits dan bonus rentetan"},{"command":"army","description":"Susun grup tempur"},{"command":"shop","description":"Beli unit"},{"command":"upgrade","description":"Tingkatkan unit"},{"command":"profile","description":"Progres dan sumber daya"},{"command":"front","description":"Pertempuran mingguan"},{"command":"contribute","description":"Kirim unit ke front"},{"command":"country","description":"Cari negara"},{"command":"language","description":"Ganti bahasa"},{"command":"nickname","description":"Ganti nama"},{"command":"settings","description":"Pengaturan profil"},{"command":"help","description":"Bantuan"}]'
commands_hi='[{"command":"start","description":"शुरू करें और देश चुनें"},{"command":"battle","description":"अभियान चुनें"},{"command":"daily","description":"Credits और सिलसिला बोनस पाएँ"},{"command":"army","description":"युद्ध समूह बनाएँ"},{"command":"shop","description":"उपकरण खरीदें"},{"command":"upgrade","description":"उपकरण उन्नत करें"},{"command":"profile","description":"प्रगति और संसाधन"},{"command":"front","description":"साप्ताहिक युद्ध"},{"command":"contribute","description":"मोर्चे पर उपकरण भेजें"},{"command":"country","description":"देश खोजें"},{"command":"language","description":"भाषा बदलें"},{"command":"nickname","description":"नाम बदलें"},{"command":"settings","description":"प्रोफ़ाइल सेटिंग"},{"command":"help","description":"सहायता"}]'
commands_tr='[{"command":"start","description":"Başla ve ülke seç"},{"command":"battle","description":"Operasyon seç"},{"command":"daily","description":"Credits ve seri bonusunu al"},{"command":"army","description":"Muharebe grupları kur"},{"command":"shop","description":"Teçhizat satın al"},{"command":"upgrade","description":"Teçhizatı yükselt"},{"command":"profile","description":"İlerleme ve kaynaklar"},{"command":"front","description":"Haftalık savaş"},{"command":"contribute","description":"Cepheye teçhizat gönder"},{"command":"country","description":"Ülke ara"},{"command":"language","description":"Dili değiştir"},{"command":"nickname","description":"Adı değiştir"},{"command":"settings","description":"Profil ayarları"},{"command":"help","description":"Yardım"}]'

commands_es="${commands_es%]}, {\"command\":\"rankings\",\"description\":\"Clasificaciones\"}, {\"command\":\"guide\",\"description\":\"Guía del juego\"}]"
commands_pt="${commands_pt%]}, {\"command\":\"rankings\",\"description\":\"Classificações\"}, {\"command\":\"guide\",\"description\":\"Guia do jogo\"}]"
commands_ar="${commands_ar%]}, {\"command\":\"rankings\",\"description\":\"التصنيفات\"}, {\"command\":\"guide\",\"description\":\"دليل اللعبة\"}]"
commands_id="${commands_id%]}, {\"command\":\"rankings\",\"description\":\"Peringkat\"}, {\"command\":\"guide\",\"description\":\"Panduan game\"}]"
commands_hi="${commands_hi%]}, {\"command\":\"rankings\",\"description\":\"रैंकिंग\"}, {\"command\":\"guide\",\"description\":\"गेम गाइड\"}]"
commands_tr="${commands_tr%]}, {\"command\":\"rankings\",\"description\":\"Sıralamalar\"}, {\"command\":\"guide\",\"description\":\"Oyun rehberi\"}]"

commands_en="${commands_en%]}, {\"command\":\"stars\",\"description\":\"Buy Credits with Telegram Stars\"}, {\"command\":\"paysupport\",\"description\":\"Stars purchase and refund support\"}]"
commands_ru="${commands_ru%]}, {\"command\":\"stars\",\"description\":\"Купить Credits за Telegram Stars\"}, {\"command\":\"paysupport\",\"description\":\"Поддержка покупок и возвратов\"}]"
commands_es="${commands_es%]}, {\"command\":\"stars\",\"description\":\"Comprar Credits con Telegram Stars\"}, {\"command\":\"paysupport\",\"description\":\"Soporte de compras y reembolsos\"}]"
commands_pt="${commands_pt%]}, {\"command\":\"stars\",\"description\":\"Comprar Credits com Telegram Stars\"}, {\"command\":\"paysupport\",\"description\":\"Suporte a compras e reembolsos\"}]"
commands_ar="${commands_ar%]}, {\"command\":\"stars\",\"description\":\"شراء Credits عبر Telegram Stars\"}, {\"command\":\"paysupport\",\"description\":\"دعم المشتريات والاسترداد\"}]"
commands_id="${commands_id%]}, {\"command\":\"stars\",\"description\":\"Beli Credits dengan Telegram Stars\"}, {\"command\":\"paysupport\",\"description\":\"Dukungan pembelian dan refund\"}]"
commands_hi="${commands_hi%]}, {\"command\":\"stars\",\"description\":\"Telegram Stars से Credits खरीदें\"}, {\"command\":\"paysupport\",\"description\":\"खरीद और वापसी सहायता\"}]"
commands_tr="${commands_tr%]}, {\"command\":\"stars\",\"description\":\"Telegram Stars ile Credits al\"}, {\"command\":\"paysupport\",\"description\":\"Satın alım ve iade desteği\"}]"

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
  --data-urlencode 'allowed_updates=["message","callback_query","pre_checkout_query"]' \
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
  --data-urlencode 'description=An asynchronous strategy game with tactical operations, commander progression, and massive weekly battles between countries.' \
  "$api_url/setMyDescription" >/dev/null; then
  printf 'Warning: Telegram profile description update was rate-limited or rejected.\n' >&2
fi

if ! curl --fail --silent --show-error \
  --request POST \
  --data-urlencode 'short_description=Build your army and lead your country to victory.' \
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
