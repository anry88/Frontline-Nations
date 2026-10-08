# AND-15 — аудит активации и раннего удержания

Дата среза: 2026-10-08, UTC. Продукт: command-only Telegram-бот Frontline Nations. Mini App в аудит не включён.

## Вывод для решения

Идея имеет подтверждённый интерес на верхнем уровне: в базе 83 регистрации, из них 69 пришли по реферальным источникам; публичное описание обещает понятную фантазию — «Build your army and lead your country to victory». Реализация уже содержит полноценный боевой результат, карту, экономику, прогрессию, replay и недельный фронт.

Активация и удержание пока не доказаны данными. В доступном production-срезе DAU равен 2, и оба аккаунта принадлежат владельцу проекта. Значит, внешняя активность за последние 24 часа равна нулю по этому срезу. В системе нет событий и временных меток, по которым можно посчитать регистрацию → первый бой → завершение → второй бой → D1/D3/D7. Текущая панель показывает acquisition, команды, callback-и и итоговые счётчики, но не воронку.

Главная продуктовая гипотеза: новый игрок получает слишком много решений до первого доказательства ценности. После выбора страны ему показывается универсальное меню, затем `/battle` требует выбрать операцию, точку входа, первую цель и доктрину. При этом постоянные потери техники уже объясняются до боя. Первый опыт выглядит как настройка системы, а не как короткая победоносная операция.

Рекомендуемый порядок: сначала добавить измерение и одноэкранный guided first battle, затем проверить защиту первого боя от постоянной потери и только после этого расширять социальную и недельную петлю. Не начинать с Mini App или крупной переделки боевой системы.

## Доказательная база и ограничения

### Что проверено

- Production Grafana, дашборд Frontline Nations, диапазон `Last 30 days UTC`.
- Код текущего webhook/командного потока в [`GameService.kt`](../../src/main/kotlin/com/tggames/frontline/game/GameService.kt).
- Набор локалей и текстов в [`GameLanguage.kt`](../../src/main/kotlin/com/tggames/frontline/i18n/GameLanguage.kt) и [`GameI18n.kt`](../../src/main/kotlin/com/tggames/frontline/i18n/GameI18n.kt).
- Схема и наблюдаемость в [`GameMetrics.kt`](../../src/main/kotlin/com/tggames/frontline/observability/GameMetrics.kt) и `V1–V19` миграциях.
- Public landing page бота `@frontline_nations_bot`: название, avatar, username и описание «Build your army and lead your country to victory».
- `GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew test`: `BUILD SUCCESSFUL`.

### Что нельзя утверждать по текущим данным

- Два активных аккаунта — аккаунты владельца проекта, поэтому DAU нельзя считать внешним спросом.
- Нет доступа к минимум пяти реальным новым/ушедшим игрокам и их обезличенным последовательностям сообщений. Два тестовых аккаунта владельца не заменяют качественную выборку. Визуально проверена только публичная landing page бота; внутренние экраны и полный 8-язычный in-bot screenshot pass не зафиксированы.
- Нет воронки с уникальными игроками, cohort date, первым/вторым боем и временем между этапами.
- Командные счётчики и database-backed battle gauges имеют разные границы надёжности и могут переживать разные рестарты/окна. Их нельзя делить друг на друга как точную конверсию.
- В Telegram payload нет device/client fields; сегментация по устройству сейчас невозможна.
- В dashboard нет разрезов по выбранной локали и нет replay-ready/replay-error событий.

## Фактическая карта пути игрока

```text
Открыть @frontline_nations_bot
  → /start
  → создать player + автоматически выдать starter inventory
  → выбрать страну/территорию (рекомендация по Telegram language или каталог 250 записей)
  → универсальное меню: Battle / Daily / Army / Shop / Upgrade / Profile / Front / Rankings / Guide / Settings
  → /army: выбрать один из трёх пресетов и состав в CP
  → /battle: 5 предложений операций
  → карта + выбрать вход
  → выбрать первую цель
  → выбрать доктрину/приоритет движения и целей
  → синхронно рассчитать бой
  → текстовый результат: исход, маршрут, CP, шаги, причины завершения, цели, события, XP/Credits/Materials, returned/lost
  → опционально нажать Battle replay; рендер начинается после нажатия
  → повторно выбрать Battle / Daily / Army / Shop / Front
  → отдельно найти /contribute для недельной кампании
```

