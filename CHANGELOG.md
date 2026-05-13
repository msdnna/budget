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

### [1.17.0] — 2026-05-13

#### Added
- **`User.IsAdmin` + bootstrap-promоушн**: новое поле `is_admin` в коллекции `users`. `UserRepository.EnsureAdmin` идемпотентно даёт права самому раннему юзеру по `_id` ObjectID (timestamp), вызывается на старте бэкенда — single-user инсталляции автоматически получают админа. JWT claims + login response + `/auth/me` несут `is_admin`. `create_user` CLI получил флаг `-admin`.
- **`AdminRequired` middleware** (403 если `!claims.IsAdmin`), пристёгивается к admin-подгруппе protected-роутов.
- **`PATCH /api/categories/:id`** (admin-only) — частичное обновление `name`/`color`/`icon`/`icon_scale`. Pointer-семантика: nil = не трогать, "" / 0 = очистить (или ресет в client-default). Бампает `version` + `updated_at` → sync-клиенты подхватят. Дефолтные категории можно редактировать (удалять нельзя как раньше). `UpdateCategoryRequest` модель.
- **`Category.icon_scale` (float, optional)** — админ масштабирует иконку относительно бейджа в легенде/списках (pie-слайс игнорирует, там фикс под читаемость арки). 0 / отсутствует = client-default 1.0; >1 — увеличить + обрезать по форме бейджа; <1 — уменьшить.
- **Хранилище пользовательских иконок** — коллекция `category_icons` (`_id` UUID, `mime_type`, `size_bytes`, `data` binary inline, `uploaded_by`, `uploaded_at`). Inline вместо GridFS — иконки маленькие (PNG ≤512KB, SVG ≤64KB), одно чтение на рендер. Ссылка из категории через `Category.icon = "custom:<id>"`.
- **Эндпоинты иконок**:
  - `POST /api/icons` (admin, multipart `file`) — валидация magic-bytes (PNG `\x89PNG...` или `<svg` в первой 1KB), лимит 512KB.
  - `GET /api/icons/:id` (auth) — отдаёт байты с правильным `Content-Type` + `Cache-Control: public, max-age=86400, immutable`.
  - `GET /api/icons` (admin) — список метаданных без payload.
  - `DELETE /api/icons/:id` (admin) — hard delete; категории, ссылающиеся на удалённую иконку, остаются валидными (клиент откатывается в colored-badge режим).

#### Tests
- `TestCategoryRepo_UpdateAppliesPartialFields` — pointer-семантика PATCH (nil не трогает, "" / 0 ресетят); бамп `version`; round-trip `icon_scale` 1.5 + reset в 0.
- `TestUserRepo_EnsureAdminPromotesEarliestAndIsIdempotent` — пустая коллекция no-op; промоут самого раннего по `_id`; повторный вызов не двигает админа.
- `TestUserRepo_SetAdminToggles` — grant/revoke.
- `TestCategoryIconRepo_RoundTrip` — Create/FindByID/List/Delete; `List` не возвращает payload; повторный Delete → `ErrNoDocuments`.
- `TestSniffIconMime` — 7 кейсов: PNG magic, SVG (с/без xml declaration / DOCTYPE), пустой файл, JPEG, HTML.

### [1.16.0] — 2026-05-12

#### Added
- **Color & icon на категориях (Phase 1 нового визуала pie charts).** `models.Category` получил необязательные поля `color` (hex `#RRGGBB`) и `icon` (строковый ключ из общего словаря, мирится с web `frontend/src/utils/categoryIcons.js` и android `ui/icons/CategoryIcons.kt`). 14 дефолтных expense + 7 income + 7 wishlist категорий теперь засеваются с заранее подобранным цветом и иконкой. `EnsureDefaults` идемпотентно бэкфилит цвет/иконку на дефолтных строках, засеянных до этого релиза (бампит `version` + `updated_at` → sync-клиенты заберут).
- `CreateCategoryRequest` и `CategoryRepository.Create` принимают optional `color`/`icon` (handler пробрасывает).

### [1.15.0] — 2026-05-12

#### Added
- **Unit + integration test suite (с нуля до ~51% line coverage).** `config/` (89%, env defaults + overrides), `middleware/auth` (70%, JWT happy/expired/wrong-secret/non-HMAC), handler-уровень: auth (login + me + ListUsers), transactions/wishlist/categories CRUD, sync push/pull, statistics summary/by-category/monthly/overview/forecast, detail-requests full flow + cancel; repository-уровень: TransactionRepository (Create/Update/Delete с conflict, Upsert, Find с фильтрами, AggregateByCategory/MonthlyRange, FindLinkedToWishlist*, UnlinkFromWishlist, GetAverageMonthlyCategoryExpensesUnlinked, children-методы), WishlistRepository, CategoryRepository (включая EnsureDefaults идемпотентность + protection дефолтных), UserRepository, DetailRequestRepository. Helper-функции export.go (truncate / txTypeLabel / cellName).
- **testcontainers-go для интеграционных тестов с Mongo.** Один общий контейнер на test-binary (`sync.Once`), каждый тест получает свою БД через `dbCounter`. Авто-skip если Docker недоступен или передан `go test -short`. Помощник в `backend/internal/mongotest`.
- **Make-таргеты:** `make test-backend` (unit), `make test-backend-cover` (`-coverpkg=./... -coverprofile`, генерирует `cover.html`), `make test-backend-integration` (build-tag `integration`). `backend/coverage.out` и `cover.html` добавлены в `.gitignore`.

### [1.14.2] — 2026-05-12

#### Changed
- **Линтеры backend подняты до чистого состояния.** Добавлен `backend/.golangci.yml` (govet, errcheck, staticcheck, ineffassign, unused, bodyclose, gocritic, revive, misspell, unconvert, prealloc + gofmt-formatter); `make lint` / `make lint-backend` запускают `gofmt -l . + go vet + golangci-lint run` и зелёные.
- Все `bson.D{{"key", v}}` переведены на keyed-форму `bson.D{{Key: "key", Value: v}}` — `go vet` теперь чистый по `composites`.
- `handlers/sync.go`: `finishOp[T]` — `context.Context` первым параметром (revive `context-as-argument`).
- `handlers/export.go`: параметр `truncate(max int)` → `maxLen` (revive `redefines-builtin-id`).
- `cmd/seed_loadtest`: флаг-переменная `clear` → `clearDB` (revive `redefines-builtin-id`).
- `repository/transaction_repo.go`: `x = x / months` → `x /= months` (gocritic `assignOp`).
- `main.go`: `r.SetTrustedProxies` теперь обрабатывает ошибку.

### [1.14.1] — 2026-05-12

#### Changed
- Лицензия проекта изменена с Proprietary на **Apache 2.0**. `@license.name Apache 2.0` + `@license.url` в swag-аннотациях, спека перегенерирована. `Dockerfile.prod` label `org.opencontainers.image.licenses="Apache-2.0"`, `image.source` → `https://github.com/msdnna/budget`.

### [1.14.0] — 2026-05-12

#### Security
- **Fail-closed на критичных env в prod.** При `APP_ENV=production` отсутствие `JWT_SECRET` или `MONGO_URI` приводит к `log.Fatal` на старте (раньше — silently boot с dev-defaults `"dev-secret-change-in-production-min32chars!"` и `mongodb://admin:password@localhost...`). `JWT_SECRET` короче 32 символов теперь логирует warning.
- `docker-compose.prod.yml`: `JWT_SECRET=${JWT_SECRET:?...}`, `MONGO_USERNAME/PASSWORD` тоже required — compose отказывается стартовать без них.
- `GIN_MODE=release` и `SWAGGER_ENABLED=false` форсятся через env в prod-compose: убирает debug-логирование запросов и закрывает `/swagger/*` (он уже был недоступен через nginx, теперь — defense-in-depth).
- `backend/Dockerfile.prod`: переход на `gcr.io/distroless/static-debian12:nonroot` — контейнер крутится под uid 65532 вместо root.

#### Added
- OCI image labels (`org.opencontainers.image.{title,version,revision,source,description,licenses}`) в обоих Dockerfile-ах — будут заполняться из `make prod-build` (читает VERSION + `git rev-parse --short HEAD`). Готово к публикации в GHCR.
- `backend/.dockerignore` и `frontend/.dockerignore` — `.env`, `.git`, `node_modules`, локальные cmd-бинарники не попадают в build-context.

### [1.13.0] — 2026-05-11

#### Added
- **OpenAPI 3.1 + Swagger UI.** Бэкенд аннотирован комментариями `swaggo/swag` v2; спека генерируется в `backend/docs/swagger.{json,yaml}` (OpenAPI 3.1.0) и отдаётся через `GET /swagger/index.html` (UI на Swagger UI 5 с CDN) и `GET /swagger/doc.json` (raw spec). Покрыты все ручки: auth / transactions / wishlist / categories / statistics / sync / detail-requests / export / meta.
- Спека embed-ится в бинарь (`//go:embed docs/swagger.json`); `info.version` подставляется из `backend/VERSION` в рантайме. Никаких runtime-зависимостей от `swaggo/*` — UI на 100% статичен.
- Перегенерация — `make swag` (CLI ставится через `make swag-install` → `swaggo/swag/v2`).
- Env-флаг `SWAGGER_ENABLED=false` отключает UI и JSON-эндпоинт (например, в prod). По умолчанию включено.

### [1.12.1] — 2026-05-08

#### Changed
- `POST /api/wishlist/:id/unlink-period` теперь принимает и `frequency=once` итемы — для разовых wishlist'ов period не имеет смысла, поэтому очищается единственная привязанная транзакция (без date-filter). Раньше handler возвращал 400 для once. Нужно для нового поведения «Не куплено» (отвязать без удаления).

### [1.12.0] — 2026-05-08

