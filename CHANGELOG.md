# Changelog

Все значимые изменения в этом монорепозитории документируются в этом файле.

В репозитории три **независимо версионируемых** компонента: API (`backend`), Web (`frontend`), Android (`android`). Каждый ведёт свой раздел ниже. Формат — [Keep a Changelog 1.1.0](https://keepachangelog.com/ru/1.1.0/), правила — [Semantic Versioning](https://semver.org/lang/ru/).

Ключи разделов:
- **Added** — новые возможности
- **Changed** — изменения в существующем поведении
- **Deprecated** — то, что будет удалено
- **Removed** — удалённое
- **Fixed** — багфиксы
- **Security** — фиксы уязвимостей и связанные с безопасностью изменения

---

## API (backend)

### [1.8.0] — 2026-05-06

#### Added
- `GET /api/transactions` принимает параметр `categories` (CSV) — Mongo-фильтр через `$in`. Существующий `category` оставлен для back-compat.

### [1.7.0] — 2026-05-05

#### Added
- `GET /api/version` — текущая версия API + рекомендованная и минимально допустимая версии Android-клиента (`ANDROID_LATEST`, `ANDROID_MIN_REQUIRED`).
- `GET /api/users` — список пользователей для переназначения автора записей.
- Кастомные категории: модель `Category`, репозиторий и хендлеры `GET /api/categories`, `GET /api/categories/all`, `POST /api/categories`, `DELETE /api/categories/:id`. Категории хранятся per-section (`expense`/`income`/`wishlist`).
- Bulk-эндпоинт `GET /api/statistics/overview` — параллельный fan-out через `errgroup`, возвращает summary + by-category + monthly + forecast одним ответом.
- Bulk-эндпоинт `GET /api/categories/all` — все секции одним ответом, чтобы клиент мог прогревать `CategoryRepository` за один запрос.
- Bidirectional sync: `GET /api/sync/pull?since=...` и `POST /api/sync/push` с разрешением конфликтов по версии. Транзакции получают UUID `_id`, поля `version`, `updated_at`, `deleted_at` (soft delete).
- Параметр периода (`from` / `to`) для `GET /api/statistics/monthly` — поддерживает двустороннюю синхронизацию date-picker'а и brush-выделения на графике.
- `cmd/seed_loadtest` — генератор реалистичных данных семейного бюджета (≈6k транзакций, конфигурируется флагами).
- `cmd/migrate` — заготовка для ad-hoc миграций.
- `Dockerfile.prod` — минимальный distroless-образ для production.
- Тип транзакции `initial_balance` — учитывается в балансе, но исключён из income-статистики.

#### Changed
- В каждой записи (`Transaction`, `Wishlist`) встроены `created_by` и `last_modified_by` (id, имя, аватар).
- Категории `expense`/`income` теперь динамические; ранее были захардкоженный enum.
- `Transaction.Date` парсится толерантно к нескольким форматам (Android отправляет варианты).

#### Fixed
- Backfill `userId` для старых записей через `/auth/me`.

### [1.0.0] — 2026-04-29

- Первая публичная версия: REST API (Gin), MongoDB, JWT-аутентификация, transactions / wishlist / statistics / экспорт PDF+Excel, `cmd/create_user`.

---

## Web (frontend)

### [1.9.0] — 2026-05-06

#### Added
- Multi-select фильтр категорий в Income / Expenses: `NSelect` с `multiple`, отправляет `categories` CSV на бэкенд.
- Сортировка категорий по recency: store `categories.js` хранит per-section карту последнего использования в `localStorage`; `options(section)` отдаёт сортировку по recency, неиспользованные — fallback на default-first / alphabetical.

### [1.8.0] — 2026-05-06

#### Changed
- Логотип `mb` инлайнится как Vue-компонент (`MbLogo.vue`) с SVG прямо в шаблоне — вместо отдельного шрифта Nunito ExtraBold.

#### Removed
- Шрифт Nunito (5 файлов `frontend/public/fonts/*.woff2` и `nunito.css`) — больше не используется ни в одном компоненте.

### [1.7.0] — 2026-05-05

#### Added
- Темная тема: `tokens.js` палитра, `naiveTheme` / `themeOverrides` / `palette`-store, CSS-переменные.
- Кастомные категории: store `categories.js`, динамические селекты в формах добавления/редактирования.
- `TilePeriodPicker` — компактный month/year tile-picker (4×3) на базе `NInput` + popover; используется в `StatisticsView` и `IncomeView` (initial-balance).
- Статистика: фильтр периода с двусторонней синхронизацией date-picker ⇄ brush-выделение по графику (ECharts `brushSelected` + `takeGlobalCursor`, без панели toolbox).
- Mass select / bulk delete во всех списках (Income / Expenses / Wishlist): per-view `bulkMode` + `selectedIds`, чекбокс заменяет аватар в режиме выделения.
- `ConfirmActionButton` — двухшаговое подтверждение для деструктивных действий.
- Inline-редактирование записей (карандаш → инпут, без модалок), переназначение автора через выпадающий список.
- Глобальный тумблер скрытия сумм в шапке (CSS-blur через theme-store + `App.vue`).
- Per-view stores транзакций (`useTransactionsStore('income' | 'expenses')`) — фильтры/пагинация изолированы.
- Тип `initial_balance` в `IncomeView` с навигацией по месяцам.
- В шапке отображается версия web и API (через `/api/version`).

#### Fixed
- LoginModal — корректное закрытие по успешному логину.
- Sider в основном лейауте — `flex` для корректного скролла.

### [1.0.0] — 2026-04-29

- Первая публичная версия: Vue 3 + Naive UI SPA, страницы Income / Expenses / Wishlist / Statistics / Forecast / Export, JWT-логин.

---

## Android

### [1.18.6] — 2026-05-06

#### Changed
- **R8 включён для release-сборки**: `minifyEnabled true` + `shrinkResources true` с `proguard-android-optimize.txt`. Создан `app/proguard-rules.pro` — keep-правила для Gson моделей (`data.model.**`), Retrofit `ApiService` (динамический proxy) и Room entities (verification схемы при первом открытии).
- В `release` блоке добавлен fallback на debug-keystore, если `ANDROID_KEYSTORE_FILE` не задан — позволяет ad-hoc `./gradlew assembleRelease` для perf-валидации без production keystore. Канонический `make android-release` flow по-прежнему требует все 4 env-переменные через `tools/build-android-release.sh`.

#### Fixed
- **Размер APK уменьшился c 23 MB до 3.2 MB** (-86%) за счёт R8 dead-code elimination — в первую очередь выкошен `material-icons-extended` (тысячи неиспользуемых иконок). Cold start, скролл и swipe-отклик стали заметно бодрее на 120 Hz, особенно на больших объёмах данных (3–8 тыс. записей).

### [1.18.5] — 2026-05-06

#### Changed
- `SwipeableTransactionCard` / `SwipeableWishlistCard` принимают id- и entity-typed callbacks вместо no-arg лямбд. Коллеры передают `vm::method` references (стабильны по `equals`) вместо closure-style лямбд внутри `items {}`. Compose теперь скипает рекомпозицию карточек при эмиссиях списка, если параметры не менялись — главный фикс рваного скролла на длинных списках.

### [1.18.4] — 2026-05-06

#### Changed
- Удалён `animateColorAsState` для фонового цвета в `SwipeableTransactionCard` / `SwipeableWishlistCard`. Каждая видимая карточка раньше крутила свою `Animatable<Color>` на каждой рекомпозиции; теперь цвет проставляется напрямую (snap), `SelectionOverlay` всё равно кроссфейдится поверх.

### [1.18.3] — 2026-05-06

#### Changed
- **Bulk-операции в одной Room-транзакции**. Multi-select hide/show/delete и wishlist `togglePurchased`/`bulkDelete` идут одним SQL `UPDATE … WHERE id IN (:ids)` (или `@Transaction` для удаления, чтобы скоалесцировать hard-purge + soft-tombstone). Room эмитит invalidation один раз на батч вместо N — `LazyColumn` больше не рекомпонуется N раз подряд при выделении 3-5 карточек.

### [1.18.1] — 2026-05-06

#### Fixed
- Wishlist на экране Forecast рендерится через flat `LazyColumn.items(key = { it.id })`. Раньше был обёрнут в один `item { Column { forEach } }`, что отключало рециркуляцию — все карточки композились разом, экран тяжелел на ≥100 записях и фризил на 1 000+.

### [1.18.0] — 2026-05-06

#### Added
- **Multi-select фильтр категорий** на Income / Expenses через checkbox-based dropdown (`CategoryFilterField`) — заменяет одиночный `ExposedDropdown`-фильтр.
- **Сортировка категорий по recency**: `CategoryUsage` singleton хранит per-section карту последнего использования в DataStore. ViewModels комбинируют поток `CategoryRepository` с usage и отдают категории, отсортированные от недавно используемых к давним.
- `TransactionRepository.observeFiltered` принимает `Set<String>` категорий — фильтрация выполняется client-side (Room flow + in-memory filter).

### [1.17.1] — 2026-05-06

#### Added
- Date picker (Material 3 `DatePickerDialog`) на формах добавления / редактирования транзакций — заменяет ручной ввод даты в текстовом поле.
- `KeyboardCapitalization.Sentences` на текстовых полях ввода (источник, назначение, описание, категория) — IME сразу показывает заглавную раскладку.

### [1.16.0] — 2026-05-06

#### Changed
- Логотип `mb` теперь vector drawable (`mb_logo.xml`) — единственный SVG-источник, тинтуется через `colorFilter`. Заменяет растровые картинки и зашитый шрифт Nunito ExtraBold.
- Адаптивная иконка приложения (`ic_launcher_foreground.xml`) перерисована на vector drawable.

#### Removed
- Шрифт Nunito ExtraBold (`font/nunito_extrabold.ttf`) и связанные растровые иконки лаунчера (`drawable-*dpi/ic_launcher_foreground.png`).

### [1.15.0] — 2026-05-05

#### Added
- **Server discovery** на экране подключения: сканирование локальной подсети (`/api/health` + `/api/version`) на стандартных портах `[8082, 8080, 80, 443, 8443]`, без участия сервера.
- **App lock**: PIN (PBKDF2) + биометрия, тайм-аут блокировки, восстановление паролем; first-launch setup; вынесенный `SecurityScreen`.
- **In-app updates**: баннер + обязательный диалог при критическом обновлении; APK скачивается с `<server_root>/apks/msdnna-budget-app-v<v>.apk`, валидация content-type + zip-magic; `canInstall` рефрешится по `Lifecycle.RESUME`.
- **Офлайн-режим (полная двусторонняя синхронизация)**: Room + `SyncEngine` + `WorkManager`, экран разрешения конфликтов, soft-delete, версии записей.
- **Notifications screen**: отдельный экран частот напоминаний (daily / weekly / monthly / quarterly), grid-picker дней, themed M3 TimePicker.
- **Bulk edit / multi-select**: long-press multi-select на всех списках, FAB-row групповых действий, счётчик в `TopAppBar`, `SelectionOverlay` без puck.
- **Custom categories**: динамические per-section категории, прогрев общего `CategoryRepository` за одну загрузку через `/api/categories/all`.
- **Статистика — bulk-загрузка** через `/api/statistics/overview`.
- **Skeleton loading**: shimmer-плейсхолдеры карточек заменяют центральный спиннер; pull-to-refresh не сбрасывает в скелетоны.
- **Hide values** — глобальный тумблер прячет суммы сплошными плашками в `SummaryCard` и `SwipeableTransactionCard`.
- **Initial balance** — UI в `IncomeScreen` с навигатором по месяцам.
- **Темная тема**: `BudgetTheme(isDark)`, `darkColorScheme`, `LocalIncomeColor` / `LocalExpenseColor` CompositionLocals, DataStore-ключ `dark_mode`, переключатель в `SettingsDialog`.
- **Версия API** в `SettingsDialog` и `ConnectScreen` — берётся через `/api/version`.

#### Changed
- `MainActivity` переведён на `FragmentActivity` (требование Biometric).
- Даты парсятся лениво, толерантно к нескольким форматам.
- Лазичная загрузка данных: только при первом открытии экрана + pull-to-refresh + явные действия пользователя.
- Логотип `mb` (Nunito ExtraBold с подчёркиванием под `b`) — `MbLogo` composable + PNG-иконки приложения.

#### Fixed
- Гонка в DataStore при первой инициализации.
- Индикатор активного таба в NavBar; адаптивная иконка; статус-бар.
- Маленькая иконка уведомлений — alpha-only.
- BackHandler корректно работает на оверлеях.
- Workaround для логкэта на HONOR / Magic OS.
- `viewModel(key=...)` — уникальный ключ на класс ViewModel, чтобы избежать каскадных `onCleared`.
- `LinearProgressIndicator` stop-dot, `ListItem` в `AlertDialog` на тёмной теме, `ModalBottomSheet` overlap c клавиатурой, FAB-swap shadows в `AnimatedContent`.

#### Security
- PIN хранится через PBKDF2.
- DataStore-ключ для биометрии и тайм-аута блокировки.

### [1.0.0] — 2026-04-29

- Первая публичная версия: Native UI на Jetpack Compose, экраны подключения, входа, доходов, расходов, листа желаний, статистики, прогноза, экспорта; локальные уведомления-напоминания.

---

## Прод-инфраструктура (вспомогательно)

Не имеет своей версии и не покрыта semver — но изменения значимые:

- 2026-05-05: `docker-compose.prod.yml` — порты на loopback, MongoDB не пробрасывается, `Dockerfile.prod` distroless, `make prod-*` цели, бинд-маунт `./apks` в nginx-фронт, скрипт `tools/build-android-release.sh` для подписанной сборки.
- 2026-05-05: нагрузочное тестирование — отдельная БД `budget_loadtest`, цели `seed-loadtest`, `loadtest-up`, `loadtest-restore`, `loadtest-drop`.
- 2026-05-06: `tools/build-android-release.sh` теперь сам копирует подписанный release-APK в `apks/` и подчищает старые версии там же — цикл "bump VERSION → `make android-release` → in-app self-update" стал одной командой, как уже было для debug-сборки через `build.sh`.

[1.7.0]: ./backend/VERSION
[1.0.0]: ./backend/VERSION