Сильная сторона пути: starter group создаётся автоматически, минимальный первый tier начинается с 10 CP, а стартовая группа из спецификации уже соответствует этому порогу. Новому игроку не требуется сначала покупать технику или повышать уровень.

Слабая сторона пути: после выбора страны нет отдельного guided state. Универсальное меню одновременно показывает восемь направлений. `/battle` не имеет «рекомендованной миссии одним нажатием» и последовательно раскрывает четыре решения. Отмена на каждом шаге возвращает в список операций или отправляет новый текст, поэтому Telegram-история быстро заполняется промежуточными сообщениями.

## Production baseline

| Сигнал | Значение | Как читать |
| --- | ---: | --- |
| Registered players | 83 | database-backed total, не уникальные за период |
| Active players · 24h | 2 | оба аккаунта владельца проекта; внешний DAU = 0 в этом срезе |
| Active players · 7d | 11 | не cohort retention; это `players.updated_at` |
| Referrals total | 69 из 83 (83.1%) | acquisition сильно зависит от внешних ссылок |
| `gramads` | 53 из 83 (63.9%) | крупнейший источник, retention по нему не измеряется |
| `riverking` | 8 | историческая/перекрёстная кампания |
| `direct` | 14 | прямые старты |
| Personal battles · total | 72 | 39 victory, 33 defeat; счётчик из БД |
| Victory share | 54.2% | только описание итогового счётчика, не когорта новых игроков |
| Replay callbacks · 30d | 18 | клики; нет события успешной доставки/просмотра |
| Equipment purchases · total | 210 | database-backed transaction count |
| Equipment upgrades · total | 27 | database-backed transaction count |

### Команды за 30 дней

`/start` — 99; `/daily` — 81; неизвестные команды — 33; `/front` — 29; `/army` — 11; `/rankings` — 7; `/nickname` — 5; `/battle` — 4; `/language` — 3; `/contribute` — 2; `/settings`, `/upgrade`, `/shop`, `/rating`, `/guide` — по 1.

Эти числа не являются уникальными пользователями. `start=99` при 83 аккаунтах показывает повторные входы или повторные нажатия. `unknown=33` — сигнал command-discovery/friction, но требует разделить неизвестную slash-команду, текст без команды и старую кнопку.

### Callback-и за 30 дней

`rankings` — 568; `army` — 372; `battle` — 307; `country` — 302; `shop` — 282; `front` — 212; `upgrade` — 47; `daily` — 22; `replay` — 18; `profile` — 16; `guide` — 9; `settings` — 7; `stars` — 2; `nickname` — 2; `language` — 1.

Панель показывает много навигации и мало измерений исхода. Высокие `rankings/army/country/shop` могут быть исследованием интерфейса, возвратом к меню или циклом после ошибки; без `player_id`, `session_id` и `result` они не объясняют поведение.

### Экономика и исходы

За 30 дней панель показала 64 успешные покупки, 14 `insufficient_resources`, 2 `locked`, 13 успешных улучшений и 10 `insufficient_resources`. Это подтверждает, что экономика и каталог используются, но не показывает, на каком шаге нового игрока возникает нехватка.

В коде `/daily` стартует с 90 Credits и растёт до 180; starter group стоит около 32 Credits по catalog. Это снижает риск немедленного экономического тупика. Одновременно `/battle` заранее сообщает, что уничтоженная техника теряется навсегда. Для первого боя это сильный loss-aversion trigger, особенно когда игрок ещё не знает, что именно влияет на результат.

## Экспертный UX-разбор