#### Added
- Поле `regular_contrib` в `ForecastResponse` — сумма вкладов только регулярных wishlist-итемов; клиенты используют для отдельной саммари-карточки «Регулярные расходы / мес» (а «Список желаний / мес» = `wishlist_contrib − regular_contrib`).
- Поле `notes` у каждого `regular_items[]` — мирорит wishlist-item.notes, чтобы префилл-форма расхода могла точно скопировать «Назначение» (= name) и «Описание» (= notes) без дополнительного запроса.

### [1.11.0] — 2026-05-08

#### Changed
- **Forecast: переход на next-due модель.** Регулярные wishlist-итемы больше не размазываются /3 / /12 на месяцы — вклад в `total_monthly` теперь равен полной `estimated_cost` (или 0), исходя из даты следующего платежа: `next_due = last_paid_date + period`; вклад добавляется когда `next_due ≤ now + 1 месяц`. Monthly всегда вкладывается, never-paid тоже (трактуется как due now). Это устраняет ситуацию, когда после оплаты годового платежа прогноз на следующий месяц увеличивался.
- `historical_avg` теперь считается **только по транзакциям без `wishlist_id`** (`GetAverageMonthlyCategoryExpensesUnlinked`) — связанные с регулярными итемами расходы уже учитываются через next-due, иначе двойной счёт.
- `monthly_cost` в `regular_items` теперь = полная `estimated_cost` (без деления). Клиенты подставляют суффикс по `frequency`: «₽/мес» / «₽/кв» / «₽/год».

#### Added
- Поле `next_due_date` (YYYY-MM-DD) в `RegularItemForecast` — вычисленная дата следующего ожидаемого платежа. Пустое для never-applicable случаев.

### [1.10.0] — 2026-05-08

#### Added
- Поле `wishlist_id` у `Transaction` — ссылка с расхода на регулярный wishlist-итем (коммуналка/связь/Интернет и т.п.). Принимается в `POST /api/transactions`, `PUT /api/transactions/:id` (пустая строка отвязывает) и round-tripит через `/api/sync/push`.
- В ответе `GET /api/statistics/forecast` у каждого `regular_items` появились поля `estimated_cost: float`, `paid_this_period: bool`, `paid_amount: float`, `paid_count: int` — клиенты префиллят форму расхода полным `estimated_cost`, а флаги рассчитываются по транзакциям с заданным `wishlist_id`, чьи даты попадают в текущий период (месяц / квартал / год соответственно частоте). ≥1 связанная транзакция = «оплачено».
- `POST /api/wishlist/:id/unlink-period` — массово очищает `wishlist_id` у всех транзакций в текущем периоде регулярного итема (бэкенд для кнопки «Отменить»).
- Индекс по `(wishlist_id, date desc)` в коллекции `transactions`.

#### Changed
- `Forecast()` исключает «оплаченные за период» регулярные итемы из `total_monthly`, `wishlist_contrib` и категорийной разбивки — устранён двойной учёт «факт расхода + плановый wishlist».

### [1.9.0] — 2026-05-08

#### Added
- Запросы на детализацию (детализационные запросы над lump-sum расходами): модель `DetailRequest` + коллекция `detail_requests` + индексы (`assignee.user_id+status`, `parent_transaction_id`, `creator.user_id`, `status`).
- Новые поля у `Transaction`: `parent_id` (ссылка на родителя у дочерних), `detail_request_id` / `detail_request_status` (на родителе), `excluded_from_stats` (денормализован для простоты агрегаций).
- Эндпоинты:
  - `POST /api/detail-requests` — создать (creator может назначать на себя или на любого пользователя семьи)
  - `GET  /api/detail-requests?assignee_id=me|...&creator_id=me|...&status=open|closed`
  - `GET  /api/detail-requests/:id` — возвращает `{request, parent, children}`
  - `POST /api/detail-requests/:id/transactions` — добавить child (только assignee, только пока `open`)
  - `POST /api/detail-requests/:id/close` — Готово (только assignee, требует ≥1 child; помечает родителя `excluded_from_stats=true`, дочерние — `false`)
  - `POST /api/detail-requests/:id/cancel` — отменить (только creator; soft-delete детей, сброс флагов на родителе)
- `GET /api/transactions?include_detailed=true|false` — параметр (default `false`) скрывает родителей закрытых запросов из listing'а; статистика их игнорирует всегда.

#### Changed
- Агрегации `GetSummary` / `AggregateByCategory` / `AggregateMonthlyRange` исключают записи с `excluded_from_stats=true` (открытые children и закрытые parents).
- `buildTransactionFilter` дополнительно исключает pending children открытых запросов (`parent_id != '' AND excluded_from_stats=true`).
- `decodeTransactionPayload` пропускает новые поля при `/api/sync/push`, чтобы дочерние транзакции корректно round-tripили через offline-sync.

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

### [1.21.0] — 2026-05-13

#### Changed
- **Unicode-маркеры на кнопках заменены на ionicons5** (Phase 3 — иконки на кнопках):
  - **Кнопки удаления** (`✕`) → `TrashOutline` через `<template #icon>` слот NButton. Точки правки: `IncomeView` (delete initial-balance + delete-row), `ExpensesView` (delete-row), `ForecastingView` (две кнопки удаления регулярных/wishlist), `DetailRequestModal` (remove-child).
  - **Inline-edit confirm/cancel** (`✓` / `✗`) → `CheckmarkOutline` / `CloseOutline`. `okBtn` / `cancelBtn` helpers в `IncomeView` и `ExpensesView` (render-функция через `h(NIcon, ...)`), 8 пар inline-edit кнопок в `ForecastingView` (template `replace_all`).
  - **Trend-prefix статистики** (`↑` / `↓`) перед суммами Доходы/Расходы в `StatisticsView` → `TrendingUpOutline` / `TrendingDownOutline` (`:color` от primary / palette.expense, size=20).
  - **Row-actions в таблицах Доходов/Расходов**:
    - «Скрыть/Показать» (`●` / `○`) → `EyeOutline` / `EyeOffOutline`;
    - «Добавить как шаблон» (`+`) → `CopyOutline` (семантика «дублировать запись из шаблона»);
    - «Запрос на детализацию» (`⇲`, только в Расходах) → `ListOutline` (раскладка lump-sum в список детальных трат).
- Сохранены: мат-знаки `+` / `−` (баланс — формат значения, не UI-маркер) и `%` / `₽` в pie-unit-toggle (семантика единиц измерения).

### [1.20.0] — 2026-05-13

#### Added
- **Настройки `/settings/categories`** — админ-страница управления категориями. Sidebar получил новый узел «Настройки» с раскрывающимся подменю «Категории» (структура готова к будущим разделам); `n-menu` `v-model:expanded-keys` + auto-expand при заходе на child-роут / deep link. Видимость — только админам (`auth.isAdmin`); `router.beforeEach` редиректит остальных на `/statistics`. Маршрут `/admin` редиректит на `/settings/categories` (бэк-совместимость). `currentTitle` в header резолвит составные ключи (`settings/categories` → «Настройки · Категории»).
- **3-колоночный split layout** в редакторе категорий: `[разделы] [список + «Добавить»] [in-place редактор]`. Создание через `POST /api/categories` + (если `icon_scale ≠ 1`) follow-up PATCH. На <1100px схлопывается в 2 ряда, на <720px — в 1.
- **Icon picker** в редакторе:
  - грид auto-fill, тайлы окрашены в выбранный цвет категории (live preview), встроенные иконки белые, кастомные на цветном фоне;
  - кнопка «+» открывает `n-popover` (trigger=click) с autofocus-инпутом поиска по **всему каталогу `@vicons/ionicons5`** (~1300 глифов, фильтр по подстроке, до 96 результатов) + кнопкой «Загрузить свою иконку» (PNG/SVG до 512KB);
  - recently-used сетка: дефолтный seed = `CATEGORY_ICON_ORDER` (27 курируемых), `localStorage['category-icons-recent-v1']` cap 30, любой пик продвигает ключ наверх. Custom (`custom:<id>`) иконки всегда видны и не вытесняют builtin'ы.
- **Слайдер «Масштаб иконки в бейдже»** (виден только для custom-ключа): 0.6×–2.2× с шагом 0.05 + preview-бейдж рядом с WIP-параметрами. Применяется в легенде/списках; pie-слайс остаётся фикс 16px.
- **Аплоад кастомных иконок** через grid тайл «+»: `accept="image/png,image/svg+xml"`, 512KB лимит на клиенте; после успеха иконка попадает в blob-кеш и автоматически выбирается.
- **`useIconCacheStore`** (Pinia): фетчит `/api/icons/:id` как blob через axios (Bearer auth) → `URL.createObjectURL()` для шаблонов. `<img>` нельзя авторизовать напрямую, поэтому через blob URL. Inflight-дедупликация, утилита `parseCustomIconKey('custom:<id>')`.
- `auth.isAdmin` computed + `is_admin` в сохранённом user; `auth.verify()` обновляет флаг из `/auth/me`. API: `categories.update()`, `icons.list/upload/remove/image`.
- `utils/categoryIcons.js` теперь экспортирует `ICON_NAMES` (отсортированный список всех PascalCase имён для поиска) + `normalizeIconKey()` (PascalCase → curated kebab, чтобы «Cart» и «cart» дедуплились). `categoryIcon()` падает на `Ionicons5[key]` для PascalCase ключей — `Category.icon` может хранить и kebab-case, и PascalCase, и `custom:<id>`.