### Первые 30 секунд

Публичное обещание понятно тематически, но не отвечает на три вопроса: что делать первым, сколько длится операция и что именно получу за один заход. После `/start` пользователь сразу должен выбрать страну/территорию. Выбор необратим в текущей версии, а текст отдельно сообщает, что сезонная смена пока недоступна. Для рекламы это нормальный слой, для первого запуска — высокий perceived cost.

Рекомендуемый первый экран: «Starter force ready · first operation takes 1–3 min · choose one mission». Кнопка должна вести сразу в рекомендованный low-risk бой, а полный каталог страны, `/guide` и `/settings` — оставаться доступными вторично.

### Подготовка армии

`/army` показывает три пресета, CP capacity, количество единиц, composition power и по две кнопки `−/＋` для каждого типа техники. Для опытного игрока это полезно; для новичка это редактирование инвентаря до первого доказательства ценности. Starter group уже пригодна к бою, поэтому first-run не должен заставлять пользователя открывать `/army`.

### Первый бой

Путь содержит пять операционных предложений, затем изображение карты и вход, затем первую цель, затем доктрину. Тексты объясняют terrain, sight, range и permanent loss, но объяснение распределено по сообщениям. Игроку трудно понять, какое решение действительно важно, потому что все четыре решения представлены одинаковым весом.

Положительные элементы: карта отправляется до приказа; маршрут явно показывается как `group → entry → objective`; tactic hints локализованы; результат содержит исход, CP, число шагов, end reason, цели, highlights, награды и потери. Это хорошая основа для guided-режима без переписывания движка.

Риск: разрешение выполняется синхронно в обработчике callback, а replay рендерится только после отдельного нажатия. Пользователь может увидеть ожидание между «fight» и результатом, а затем второе ожидание между «replay» и видео. Ни latency, ни failure rate этих этапов не измеряются.

### Результат и следующий шаг

Результат достаточно информативен, но не формулирует одну рекомендуемую следующую задачу. В первом результате появляется подсказка «Claim /daily and buy units in /shop», а keyboard одновременно предлагает Battle, Daily, Army, Shop, Upgrade, Profile, Front, Rankings, Guide и Settings. Подсказка конкурирует с девятью кнопками.

Нужен один primary CTA: для нулевого боя — `🎁 Claim daily → ⚔️ Repeat battle`; для проигрыша — `🧰 Rebuild lost unit → ⚔️ Try again`; для победы — `🌍 Send one group to front`. Остальные действия оставить в меню.

### Петли

Core loop: состав → операция → четыре приказа → результат → replay/награда. Он потенциально интересен, но первый session loop перегружен выбором.

Session loop: battle → credits/XP/materials → army/shop/upgrade → battle. Он поддержан кодом и экономикой, но не различает first-time и repeat users.

Weekly loop: contribute до трёх пресетов → воскресный lock → result/replay → рейтинг и ×1.2 bonus. Он даёт принадлежность к стране, но появляется через отдельную `/front`/`/contribute` ветку и не включён в первичный onboarding.

Autonomy сейчас высокая, но comprehension низкая: клиент выбирает entry/objective/tactic, а в dashboard нет данных, показывающих, понимает ли игрок влияние этих решений. Для первого боя следует оставить одно явное решение и раскрывать остальные после завершения.

## Локали и мобильный Telegram UX

В коде поддерживаются все восемь локалей: English, Русский, Español, Português (Brasil), العربية, Bahasa Indonesia, हिन्दी, Türkçe. Основные тексты и battle/campaign events имеют восемь вариантов, что является сильной базой.

Статический проход по message matrix выявил общий риск для всех локалей: команды `/start`, `/daily`, `/shop`, `/battle`, CP, XP, Credits и названия классов остаются Latin/mixed-script. Это допустимо для Telegram-команд, но требует визуальной проверки длины строк, переносов и порядка emoji/чисел. Для Arabic дополнительно нужен RTL проход на реальном мобильном клиенте; текущий код не содержит отдельного RTL layout contract.