#### Changed
- **CategoryDonutChart**: тройное состояние иконок (`builtin` / `custom` / `none`). Пустой `iconKey` → только цветной бейдж без глифа (на слайсе и в легенде); `custom:<id>` → `<img>` из `useIconCacheStore` (16px на слайсе, scale × 22px в легенде с `overflow: hidden` на бейдже — переразмеренная иконка клиппится по `border-radius`).
- Порог группировки мелких слайсов в «Прочее» поднят 3% → 5%; `MIN_ICON_PCT` тоже 5%. Слайсы 3–4% больше не выживают в pie chart без иконки — уходят в «Прочее» автоматически.
- `categoryIcon(key)` возвращает `null` для unknown/empty/`custom:` ключей (раньше fallback'ил к `PricetagOutline`); новый `iconKind(key)` возвращает `'builtin' | 'custom' | 'none'`.

#### Theming
- `/settings/categories` адаптирован к тёмной теме: обёрточные `div`-ы пробрасывают токены `palette` (surface / surfaceAlt / border / hover / text1-3) и primary через CSS-переменные `--admin-*` на root-узле и потребляют их из `<style scoped>`. Hover/active-состояния, скроллбар, picker-tile рамка, picker-popover текст — всё на токенах.
- Кастомный thin scrollbar на `.list-rows` + `.editor-body` (translucent gray 25/45% thumb, прозрачный track, `width: 6px`).
- `.admin-shell` высота `calc(100vh - var(--app-header-h, 64px) - 48px)` — точно по периметру `n-layout-content`, без внешнего scrollbar справа.

### [1.19.0] — 2026-05-13

### [1.19.0] — 2026-05-13

#### Added
- **`CategoryDonutChart.vue` — донат с многоцветными слайсами, иконками поверх Canvas и кастомной правой легендой (Phase 1 нового визуала pie charts).** Слайс окрашивается в `Category.color` из бэкенда; категория без цвета получает стабильный fallback по хешу имени. Подключено на `StatisticsView` (Расходы/Доходы) и `ForecastingView`.
- **Иконки на слайсах**: 16px белая иконка позиционируется абсолютно поверх ECharts по cumulative-углам, слайсы < 4% бейдж пропускают. Анимируются вместе со sweep-чартом — CSS-keyframe `cdc-icon-in` (700ms, 40% невидимы, 40→100% fade+scale из 0.4) ретриггерится через bump `animKey` в `:key` на каждое изменение `pieSlices`.
- **Кастомная правая легенда**: прокручиваемый `<div>` высотой 320px с темо-зависимым скроллбаром (`scrollbar-color` + `::-webkit-scrollbar-*` через CSS-переменные на `palette.border` / `palette.text3`). Каждая строка — три колонки `[цветной бейдж 36×36 с иконкой] [название] [сумма + % правым столбцом]`, амаунт 14px medium, % мельче и сероват (12px @ 0.6). На <600px layout сворачивается вертикально.
- **Кликабельный фильтр в легенде**: клик по строке скрывает/показывает категорию на pie (opacity 0.4 + strikethrough), поддержаны Enter/Space + `role=button`.
- **Группировка мелких слайсов в «Прочее»**: всё что < 3% видимой суммы сливается в одну псевдо-долю серого цвета. Если объединяется только один слайс — оставляем как есть. Tooltip показывает «Прочее: N₽».
- `src/utils/categoryIcons.js` — общий словарь из 27 ионикон-ключей под банковский/семейный бюджет (cart, car, home, restaurant, fast-food, cafe, game-controller, medkit, school, shirt, phone-portrait, airplane, call, rose, barbell, cash, briefcase, trending-up, gift, key, desktop, flame, swap-horizontal, wallet, bag-handle, tag, ellipsis-horizontal), синхронизирован с android `ui/icons/CategoryIcons.kt`. Стабильная fallback-палитра из 14 цветов с hash-based выбором по имени категории.
- `categories.all()` в api клиенте — `StatisticsView` прогревает name → color/icon карту в один запрос.

#### Changed
- ECharts pie-серии: убран глобальный `chartColors` (раньше монохром-палитра от primary) — цвет задаётся per-slice через `itemStyle.color`. `legend.show=false`, label на слайсах тоже скрыт — вся метаданность в кастомной правой легенде. Радиус доната раздвинут с `['38%', '65%']` до `['52%', '78%']`. `chartColors` в theme store оставлен для bar-чартов.
- `itemStyle.borderColor: palette.surface` + `borderWidth: 2` + `borderRadius: 6` — зазор между слайсами окрашен в цвет карточки (theme-aware), углы скруглены.
- Hover: `emphasis.scale: true, scaleSize: 8` (слайс выезжает наружу), без drop-shadow — на светлой теме тень просвечивала через `palette.surface` border соседей и читалась как halo.

### [1.18.1] — 2026-05-12

#### Fixed
- **Мобильный header у гостя перегружен:** скрыта дублирующая кнопка «Войти» (та же кнопка остаётся по центру в `AuthGate`).
- **«Запросы на детализацию» больше недоступны под неаутентифицированным пользователем.** `DetailRequestBell` в desktop- и mobile-header'ах теперь рендерится только при `auth.isAuthenticated` (исчезает и иконка, и попап).

#### Changed
- **Forecast: форма «Добавить» теперь над списками** регулярных расходов и желаний (на мобильном порядок стал: chart → Добавить → Регулярные → Список желаний). На десктопе первая 2-колоночная сетка — `[Прогноз по категориям | Добавить]`, вторая — `[Регулярные расходы | Список желаний]`.

### [1.18.0] — 2026-05-12

#### Changed
- **Node 22 → Node 24 LTS** в `frontend/Dockerfile`. Добавлены `frontend/.nvmrc` (`24`) и `engines.node: ">=24"` в `package.json`.
- **Менеджер зависимостей: npm → Yarn 4 (berry, classic node-modules linker).** В `package.json` запинен `packageManager: yarn@4.14.1`, добавлен `frontend/.yarnrc.yml` с `nodeLinker: node-modules`. `package-lock.json` удалён, появился `yarn.lock` (v8). Dockerfile теперь `corepack enable` + `yarn install --immutable` + `yarn build`. `Makefile` и `tools/aggregate-reports.py` вызывают `corepack yarn …`, чтобы маршрутизация шла через закреплённую версию вне зависимости от глобально установленного yarn'а. `.gitignore` дополнен `frontend/.yarn/*` (с исключениями releases/plugins/sdks/patches/versions) и `frontend/.pnp.*`.
- **Обновлены зависимости фронта до актуальных:**
  - `eslint` 9.39 → 10.3, `@eslint/js` 9.39 → 10.0, `globals` 16.5 → 17.6 (мажорные).
  - `axios` 1.15 → 1.16, `vue` 3.5.33 → 3.5.34, `vite` 8.0.10 → 8.0.12 (минорные/патчевые).
  - `eslint-plugin-vue` / `eslint-config-prettier` / `vue-eslint-parser` уже на peer-диапазоне `eslint ^10` — без правок конфига.

### [1.17.0] — 2026-05-12

#### Added
- **Юнит-тесты с нуля (CI-readiness, Phase 2 — тесты).** Vitest 4 + `@vue/test-utils` + happy-dom + `@vitest/coverage-v8` + `axios-mock-adapter`. **60 тестов, ~62% line coverage** в покрываемой части (88% на stores, 85% на utility-компонентах, 76% на `api/index.js`):
  - `api/index.js` — request interceptor (Bearer), response interceptor (401 → clear + `auth:expired` event, backend error message extraction), `downloadBlob` helper.
  - Pinia stores: auth (login/logout/verify happy + 401), categories (load/add-dedup/remove/recordUse/sortByRecentUse/options), transactions factory (scope isolation, filter→params flattening, `setFilters` replace-semantics, CRUD refetch), wishlist, detailRequests, theme (palette generation, dark-mode overrides).
  - Utility components: `ConfirmActionButton` (two-step confirm + auto-reset + disabled-reset), `TilePeriodPicker` (month/year cells, navigation, clear/setNow buttons).
- **Make-таргеты:** `make test-web` (vitest run), `make test-web-cover` (HTML + lcov + text report). `make test` теперь зовёт `test-backend test-web`. `frontend/coverage/` добавлен в `.gitignore`.

### [1.16.3] — 2026-05-12

#### Changed
- **Линтеры web подняты до чистого состояния (CI-readiness, Phase 2 — линт).** Добавлен `frontend/eslint.config.js` (ESLint 9 flat config: `@eslint/js` recommended + `eslint-plugin-vue` flat/recommended + `eslint-config-prettier`), `.prettierrc.json` (singleQuote, no-semi, printWidth 100, trailing-comma all, arrow-parens always) и `.prettierignore`. `package.json` обзавёлся `"type": "module"` и скриптами `lint` / `lint:fix` / `format` / `format:check` (lint с `--max-warnings=0`).
- Все исходники в `frontend/src/` (`+ index.html` + `vite.config.js`) переформатированы Prettier'ом — `npm run format:check` и `npm run lint` зелёные.
- Удалены неиспользуемые импорты: `h, defineComponent` в `DetailRequestBell.vue`, `NThing` в `ForecastingView.vue`, `computed` в `stores/detailRequests.js`, неназначаемая `const props` в `LoginModal.vue`.
- Убран дубликат `@brushselected` в `StatisticsView.vue` (parsing-error `duplicate-attribute`), пустые `catch {}` дополнены поясняющими комментариями.
- `make lint-web` (`npm run lint` + `npm run format:check`); `make lint` теперь зовёт `lint-backend lint-web`.

### [1.16.2] — 2026-05-12

#### Changed
- Лицензия Apache 2.0: `frontend/Dockerfile` label `org.opencontainers.image.licenses="Apache-2.0"`, `image.source` → `https://github.com/msdnna/budget`.

### [1.16.1] — 2026-05-12

#### Security
- `frontend/nginx.conf`: `server_tokens off`, плюс `X-Content-Type-Options nosniff`, `X-Frame-Options DENY`, `Referrer-Policy strict-origin-when-cross-origin`, `Permissions-Policy geolocation=(), microphone=(), camera=()`. Headers продублированы в `location /` и `/apks/` — иначе nginx replaces (не merges) при per-location `add_header`.
- `frontend/Dockerfile` — OCI labels (`org.opencontainers.image.*`), build args `WEB_VERSION` + `VCS_REF`.

### [1.16.0] — 2026-05-08

#### Changed
- **«Куплено» в wishlist теперь работает как «Оплачено» в регулярных:** клик по кнопке открывает ту же модалку «Зафиксировать покупку» (заголовок меняется по типу), создаёт расход с `wishlist_id` и переключает `purchased=true`. Раньше просто флипал флаг без транзакции.
- **«Не куплено»**: теперь зовёт `POST /api/wishlist/:id/unlink-period` (для once это очищает единственную привязку) + сбрасывает `purchased=false`. Сама транзакция остаётся в Расходах — отвязка без удаления.
- Модалка ввода объединена в один компонент с `payKind: 'regular' | 'wishlist'` — заголовок и сообщение об успехе адаптируются.

### [1.15.0] — 2026-05-08

#### Added
- Inline pencil-edit для всех полей wishlist-итемов: название, категория, заметки (раньше — только стоимость). Один и тот же `editingId/editingField/editValue` обслуживает все четыре редактируемых поля.
- Inline pencil-edit для записей «Регулярных расходов» (имя/категория/заметки/стоимость) в той же манере.
- Bulk-edit (пакетное редактирование) для регулярных расходов: кнопка в заголовке карточки. Действия: «Отменить» (видно если ≥1 выбран и `paid_this_period`) + «Удалить». Двухшаговое подтверждение через `ConfirmActionButton`.

#### Changed
- Кнопка «Куплено» в строке wishlist теперь зелёная (`type="success"`). «Не куплено» остаётся дефолтной.
- В строке регулярного расхода рядом с категорией теперь видны заметки (если есть) и плейсхолдер «без заметок» — иначе непонятно, как редактировать пустое поле.

### [1.14.0] — 2026-05-08

#### Added
- Четвёртая summary-карточка «Список желаний / мес» (= `wishlist_contrib − regular_contrib`); существующая «Регулярные расходы / мес» (= `regular_contrib`) — раньше под этим лейблом показывалась общая сумма wishlist'а вместе с регулярными.
- Поле «Назначение» в модалке «Зафиксировать оплату».

#### Changed
- «Регулярные расходы» и «Список желаний» — внешняя `n-card` с заголовком и внутренние sub-`n-card` (`embedded`) для каждой записи. Списки выровнены по тому же flex-rhythm'у, что регулярные.
- Фриквенси-теги и «Куплено» теперь с `round` для визуальной согласованности с бейджем «Оплачено».
- В модалке «Зафиксировать оплату» исправлен префилл: `purpose` ← `item.name` (раньше попадало в description), `description` ← `item.notes` (раньше игнорировалось).
- В строке wishlist рядом с категорией теперь показываются `notes` если они есть.

### [1.13.0] — 2026-05-08

#### Changed
- **Прогноз: разделены «Список желаний» и «Регулярные расходы».** Раньше регулярный wishlist-итем дублировался в обоих списках; теперь wishlist показывает только `frequency=once`, регулярные — только не-once.
- Каждый регулярный итем теперь отдельная карточка с единым layout'ом строки (название + freq-tag + сумма + три кнопки одинакового размера: «Оплачено», «Отменить», «✕ удалить»). Заголовок «Регулярные расходы» вынесен наружу карточек.
- Сумма отображается с per-period суффиксом: monthly → `₽/мес`, quarterly → `₽/кв`, yearly → `₽/год` (вместо принудительного `₽/мес`).
- В форме добавления сверху появился сегментный селектор типа: **«Желаемая покупка»** / **«Регулярный расход»**. Селектор частоты показывается только для «Регулярного расхода» и предлагает [monthly/quarterly/yearly] (без `once`). Дефолт — «Желаемая покупка».
- Тогл «Куплено» убран из формы добавления (статус ставится позже через действие на карточке).
- При оплаченном регулярном итеме рядом с категорией появляется «следующая оплата: DD.MM.YYYY» (берётся из `next_due_date`).

### [1.12.0] — 2026-05-08

#### Added
- В карточке «Регулярные расходы» прогноза:
  - Кнопка **«Оплачено»** на каждой строке: открывает компактную модалку с предзаполненной суммой (`estimated_cost`), категорией и описанием — на сохранении создаёт расход с `wishlist_id`, привязывая его к регулярному итему. Многократный клик = несколько связанных транзакций (доплаты).
  - Кнопка **«Отменить»** (видна только когда `paid_this_period`): двухшаговое подтверждение → `POST /api/wishlist/:id/unlink-period` массово снимает `wishlist_id` со всех транзакций текущего периода.
  - Бейдж «Оплачено · X ₽» (`n-tag` success) и `line-through` на названии и сумме при `paid_this_period`.
- `wishlist.unlinkPeriod(id)` в API-клиенте.

### [1.11.2] — 2026-05-08

#### Fixed
- `DetailRequestModal` отклеивался от центра экрана и не закрывался по клику вне: `margin: 24px` на самой модалке ломал flex-центрирование Naive; `mask-closable: false` блокировал backdrop-клик. Заменено на `width: min(1024px, calc(100vw - 48px))` + `max-height: calc(100vh - 48px)` без margin, `mask-closable` возвращён к дефолту.

### [1.11.1] — 2026-05-08

#### Changed
- `DetailRequestModal` — двухколоночный layout: слева форма «Добавить расход», справа список добавленных (мирроринг страницы Доходов/Расходов). Прогресс-карточка сверху, мета+кнопки в footer.
- Модалка ограничена `max-width: min(1024px, calc(100vw - 48px))` и `max-height: calc(100vh - 48px)` с `margin: 24px` — не упирается в края экрана при высоком контенте; внутренний контент скроллится.

### [1.11.0] — 2026-05-08

#### Added
- Запросы на детализацию (поверх API 1.9.0):
  - Колокольчик в header (Pinia-store `detailRequests`, `AlertCircleOutline` + бейдж количества открытых) с popover в стиле version-popover (uppercase title, `text3`, табы Открытые/Закрытые, отдельный компонент `DetailRequestBellList`).
  - Модалка `DetailRequestModal.vue` — прогресс-карточка (target/total/count, остаток в баланс или сверх), список children, форма «добавить расход», кнопки «Готово»/«Отменить запрос».
  - Модалка `DetailRequestCreateModal.vue` — выбор assignee из списка пользователей.
  - В `ExpensesView`: pinned-баннер сверху со списком моих открытых запросов, жёлтая подсветка строки родителя, иконка «⇲» в actions (создать или открыть). Колонка actions выровнена по правой стороне (`justify: 'end'`), чтобы дочерние строки не оставляли провал на месте отсутствующей кнопки.
  - Чекбокс «Показать закрытые запросы» в фильтре расходов — пробрасывается в `?include_detailed=true`. По умолчанию закрытые-родительские транзакции скрыты.

#### Changed
- Иконки в header `i` (версия) и `!` (запросы на детализацию) увеличены с 16 → 18 px для разборчивости.
- Глобальные модалки `DetailRequestModal` / `DetailRequestCreateModal` смонтированы в `App.vue`, состояние видимости управляется через store (`openRequestId`, `creatingForTx`) — открываются и из колокольчика, и из ExpensesView.

### [1.10.0] — 2026-05-07

#### Changed
- **Редизайн логотипа `MbLogo`.** Буквы «mb» получили скульптурные тени-градиенты под каждым горбом «m» и боулом «b» (linear black-alpha → transparent), создающие эффект 3D-глубины. Подчёркивание под «b» отделено в нейтральный серый (`#CED4E1`) — больше не наследует тон букв. Межбуквенный интервал сделан плотнее. Базовые path-буквы по-прежнему окрашиваются через `currentColor`, поэтому передаваемый из `App.vue` `primaryColor` продолжает работать; тени и подчёркивание — фиксированные оверлеи (theme-agnostic).
- **Favicon.** `frontend/public/favicon.svg` обновлён под новую геометрию + градиенты, базовый цвет — дефолтный брэнд-синий `#2080F0`.

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

### [1.33.0] — 2026-05-13

#### Added
- **Поддержка пользовательских иконок категорий** (Phase 2). `PieSlice` получил поля `customIconUrl: String?` + `iconScale: Float` (default 1.0). `StatisticsScreen.customIconUrl()` собирает `<serverUrl>/api/icons/<id>` для категорий с `icon = "custom:<id>"`; `normalizeIconScale()` нормализует серверный 0 (= default) в клиентский 1f. DonutChart on-slice оверлей и `ChartLegend` бейдж рендерят: `Icon` для built-in, `AsyncImage` (Coil) для custom, пустой цветной квадрат — если иконки нет. Бейдж легенды теперь `.clip(RoundedCornerShape(8.dp))` + AsyncImage скейлится `.size((18 × scale).dp)` — переразмеренная иконка клиппится формой бейджа (эквивалент web `overflow: hidden` + scaled `<img>`).
- **Room migration v4 → v5** (`ALTER TABLE categories ADD COLUMN icon_scale REAL NOT NULL DEFAULT 0` — 0 = клиентский default). `CategoryEntity ↔ Category` маппинг сохраняет/возвращает `iconScale`.
- **Coil ImageLoader через `BudgetApplication.newImageLoader()`** — общий OkHttp клиент с интерсептором, который берёт `RetrofitClient.authToken` и пробрасывает `Authorization: Bearer <token>` для `/api/icons/<id>` (без этого `AsyncImage` ловил бы 401). Включён `SvgDecoder.Factory` для пользовательских SVG, disk-cache 8MB. Зависимость `io.coil-kt:coil-svg:2.7.0`.
- **Alias-map ionicons5 (PascalCase) → material-icons** в `ui/icons/CategoryIcons.kt`. 864 из 1332 имён сопоставлены с ближайшим material-эквивалентом — Android рендерит узнаваемую иконку для всего, что админ выбрал через free-text поиск во фронте. Стратегия: прямое имя → strip `Outline`/`Sharp` → курируемый алиас (~80 правил: Cart→ShoppingCart, Airplane→Flight, Call→Phone, GameController→SportsEsports и т.д.). Где material имеет `AutoMirrored.Filled.*` (Label, TrendingUp, DirectionsBike etc.) — используется он без deprecation warning'ов. Резолвер: курируемый kebab-словарь → alias-map → null (бейдж без глифа). Оставшиеся 468 имён (Albums, Aperture, Balloon, `*Circle`-варианты, логотипы соцсетей) фоллбэчат на пустой бейдж.
- `utils.parseCustomIconKey` для парсинга `custom:<id>`.

#### Changed
- `categoryIcon(key)` возвращает `null` для неизвестных/пустых/custom-ключей (раньше fallback'ил к `Label`).
- `MIN_VISIBLE_PCT` / `MIN_ICON_PCT` 3% / 4% → 5% / 5% (синхрон с web). Тонкие слайсы 3–4% уходят в «Прочее» автоматически.

### [1.32.0] — 2026-05-13

#### Added
- **Многоцветный DonutChart с иконками на слайсах + кастомная правая прокручиваемая легенда с иконками (Phase 1 нового визуала pie charts).** Слайс окрашивается в `Category.color` из синхронизованных данных; fallback — стабильная палитра по хешу имени категории. Размер доната увеличен 130 → 160dp.
- **Слайсы как настоящий Compose `Path`** с скруглёнными углами через `quadraticTo`: внутренняя дуга clockwise → угол → радиальное ребро → угол → внешняя дуга counter-clockwise → угол → ребро → угол → close. Эквивалент web `borderRadius: 6`. Зазор 1.5° между соседями оставляет видимым `MaterialTheme.colorScheme.surface` под Canvas — сепарация theme-aware без явного бордера. Спец-кейс для donut с одним слайсом (например, доход с одной категорией): рисуется как чистое замкнутое кольцо (`Path.fillType = EvenOdd` + два `addOval`), без углов и зазоров — иначе start/end рёбра встречались у 12 часов и создавали зарубку-«хвост».
- **Иконки на слайсах**: белая 16dp иконка позиционируется через `Modifier.offset` от центра `BoxWithConstraints` — `(cos/sin)(midAngle) * ((1 − strokeFraction/2) * radius)`. Слайсы < 4% иконку пропускают. Появляются вместе со sweep-анимацией. Фоллбэк-иконка `Label` для пользовательских категорий без `iconKey` — раньше иконки рисовались только у дефолтных, и в смешанных списках получалось «через одну».
- **Кастомная правая легенда** — новый wrapper `CategoryDonut` владеет фильтр-состоянием. Цветной чип-иконка 28dp слева, `verticalScroll` с `heightIn(max=200dp)` — длинный список категорий не разрывает карточку Statistics. Тап по строке скрывает/показывает категорию на pie (opacity 0.4 + strikethrough). Состояние фильтра сбрасывается при смене периода (key = joined-label-list).
- **Группировка мелких слайсов в «Прочее»**: всё что < 3% видимой суммы сливается в одну псевдо-долю slate-цвета (`#64748B`, иконка `ellipsis-horizontal`). Если объединять нечего (один слайс) — оставляем как есть.
- `ui/icons/CategoryIcons.kt` — общий словарь иконок (`material-icons-extended`) и палитра, синхронизирован с web `utils/categoryIcons.js`. 27 ключей под банковский/семейный бюджет.
- Room v4 + migration 3→4 (`ALTER TABLE categories ADD COLUMN color`, `ADD COLUMN icon` — оба `TEXT DEFAULT ''` nullable, иначе identityHash не сходится с `CategoryEntity.String?` и валидатор миграции бросает на первом обращении к таблице); `CategoryEntity` ↔ `Category` маппинг сохраняет/возвращает поля.

#### Changed
- Убрана однотонная `generateChartColors(primary, n)` для слайсов pie — функция удалена из `theme/Theme.kt` за ненадобностью.

### [1.31.0] — 2026-05-12

#### Added
- **Unit-test-сьют с нуля (JUnit 4 + MockK + Robolectric + MockWebServer + Turbine).** 43 теста по приоритетам из memory: SyncEngine.resolveKeepServer (7 кейсов на in-memory Room — adopt-server-tx, tombstone, missing-payload-deleteHard, missing-row→Skipped, wishlist/category аналоги, blank-serverUrl→Skipped), AppLock (6 кейсов на тайм-аут под ShadowSystemClock — within/after timeout, hasPin=false идемпотентность, unlock сбрасывает backgroundedAt), PinSecurity (5 кейсов — детерминизм с reused salt, разные salt'ы при дефолтной соли, verify happy/wrong-pin/mismatched-salt), ReachabilityGate (8 кейсов — parseHostPort defaults http/https/explicit/garbage, probeOnce Online на live-сокете / Offline на refused/unknown, setServerUrl сбрасывает state), ServerDiscovery (7 кейсов через MockWebServer — health ok+version / health ok+version 500 / unknown app / ok=false / 404 / empty body / unreachable port), RetryUtils (4 кейса — immediate-success / retry-then-success / HttpException нет retry / last-error throw), Mappers (6 кейсов — Transaction/Wishlist/Category entity↔model + syncStatus/serverPayload пробрасываются + parseTransaction(toJson())).
- **JaCoCo-coverage** (`enableUnitTestCoverage = true` + ручной `jacocoTestReport` task с runtime-jar в качестве class-source — иначе class-id не совпадают). `includeNoLocationClasses = true` обязателен, иначе Robolectric-тесты не учитываются. Итоговое покрытие: **19% line overall**, по таргетам: `data.security` 60%, `data.discovery` 36%, `data.model` 31%, `data.db` 25%, `data.sync` 15% — UI-пакеты (Compose-экраны, components, theme, notifications, MainActivity) исключены из репортинга.
- **Make-таргеты:** `make test-android` (`:app:testDebugUnitTest`), `make test-android-cover` (`:app:jacocoTestReport` + путь к HTML); `make test` теперь `test-backend + test-web + test-android`.

#### Changed
- `ReachabilityGate.parseHostPort` и `ReachabilityGate.probeOnce` переведены из `private` в `internal` — нужны для unit-тестирования (только publish-точка тестов; общая API-поверхность не меняется). То же для `ServerDiscovery.probe`.

### [1.30.0] — 2026-05-12

#### Changed
- **Линтеры Android подняты до чистого состояния.** Добавлены `ktlint 1.6.0` (CLI через отдельную Gradle-конфигурацию + `JavaExec`, потому что плагин `org.jlleitschuh.gradle.ktlint` 13.x не видит source-set'ы при AGP 9.2 со встроенным Kotlin) и `detekt 1.23.8` (`io.gitlab.arturbosch.detekt` плагин). Конфиги — `android/.editorconfig` (для ktlint, `ktlint_code_style=android_studio` + точечно отключённые правила: `function-signature`, `multiline-if-else`, `package-name`, `backing-property-naming`, `function-expression-body`) и `android/app/detekt.yml` (buildUponDefaultConfig + override: `LongMethod` ignoreAnnotated `Composable`, `CyclomaticComplexMethod` threshold=30, `MaxLineLength` 160, `MagicNumber/WildcardImport/UnusedParameter/UnusedPrivateProperty/PackageNaming/MatchingDeclarationName/ThrowsCount/DestructuringDeclarationWithTooManyEntries` off). `make lint-android` запускает `:app:ktlintCheck + :app:detekt`, `make format-android` — `:app:ktlintFormat`. `make lint` теперь включает android.
- Все Kotlin-файлы (61 файл / ~14.5k LOC) приведены в соответствие через `ktlintFormat`: indent, no-multi-spaces, statement-wrapping, wrapping, import-ordering, unused-imports и т.п. Точечные правки в `MainActivity.kt` (вынос `combine(...)` в многострочный вид), `ForecastScreen.kt` (KeyboardOptions на новой строке), `IncomeScreen.kt` (длинные `if/else` в Icon развёрнуты в блоки). `MainActivity.onCreate` помечена `@Suppress("detekt:CyclomaticComplexMethod")` — это entry point с большим бизнес-роутингом, рефакторинг не оправдан.

### [1.29.3] — 2026-05-12

#### Changed
- Лицензия Apache 2.0 (без изменений в коде приложения; bump для синхронизации релизного тега со сменой лицензии монорепо).

### [1.29.2] — 2026-05-11

#### Fixed
- Цвет аватара по инициалам теперь совпадает с фронтендом. `avatarBgColor` в `Common.kt` хэшировал `displayName` 32-битным `Int` (значение `hash` оборачивалось каждую итерацию), а web-версия в `UserAvatar.vue` ведёт `hash` как JS Number (double-precision, truncate в Int32 только на `<< 5`). Для имён длиннее ~6 кириллических символов это давало разный индекс палитры — двое реальных пользователей в семье схлопывались в один цвет на Android, хотя на вебе разводились. Кotlin-функция переписана на `Long` с явным `hash.toInt() shl 5`, что точно воспроизводит JS-арифметику (проверено: 14 примеров включая «Vasya Samoilov» / «Наташа Самойлова» — раньше оба → palette[6], теперь 6/10 соответственно, как на вебе).

### [1.29.1] — 2026-05-11

#### Fixed
- Системный жест «назад» в режиме inline-редактирования записи (расход / доход / wishlist) теперь возвращает в режим просмотра, а не закрывает bottom-sheet целиком. Добавлен `BackHandler(enabled = isEditing)` в `TransactionDetailSheet` и `WishlistInteractiveSheet` — до этого ModalBottomSheet перехватывал back и звал `onDismiss` независимо от состояния редактирования.

#### Changed
- Переход «PinScreen → Главный экран» стал плавнее: `AnimatedContent` для lock→main теперь использует `tween(520, FastOutSlowInEasing)` + `scaleIn(0.90f)` (раньше — `tween(280)` + `scaleIn(0.96f)`), что делает анимацию заметной поверх внутренней индикации разблокировки PIN. Для остальных смен фаз — прежний быстрый `tween(320)` + `scaleIn(0.96f)`.

### [1.29.0] — 2026-05-11

#### Changed
- **Reachability gate перед HTTP-трафиком.** Раньше при недоступном API холодный старт фанаутил 5+ параллельных запросов с `callTimeout=30s` — UI висел до 30 секунд, прежде чем перейти к офлайн-кэшу. Теперь перед первым запросом запускается единичный TCP-probe (`Socket.connect(host:port, 3s)`):
  - `Connection refused` / `Reset` / `UnknownHost` / `NoRouteToHost` (мгновенные сетевые отказы) → state=Offline, OkHttp interceptor мгновенно бросает `IOException` для всех последующих запросов, ViewModel'и читают из Room.
  - `SocketTimeoutException` пробы (handshake висит) → state=Online, обычный 30с-флоу (сервер может быть просто медленным).
  - Успешный TCP-connect → state=Online.
- Probe рефрешится: на холодном старте, при `ON_RESUME` (свёртывание / разворачивание приложения), после `AppLock.unlock()` (разблокировка PIN/biometric, в т.ч. после блокировки экрана смартфона), при открытии overlay-экранов (Настройки, Безопасность, Уведомления, Конфликты, Запросы на детализацию), и после каждой CRUD-операции (хук в `SyncWorker.enqueue`, который зовётся из всех Repository.create/update/delete). Свайпы между основными вкладками probe не триггерят (слишком частая нагрузка).

### [1.28.1] — 2026-05-11

#### Fixed
- В офлайн-режиме регулярные расходы корректно подсвечиваются как «оплачено» (зачёркнутая строка + бейдж + «след. оплата»). Раньше синтез ставил `paid_this_period=false` для всех — флаг хранится в транзакциях, а не в самом wishlist-итеме, и я его не считывал. Теперь `synthesizeRegularItems` смотрит локальный Room и группирует транзакции по `wishlist_id` в текущем календарном периоде (мирорит backend bucket-логику) — `paid_amount`, `paid_count` и `next_due_date` тоже заполняются.

### [1.28.0] — 2026-05-11

#### Fixed
- **Регулярные расходы доступны в офлайн-режиме.** Раньше при недоступном `forecast`-эндпоинте секция целиком пропадала; теперь синтезируем минимальный `RegularItem` из локального Room-кэша wishlist'а — пользователь может редактировать / удалять / создавать связанные транзакции без сети. `paid_this_period` / `next_due_date` / `paid_amount` остаются дефолтными до следующего pull'а forecast'а.
- В детальной информации регулярного расхода поле «Статус» больше не показывает бессмысленное «Не куплено». Для recurring отображается «Оплачено ✓» / «Не оплачено» (по `paid_this_period` из forecast'а) + новая строка «Следующая оплата» с датой. Для wishlist (one-off) — как было («Куплено / Не куплено»). Для recurring без forecast-контекста (например, открыто из transaction back-link) строка статуса скрывается.
- «Ежемес. вклад» в детальном просмотре теперь показывается для quarterly/yearly независимо от purchased-флага (это справочное поле, не зависит от состояния).

#### Changed
- **Bulk-«Куплено» в списке желаний убран** — фиксация покупки теперь требует bottom-sheet'а, который не имеет смысла для пакетной выборки. Bulk-«Не куплено» оставлен (показывается только когда все выбранные `purchased=true`); как и одиночный «Не куплено», bulk дополнительно отвязывает все linked транзакции через `unlink-period`.
- **Анимация переключения вкладок в `AddWishlistSheet`.** Заголовок кросс-фейдится через `AnimatedContent`, селектор частоты раскрывается / схлопывается через `AnimatedVisibility(expandVertically + fadeIn)` — раньше форма дёргалась при смене типа.

### [1.27.1] — 2026-05-08

#### Changed
- Заголовок bottom-sheet'а префилл-формы расхода в Прогнозе адаптируется к контексту: «Фиксация оплаты» для регулярного расхода, «Фиксация покупки» для wishlist-итема. Раньше всегда было «Создать по шаблону». Дефолтное поведение `AddExpenseSheet` (вне Прогноза) не меняется — `title` опциональный override.

### [1.27.0] — 2026-05-08

#### Changed
- **«Куплено» в wishlist теперь работает как «Оплачено» в регулярных:** свайп по кнопке открывает префилл-`AddExpenseSheet`. На сохранении создаётся expense с `wishlist_id` + флипается `purchased=true`.
- **«Отменить»** на купленном wishlist-итеме зовёт `unlink-period` (для once бэкенд очищает единственную привязку, api ≥ 1.12.1) и сбрасывает `purchased=false`. Сама транзакция остаётся в Расходах.
- В `TransactionDetailSheet` back-link для tx с `wishlist_id`, ссылающимся на `frequency=once` итем, теперь подписан **«Желаемая покупка»** (раньше всегда «Регулярный расход»). Лейбл прокидывается через новый параметр `linkedWishlistLabel`.

#### Removed
- `ForecastViewModel.togglePurchased(id, currentPurchased)` — заменён на пару `purchaseWishlist(req)` / `unpurchaseWishlist(id)`. Bulk-purchase для wishlist остаётся simple toggle (массовая фиксация форм была бы UX-кошмаром).

### [1.26.0] — 2026-05-08

#### Added
- Поле «Заметки» возвращено в `AddWishlistSheet` (применимо к обеим вкладкам — желаемой покупке и регулярному расходу).
- Тап по карточке регулярного расхода открывает `WishlistInteractiveSheet` для просмотра и редактирования (как у wishlist-итемов).
- Long-press на регулярном расходе включает multi-select со своим bulk-FAB-rовом: «Отменить» (видно если ≥1 из выбранных оплачен в текущем периоде) + «Удалить». Wishlist-выделение и регулярное-выделение взаимоисключающие — переключение чистит другую секцию.
- В `TransactionDetailSheet` появилась back-link строка «Регулярный расход → {название}» — открывает inline `WishlistInteractiveSheet` для просмотра/редактирования источника. Заведено для транзакций с непустым `wishlist_id`.

#### Changed
- В форме редактирования wishlist-итема убран `Switch` «Куплено» — статус ставится через swipe-action на карточке. Сохранение больше не передаёт `purchased`.

### [1.25.0] — 2026-05-08

#### Added
- На прогнозе появилась четвёртая summary-карточка: 2×2 grid из «Прогноз / мес», «Ср. за 3 мес», «Регулярные / мес», «Желания / мес». «Регулярные» = `regular_contrib`, «Желания» = `wishlist_contrib − regular_contrib` (api ≥ 1.12.0).
- Two-stage подтверждение для свайп-действия «Отменить» на регулярных расходах: первый тап вооружает («Подтвердить?»), второй тап коммитит (как уже было у «Удалить»).

#### Fixed
- Префилл формы расхода при «Оплачено» теперь точно копирует данные wishlist-итема: `name → «Назначение»` (purpose), `notes → «Описание»` (description). Раньше `name` попадал в Описание, а Назначение оставалось пустым.
- `RegularItem` DTO дополнен полем `notes` (api ≥ 1.12.0) — нужно для префилла «Описания».

### [1.24.0] — 2026-05-08

#### Changed
- **Прогноз: разделены «Список желаний» и «Регулярные расходы».** Wishlist теперь содержит только `frequency='once'` итемы; регулярные подняты на уровень `LazyColumn` items с заголовком «Регулярные расходы» снаружи карточек.
- **Регулярный итем — отдельная свайп-карточка** (как wishlist): свайп вправо открывает «Оплачено» (зелёный), свайп влево открывает «Отменить» (серый, виден только когда `paid_this_period`) + «Удалить» (красный, с подтверждением через AnimatedContent).
- Сумма регулярного отображается с per-period суффиксом: monthly → `₽/мес`, quarterly → `₽/кв`, yearly → `₽/год`.
- При `paid_this_period` в строке регулярного появляется «след. оплата: DD.MM.YYYY» из нового поля `next_due_date`.
- `AddWishlistSheet` сверху получил пару `FilterChip` для выбора типа: **Желаемая покупка** / **Регулярный расход** (визуально mirror'ит StatisticsScreen period chips). Селектор частоты показывается только в `regular`-ветке и предлагает только monthly/quarterly/yearly.
- Удалён toggle «Куплено» из формы добавления — статус ставится позже через swipe-action на карточке wishlist.
- `RegularItem` DTO дополнен полем `next_due_date` (api ≥ 1.11.0).

### [1.23.0] — 2026-05-08

#### Added
- В прогнозе появилась секция «Регулярные расходы» — карточки `regular_items` с двусторонним свайпом:
  - **Свайп вправо → «Оплачено»** (зелёный): открывает форму добавления расхода с предзаполненными суммой (по `estimated_cost`), категорией и описанием. На сохранении транзакция сохраняется в Room с `wishlist_id` итема и уходит в sync; прогноз перезагружается, строка становится зачёркнутой с бейджем «оплачено · 800 ₽».
  - **Свайп влево → «Отменить»** (серый, виден только когда `paid_this_period`): дёргает `POST /api/wishlist/:id/unlink-period`; следующий `sync pull` снимет привязки локально.
- Категории расходов в `ForecastViewModel` (`expenseCategories` / `addExpenseCategory` / `deleteExpenseCategory`) — нужны для предзаполненной формы.
- Поле `wishlistId` у `Transaction` модели и `wishlist_id` колонка у `transactions` Room-таблицы (миграция v2→v3, `DEFAULT ''`).
- `ApiService.unlinkWishlistPeriod()` (POST /api/wishlist/:id/unlink-period).

#### Changed
- `RegularItem` DTO расширен полями `estimated_cost` / `paid_this_period` / `paid_amount` / `paid_count` (mirror новой схемы api 1.10.0).
- `TransactionRepository.create()` принимает `wishlistId: String = ""` для linked-fulfillment'ов; дефолтное поведение остальных вызовов не меняется.

### [1.22.3] — 2026-05-08

#### Fixed
- `SummaryCard` (Statistics — Доходы/Расходы/Баланс): карточки заметно вырастали по высоте в момент скрытия сумм, а сам текст суммы дополнительно «подпрыгивал» вверх в начале фейда и «спускался» обратно при показе. Crossfade переключал между Text-row (~22dp) и 26dp placeholder'ом, разница пропихивалась наружу и одновременно сдвигала контент внутри Crossfade'а на 2dp. Обоим child'ам выдан явный `Modifier.height(26.dp)` — outer Box больше не ресайзится между состояниями, текст центрируется по `CenterVertically` ровно в позицию placeholder'а.
- ChartLegend (pie chart amounts): значения сумм при скрытии/показе на мгновение прижимались к левому краю и потом возвращались. Crossfade с дефолтным TopStart-выравниванием заменён на `AnimatedContent(contentAlignment = CenterEnd)` — placeholder и Text оба анкорятся на правый край колонки.
- `TransactionDetailSheet` back-link для child-транзакции (Запрос на детализацию · Открыть запрос): метка и значение приведены к стилю `DetailRow` — `bodyMedium` шрифт + два weighted-столбца (0.4f/0.6f), значения выравниваются по левому краю своей колонки. Раньше label был `bodySmall`, а ссылка завёрнута в `TextButton` с padding'ом, что сбивало baseline.

### [1.22.2] — 2026-05-08

#### Fixed
- `TilePeriodPickerPopup` на `StatisticsScreen` всё равно прилипал к триггеру: вызовы передавали жёстко закодированный `anchorOffset = IntOffset(0, 110)`, перекрывая dp-аккуратный default из 1.22.1. Все три call-site'а очищены — теперь работает `38.dp + 8.dp gap` от триггера.
- Counter-бейдж в TopAppBar (открытые ЗнД) обрезался верхним краем app-bar'а: `BadgedBox` располагает значок снаружи measured-bounds иконки, и TopAppBar клиппил overflow вверх. Заменено на ручную `Box` с `align(Alignment.TopEnd) + offset(-4dp, 6dp)` — бейдж сидит внутри 48dp action-slot'а.
- `ExpensesScreen`: жёлтая (pinned) карточка не показывалась сразу после открытия экрана, появлялась только после ручного скролла. LazyColumn якорится на первой видимой записи: когда ЗнД-store догружается после транзакций, новые pinned-строки вставляются в head, но scroll остаётся на старом anchor'е, из-за чего pinned-ряды оказываются выше viewport. Добавлен `LaunchedEffect(myOpenParentIds.size)` с `animateScrollToItem(0)` на переход 0 → N pinned'ов.

#### Added
- Подтверждение удаления для child-расходов в `DetailRequestScreen`: `AlertDialog` с «Удалить» / «Отмена» вместо мгновенного удаления по нажатию ✕.

### [1.22.1] — 2026-05-08

#### Fixed
- `BadgedBox` в TopAppBar обрезался при `AnimatedVisibility(expandHorizontally)` — добавлено `clip = false` на enter/exit, бейдж счётчика больше не съедается клипом во время анимации.
- TopAppBar: `currentPage` сменён на `pagerState.targetPage` для тайтла и условных кнопок (фильтр, ЗнД-бейдж). При `animateScrollToPage(...)` от тапа в bottom-nav название раздела сразу прыгает к финальной странице, а не пробегает по всем промежуточным.
- `SwipeableTransactionCard`: переключение скрытия сумм переведено с `Crossfade` (TopStart-выравнивание) на `AnimatedContent(contentAlignment = CenterEnd)` — placeholder и Text больше не «съезжают» друг относительно друга во время фейда.
- Карандаш-кнопка в `TransactionDetailSheet` теперь окрашивается в `primaryColor` (как в Forecast/wishlist), а не в цвет суммы.

#### Added
- Свайп между табами Открытые/Закрытые в `DetailRequestsScreen`: `HorizontalPager` + `PrimaryTabRow` (M3) с автоматически анимированным индикатором.
- Анимация появления `TilePeriodPickerPopup` на Statistics: `MutableTransitionState` + `AnimatedVisibility(fadeIn + slideInVertically + scaleIn)` с обратным `fadeOut + scaleOut`. Отступ от триггера переведён в dp (`anchorGap = 8.dp`, базовый offset считается от 38dp-кнопки).

### [1.22.0] — 2026-05-08

#### Added
- Запросы на детализацию (поверх API 1.9.0):
  - `DetailRequestStore` (singleton, online-only `StateFlow`); API в `ApiService` (`listDetailRequests`/`createDetailRequest`/`getDetailRequest`/`addDetailRequestChild`/`closeDetailRequest`/`cancelDetailRequest`).
  - `DetailRequestsScreen` — список запросов с табами Open/Closed (когда `showAll=true`, открывается из настроек) или только Open (из header-бейджа).
  - `DetailRequestScreen` — карточка прогресса (target/total/count + предупреждение overshoot или остаток в баланс), мета-карточка с родительской транзакцией и assignee, FAB «+» для добавления child, кнопки «Готово»/«Отменить».
  - В `MainScreen` TopAppBar — `BadgedBox` с иконкой `Assignment` (только на экране «Расходы», только при `myOpenDrCount > 0`) с плавной `AnimatedVisibility` (fadeIn+expandHorizontally) при свайпе между экранами.
  - В `ExpensesScreen` — жёлтая подсветка карточек (`highlightWarning` на `SwipeableTransactionCard`), сортировка моих open-родителей наверх, кнопка «⇲» в `TransactionDetailSheet` (открыть/создать) рядом с «Редактировать».
  - В `TransactionDetailSheet` для child-записи (`parentId != ""`) — back-link «Запрос на детализацию · Открыть запрос» с переходом в DetailRequestScreen.
  - В `SettingsDialog` — иконка `Assignment` рядом с logout-иконкой в верхнем user-row для входа в полный список запросов.
  - Чекбокс «Показать закрытые запросы» в filter card — проброшен в `TransactionDao.observeFiltered` (`(:includeDetailed = 1 OR detail_request_status != 'closed')`).
- Кнопка фильтра в TopAppBar теперь анимируется (fadeIn+expandHorizontally) при переходе на/с Income/Expenses — раньше появлялась мгновенно.

#### Changed
- **Room v2** (миграция MIGRATION_1_2): `parent_id`, `detail_request_id`, `detail_request_status`, `excluded_from_stats` на `transactions` (default `''`/`0`).
- `TransactionDao.observeAll` / `observeFiltered` скрывают pending children (`parent_id != '' AND excluded_from_stats=1`); закрытые-родительские прячутся опционально через `includeDetailed`.
- `AssigneePickerDialog` рендерит `UserAvatar` в `leadingContent` — единый стиль с диалогом «Изменить автора».
- Cross-app синхронизация online-only состояния: на `Lifecycle.ON_RESUME` дёргается `DetailRequestStore.refresh()` и `checkVersion(...)` — бейдж и баннер обновления подтягиваются без рестарта приложения. Pull-to-refresh в `ExpensesScreen` тоже зовёт `DetailRequestStore.refresh()`.

#### Fixed
- BackHandler на `DetailRequestsScreen` и `DetailRequestScreen` возвращает на предыдущий экран (раньше системная «Назад» сворачивала приложение).
- Фильтр-карточка в `ExpensesScreen` получила `zIndex(1f)` и непрозрачный фон — исчезающий ряд при переключении `includeDetailed` больше не пробивается под Card.
- Чекбокс «Показать закрытые запросы» переведён в один кликабельный Row с `indication = null` — серое нажатие на текст убрано; `animateScrollToItem(0)` при флипе фильтра, чтобы новые сверху-всплывающие записи показывались сами.



### [1.21.0] — 2026-05-07

#### Changed
- **Редизайн логотипа.** Буквы «mb» получили скульптурные тени-градиенты под каждым горбом «m» и боулом «b» (linear black-alpha → transparent), создающие эффект 3D-глубины. Подчёркивание под «b» отделено в нейтральный серый (`#CED4E1`) — больше не наследует тон букв. Геометрия пересобрана из новой Inkscape-исходной SVG (Android Studio Vector Asset Tool, transforms baked); межбуквенный интервал сделан плотнее.
- **Двухслойный `MbLogo()`.** Векторная графика разделена на тинтуемый базовый слой (`mb_logo.xml`, белые буквы) и нетинтуемый overlay (`mb_logo_overlay.xml`, тени + серое подчёркивание). Composable стэкает их через `Box`: `ColorFilter.tint(primaryColor)` применяется только к базе, поэтому динамическая палитра пользователя продолжает красить буквы, а градиент-тени и подчёркивание выживают неизменными.
- **Launcher / splash icon.** `ic_launcher_foreground.xml` пересобран в новой геометрии с дефолтным брэнд-синим `#2080F0`, тенями-градиентами и серым подчёркиванием.
- **Notification small icon.** `ic_notification.xml` обновлён до новой геометрии. Тени-градиенты намеренно не перенесены: после системного monochrome-тинта они превратились бы в полупрозрачные полосы. Остаётся alpha-only по гайдлайнам Android (буквы 1.0, подчёркивание 0.45).

### [1.20.3] — 2026-05-07

#### Fixed
- **Доходы / Расходы: верхний отступ первой карточки.** Между AppBar и первой карточкой (баланс / фильтр / список) был отступ ≈4–6 dp — заметно меньше, чем 6 dp между соседними карточками в списке. Добавлен `padding(top = 6.dp)` на внешний `Column`, благодаря чему первая карточка теперь отстоит от AppBar на 12 dp независимо от того, какая карточка отображается первой (IB-карточка, фильтр или сам `LazyColumn`).
- **Иконка фильтра в TopAppBar.** Раньше использовалась `Icons.Default.FilterAlt` — у неё плотный заполненный глиф, поэтому в светлой теме она выглядела темнее, а в тёмной — светлее соседних `Visibility` / `Settings`. Заменено на `Icons.Outlined.FilterAlt` / `Icons.Outlined.FilterAltOff` (одинаковая «outline»-стилистика); тинт неактивного состояния теперь идёт через `LocalContentColor.current`, чтобы точно совпадать с дефолтным цветом соседних `IconButton`.

### [1.20.2] — 2026-05-07

#### Changed
- **Статистика: карточка периода во всю ширину.** `Card` периода вокруг чипов теперь `fillMaxWidth()` — раньше она зажималась по контенту и оставляла справа неиспользуемое поле.
- **Доходы / Расходы: фильтры свёрнуты по умолчанию + кнопка-«фильтр» в TopAppBar.** Добавлена иконка `FilterAlt` (только на этих двух маршрутах) — тап разворачивает/сворачивает Card с фильтрами через `expandVertically + fadeIn` / `shrinkVertically + fadeOut`. В развёрнутом состоянии иконка переключается на `FilterAltOff` и подсвечивается primary-цветом. Состояние общее для обоих экранов, чтобы при переходе с Доходов на Расходы фильтры оставались в том же положении.

### [1.20.1] — 2026-05-07

#### Changed
- **Статистика: чип сам становится триггером выбора.** Раньше под рядом чипов «Месяц / Год / Период» висело отдельное поле — на экране это выглядело как два уровня контролов. Теперь активный чип отображает выбранное значение (например, «Май 2026» / «2026» / «01.05 — 31.05»), а тап по любому чипу одновременно переключает режим и открывает соответствующий picker. `TilePeriodPickerPopup` и `DateRangePickerDialog` вынесены в controlled-варианты (открываются по внешнему `open: Boolean`), чтобы триггером можно было сделать любой компонент.
- **Доходы / Расходы: фильтры объединены в одну карточку.** Период и категория теперь живут внутри `Card` с тем же стилем, что и карточка начального баланса — две OutlinedTextField-формы один под другим, «Всего: N» в конце строки категории. `DateRangePickerField` перерисован как `OutlinedTextField` с лейблом «Период» вместо отдельного chip-Surface, чтобы не выбиваться визуально из соседнего фильтра категорий.

### [1.20.0] — 2026-05-07

#### Added
- **Фильтр по периоду** на экранах «Статистика», «Доходы», «Расходы». Новый компонент `TilePeriodPicker` — read-only поле, открывающее popup с tile-сеткой 4×3 (как `TilePeriodPicker.vue` на вебе): для типа `MONTH` — 12 месяцев года, для `YEAR` — десятилетие из 12 лет; стрелки в шапке листают на ±1 год / ±10 лет. На «Статистике» добавлен режим `Период` — рядом со старыми «Месяц/Год» — открывающий тематизированный Material 3 `DateRangePicker` с выбором «С — ПО».
- **DateRangePickerField** — read-only chip, при тапе открывает Material 3 `DateRangePicker` диалог с темой primary-цвета (включая `dayInSelectionRangeContainerColor`). Используется на «Доходах» и «Расходах» для фильтра записей по диапазону дат, аналогично `n-date-picker daterange` на вебе.

#### Changed
- `StatisticsViewModel`: enum `StatsPeriod` расширен значением `RANGE`; навигация ‹ / › в шапке заменена на tile-picker (`MONTH`/`YEAR`) либо `DateRangePickerField` (`RANGE`). API клиента `getStatisticsOverview` принимает `from`/`to` (бэкенд уже их парсит через `parsePeriodParams`).
- `IncomeViewModel` / `ExpensesViewModel`: `uiState` слушает дополнительно `_filterFrom` / `_filterTo`; `TransactionRepository.observeFiltered` получает диапазон, фильтрация выполняется Room-запросом (`date >= :from AND date <= :to`).

### [1.19.0] — 2026-05-06

#### Added
- **Анимации переходов между экранами.** `MainActivity` пропускает корневой state-machine (splash → connect → pin setup / lock → main) через `AnimatedContent` с fade + scale. Notifications/Security/Conflicts открываются slide-in справа и закрываются slide-out обратно. ConnectScreen пере­ключает шаг 1 ↔ 2 и `PinSetupScreen` шаг 1 ↔ 2 через slide+fade `AnimatedContent`.
- **PIN: анимированные ячейки + loader.** Новая `AnimatedPinDotsRow` (общая для `LockScreen` и `PinSetupScreen`) масштабирует точку 0→1 с пружинистым `spring` при вводе и `animateColorAsState` для смены цвета. После ввода полного PIN-кода точки на ~0.5–0.7 с уходят в волновую анимацию (`infiniteRepeatable` + `triangular waveOffset`) — даёт ощущение «проверяю…» вместо мгновенного срабатывания.
- **Прогрессивная отрисовка графиков.** `DonutChart` и `BarChart` хранят `Animatable(0f→1f)`; на смену периода/данных запускается `tween(700ms)` / `tween(600ms)` `FastOutSlowInEasing` — секторы пирога заметаются от 12 часов, столбцы прорастают от базовой линии.
- **Сглаженная смена сумм.** `AnimatedAmountText` (новый хелпер в `Common.kt`) интерполирует число между двумя значениями за 360 ms — используется в `SummaryCard` (Доходы/Расходы/Баланс на статистике) и в карточке начального баланса на экране доходов. `SummaryCard.hidden` теперь идёт через `Crossfade` (220 ms) между плейсхолдером и значением; та же логика применена к подписи слайса в `ChartLegend`.

#### Changed
- **Bulk-mode FAB swap остаётся snap'ом** (без внешней анимации), но иконки внутри ряда («Скрыть/Показать выбранные», «Куплено/Не куплено») cross-fade'ятся. Пробовали `AnimatedContent` со `scaleIn/Out` и чистый sequenced-fade — оба варианта на release-сборке давали резкий прямоугольный силуэт тени FAB во время анимации (артефакт растеризации Compose-слоя), поэтому оставлено мгновенно.
- **Свайп-карточки: цвет фона анимируется при «скрыто/куплено».** Возвращён `animateColorAsState` для ветки `transaction.hidden` / `item.purchased` — флаги меняются при пользовательском действии, не на каждом фрейме скролла, так что прежняя perf-просадка не воспроизводится. Branch `selected` остаётся snap (поверх него уже cross-fade'ит `SelectionOverlay`).
- **Удалить → Подтвердить?** На свайп-фоне `SwipeableTransactionCard` и `SwipeableWishlistCard` подпись прыгает через `AnimatedContent` (fade + 0.85f scale, ~150 ms) вместо мгновенной замены текста. Иконка-переключатель «Скрыть/Показать выбранные» / «Куплено/Не куплено» в FAB-ряде кроссфейдится по тем же правилам.
- **Скрытие/показ сумм глобально через Crossfade.** Каждая сумма в карточке транзакции, в строке начального баланса и в `ChartLegend` оборачивается в `Crossfade` (220 ms) между плейсхолдер-Box'ом и текстом, чтобы кнопка глаза в TopAppBar давала ровный fade, а не мигание.
- **TransactionDetailSheet и WishlistInteractiveSheet: view ↔ edit.** Внутри `ModalBottomSheet` режимы «просмотр» и «редактирование» переключаются через `AnimatedContent` с fade + 0.97f scale (220/160 ms) вместо моментального свопа.

#### Fixed
- `DiscoverySection` на `ConnectScreen`: подпись «Поиск не дал результатов — повторить» переносилась на вторую строку и обрезалась нижней границей кнопки (фиксированный `height(44.dp)`). Текст укорочён до «Ничего не найдено — повторить», добавлены `maxLines = 1` + `TextOverflow.Ellipsis`, фиксированная высота заменена на `heightIn(min = 44.dp)` — кнопка может вырасти на одну строку, если будущий лейбл всё-таки не влезет, вместо клипа.

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
- 2026-05-12: `tools/aggregate-reports.py` — общий HTML-отчёт по линтерам и тестам через все три компонента (api/web/android). `make lint` и `make test` теперь вызывают скрипт, который запускает golangci-lint+prettier+eslint+ktlint+detekt и `go test -json`+vitest+gradle, парсит структурный выхлоп (golangci-lint JSON, ESLint JSON, checkstyle XML от ktlint/detekt, JUnit XML от vitest/android, `coverage.out`/`coverage-summary.json`/jacoco XML) и складывает в `reports/{lint,test}.html` + сырые артефакты в `reports/raw/`. Test-отчёт показывает passed/failed/skipped, длительность, coverage % с прогресс-баром, упавшие и 10 самых медленных, coverage по пакетам. Lint-отчёт — счётчик по правилам и до 300 проблем подробно. `reports/` добавлен в `.gitignore`.