Чего не сделано: восемь независимых live-сессий с реальным Telegram UI, проверка маленького экрана и минимум пять новых/ушедших пользователей. Это ограничение аудита, а не утверждение, что переводы плохие. Следующий research-пакет должен включать по одной scripted session на каждую локаль и отдельный RTL screenshot pass.

## Классификация причин drop-off

| Класс | Наблюдение | Доказательство | Приоритет |
| --- | --- | --- | --- |
| Critical blocker | Нет измеримой воронки и cohort retention | `GameMetrics` хранит counters/gauges, но нет first/second battle timestamps и user-level event table | P0 |
| Critical blocker | Нет guided first battle | после страны открывается универсальное меню; `/battle` требует 4 решения | P0 |
| Friction | Меню показывает слишком много равноправных веток | `actionKeyboard` содержит Battle, Daily, Army, Shop, Upgrade, Profile, Front, Rankings, Guide, Settings | P0 |
| Friction | Страна выбирается до ценности и блокируется | `selectAlliance` сохраняет alliance once; copy сообщает, что смена пока недоступна | P1 |
| Непонимание | 33 unknown slash commands за 30 дней | Grafana Commands | P1 |
| Непонимание | CP, tactic, objective и terrain объясняются раздельно | `showDeployment` → `showObjectives` → `showTactics` | P1 |
| Негативная мотивация | Постоянные потери показаны до первого боя | `/battle` copy: destroyed equipment is lost | P0 для эксперимента |
| Слабая мотивация | Weekly front не связан с первым результатом | `/front`/`/contribute` — отдельная ветка | P1 |
| Technical wait | Battle и replay latency не измеряются | нет `battle_started/finished` или `replay_ready/error/duration` | P0 |
| Слабый feedback loop | 18 replay clicks, но нет успешной доставки | callback counter есть, completion event отсутствует | P1 |

## Приоритизированный backlog

Оценка: Impact и Confidence по шкале 1–5, Effort 1–5; порядок учитывает и критичность измерения.

| Приоритет | Изменение | I | C | E | Метрика успеха |
| --- | --- | ---: | ---: | ---: | --- |
| P0 | События воронки и cohort dashboard | 5 | 5 | 2 | все ключевые этапы с уникальным игроком и временем |
| P0 | Guided first battle с одной рекомендованной миссией | 5 | 4 | 3 | first-battle start, p50 TTFB, completion |
| P0 | Защита первого боя от permanent loss в эксперименте | 5 | 4 | 3 | second battle 24h, D1, loss complaints |
| P0 | Instrument battle/replay latency and errors | 5 | 5 | 2 | p50/p95 resolution/replay, error rate |
| P1 | Свести post-result к одному primary CTA | 4 | 4 | 1 | second battle 24h, CTA click-through |
| P1 | Убрать `army/shop/upgrade` из mandatory first-run | 4 | 4 | 1 | TTFB и first-battle start |
| P1 | Дать reversible country choice до первого боя или ясное предупреждение | 3 | 3 | 2 | country-to-battle drop-off |
| P1 | Превратить unknown command в contextual recovery | 3 | 4 | 1 | unknown rate на активного игрока |
| P1 | Проверить 8 локалей на мобильном клиенте, RTL отдельно | 3 | 3 | 2 | task completion, overflow/translation defects |
| P2 | Ввести first-result bridge в weekly front | 3 | 3 | 3 | first-time `/front`/`/contribute` reach |
| P2 | Сделать replay auto-ready или дать ETA/готовое состояние | 3 | 3 | 3 | replay-ready / replay-click ratio |
| P2 | Сегментация по source, locale и registration cohort | 4 | 5 | 2 | сравнимые source/locale cohorts |

## Quick wins на 1–2 недели

1. Добавить после выбора страны отдельное welcome-сообщение: starter force ready, 1–3 minutes, кнопка `Start first operation`, вторичная `Customize army`.
2. Добавить на `/battle` кнопку `Recommended first operation` с низким риском и server-side default для entry/objective/tactic; advanced choices оставить через `Customize`.
3. В первом бою включить feature flag `first_battle_protection`: потери превращаются в временно повреждённые или возвращаются после результата. Сохранить flag/version в battle snapshot.
4. Заменить девять равных result-кнопок на один primary CTA и две secondary actions; остальные оставить в `/menu`/`/settings`.
5. Добавить события `battle_started`, `battle_finished`, `replay_requested`, `replay_ready`, `replay_failed` и duration.
6. Для unknown slash command отвечать «Выберите действие» с Battle/Daily/Guide кнопками и сохранять `unknown_command` как отдельное событие.
7. Добавить в Grafana cohort panels за 1/3/7 дней и user-level derived funnel, не используя Telegram ID в labels.
8. Провести пять интервью/тестов до изменения баланса: три новых игрока, один проигравший после первого боя, один зарегистрировавшийся без боя.

## Варианты целевого onboarding

### Вариант A — guided mission, рекомендуется

```text
/start → country → «Starter force ready»
      → [Start first operation]
      → одна low-risk operation + короткая разведка
      → [Use recommended route] или [Customize]
      → бой → результат с одной CTA [Claim daily]
      → [Repeat battle] / [Send one group to front]
```

Игрок сохраняет выбор только там, где это помогает почувствовать agency. Entry/objective/tactic получают объяснимые defaults. Это самый дешёвый вариант: движок не меняется, меняются offer state, callbacks и copy.

### Вариант B — безопасный tutorial battle

```text
/start → country → daily optional → [Training operation]
      → 10 CP starter force, no permanent loss
      → карта + один objective choice
      → бой ≤ 90 s → понятный result card
      → [Play a real operation]
```

Подходит, если E1 показывает, что permanent loss мешает completion. Нужен snapshot-параметр режима, чтобы сохранить воспроизводимость и не смешать tutorial result с обычной экономикой.

### Вариант C — map-first agency

```text
/start → country → карта и короткое объяснение цели
      → [Choose entry] → [Choose first objective]
      → tactic preselected with one-line reason
      → бой → replay/result → front teaser
```

Подходит, если пользователи хотят командовать, но не хотят читать арсенал. Этот вариант сохраняет тематическую ценность карты и откладывает детали состава.

## План экспериментов

| ID | Гипотеза и вариант | Основная метрика | Guardrails | Stop/ship |
| --- | --- | --- | --- | --- |
| E1 | Guided mission против текущего меню повышает первый бой | registered → `battle_started` за 24h; TTFB p50 | completion ≥ 80%, error < 2%, defeat rate ±10 п.п. | ship при +25% relative и 30+ игроках на arm; stop при падении completion >10% |
| E2 | First-battle protection повышает second battle/D1 | second battle ≤24h; D1 | Credits/Materials inflation, loss complaints, win rate | ship при +20% second battle без инфляции >15% |
| E3 | Recommended defaults сокращают выбор без потери agency | TTFB, decision-step completion | tactic selection on battle 2, defeat rate, `customize` usage | ship при −30% TTFB и ≥70% battle-2 tactic interaction |
| E4 | Result primary CTA ведёт к повтору | second battle ≤24h; CTA click | `/daily` abuse, unknown commands, replay errors | ship при +20% repeat and no guardrail regression |
| E5 | Replay status/preload повышает replay completion | `replay_ready / replay_requested` | p95 render time, CPU/heap, failure rate | ship при +20% ready ratio и p95 <30s |

При текущем масштабе выборка слишком мала для уверенного A/B-теста. Сначала собирать instrumentation, затем запускать sequential test с минимум 30 игроками на arm или ждать 200+ новых регистраций. Не объявлять победителя по двум аккаунтам владельца.

## Минимальная аналитическая схема

Хранить серверные события в PostgreSQL/append-only outbox или отдельной таблице, а в Prometheus публиковать только агрегаты. Не отправлять Telegram ID, nickname, raw `/start` payload или callback data как labels.

Обязательные события:

| Event | Когда | Поля |
| --- | --- | --- |
| `registration_completed` | первая запись player | `player_fk`, `occurred_at`, `source`, `referral_code`, `telegram_locale`, `game_locale` |
| `onboarding_country_viewed/selected` | показ/выбор страны | `session_id`, `country_code`, `step_index` |
| `onboarding_cta_clicked` | first-run CTA | `session_id`, `cta`, `surface` |
| `army_viewed/changed` | открытие/изменение пресета | `session_id`, `preset_no`, `used_cp`, `capacity` |
| `battle_offer_viewed/selected` | список/операция | `session_id`, `offer_slot`, `difficulty`, `force_tier` |
| `battle_deployment_selected` | entry/objective/tactic | `session_id`, `entry_id`, `objective_id`, `tactic`, `is_default` |
| `battle_started` | перед server resolve | `battle_id`, `session_id`, `engine_version`, `first_battle` |
| `battle_finished` | commit result | `battle_id`, `duration_ms`, `victory`, `end_reason`, `steps`, `lost_units`, `reward_credits` |
| `replay_requested/ready/failed` | replay lifecycle | `battle_id`, `duration_ms`, `failure_class` |
| `daily_claimed/shop_opened/upgrade_completed/contribution_committed` | loop actions | `session_id`, `source_surface`, `result` |
| `session_started/ended` | 30-minute inactivity boundary | `session_id`, `entry_surface`, `duration_ms`, `last_step` |

Derived metrics:

- activation: `battle_started / registration_completed`, with denominator selected country;
- completion: `battle_finished / battle_started`;
- TTFB: p50/p90 from `registration_completed` to first `battle_started`;
- replay completion: `replay_ready / replay_requested`;
- second battle: unique players with second `battle_started` within 24h;
- D1/D3/D7: any qualifying event on day 1/3/7 after registration;
- critical errors: stale selection, engine failure, Telegram send failure, replay failure, timeout.

Required dashboard cuts: registration cohort date, source/referral, game locale, Telegram locale, chosen country, first-battle tier, tutorial variant, and client class only if Telegram exposes a non-sensitive stable field. Device data should remain optional; do not collect raw identifiers.

## Research script for five real sessions

Recruit three new players who have not completed a battle, one player who completed exactly one battle, and one who registered but did not start a battle. Do not reveal the expected solution. Ask the player to think aloud while completing:

1. Open the bot and say what you expect to happen.
2. Choose a country and explain why.
3. Find the first battle without help.
4. Decide whether to touch army/shop/daily.
5. Choose entry, objective and tactic; explain what each should change.
6. Read the result and state the next action.
7. Find the replay and say whether it helps.
8. Return the next day and say what brought you back.

Record only step completion, time, wrong turns, quote snippets, perceived risk, and requested help. Do not store Telegram IDs, nicknames, auth payloads or raw chat history in the audit dataset.

## Heuristic benchmark

Relevant Telegram/idle/async strategy patterns are: first action within one screen, reversible tutorial losses, strong defaults with opt-out, explicit session reward, one primary next action, and social context introduced after individual competence. The current bot has a server-authoritative and deterministic core, but applies the advanced decision model before the player has learned why the choices matter. Use these patterns as hypotheses to test, not as a reason to copy another product.

## Final assessment

The concept is attractive enough to produce 83 registrations and a strong referral mix. The current evidence does not prove that the concept retains players; it proves that acquisition and some deep interactions exist. The largest implementation risks are onboarding choice load, permanent first-battle loss, a crowded post-result menu, and missing lifecycle telemetry.

The next decision should be whether to instrument first or ship a guided first mission behind a flag. The correct sequence is both in one small slice: add funnel/replay events, expose one guided mission, and run the first five user sessions before changing economy or battle balance. Keep deterministic engine inputs, server authority, idempotent wallets and neutral country identifiers unchanged.
