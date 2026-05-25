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

### [1.26.0] — 2026-05-26

#### Added
- **Разделение дохода по депозитам.** `POST /api/transactions/:id/split` атомарно создаёт N (≥2) дочерних транзакций c заданными `amount`+`deposit` (bank/cash), наследующих категорию/дату/источник/описание у parent'а; parent помечается `excluded_from_stats=true` и пропадает из стандартных списков и агрегаций. Сумма частей должна точно равняться сумме parent'а (допуск 0.01 ₽ на округление). `POST /api/transactions/:id/unsplit` soft-удаляет всех детей и снимает с parent'а `excluded_from_stats` — после этого его можно разделить заново.
- **Filter `include_split=true` для `GET /api/transactions`.** По умолчанию (`false`) скрывает split-parent income-транзакции; включение — для UI-чекбокса «Показать разделённые». DR-логика расходов не затронута (фильтр `$nor` отдельно проверяет `type=income` + пустой `detail_request_status`).
- **`TransactionFilter.IncludeSplit`.** Параллель к существующему `IncludeDetailed`.

#### Tests
- `TestTransactionSplit_FullFlow` — happy path (split → parent hidden / `include_split=true` returns it / stats считают только детей / double-split → 409 / unsplit → восстановление / re-split после unsplit).
- `TestTransactionSplit_Validation` — sum mismatch / `min=2` / non-income → 400; unsplit на never-split → 409.

### [1.25.1] — 2026-05-25

#### Fixed
- **Forecast NPE на Android при пустом scope.** `Forecast()` собирал `breakdown` через `for cat, amount := range catMap` — на пустом scope (например `?deposit=cash` без cash-tx) переменная оставалась `nil`, Gin сериализовывал её как `null`, а Gson на Android при reflection-десериализации пихал `null` в non-nullable `List<CategoryStat>` поле модели, в результате `breakdown.isNotEmpty()` крашил приложение. Force-инициализация пустой slice (`[]models.CategoryData{}`) перед return'ом — теперь по проводам всегда `[]`. (Та же защита уже была на `regular_items` и `unpurchased_wishlist`.)

### [1.25.0] — 2026-05-24

#### Added
- **Deposit scope (банковская карта / наличные).** Новое поле `deposit` на `Transaction` и `WishlistItem` со значениями `bank`/`cash` (default — `bank`). Фильтр `?deposit=bank|cash` принимают `GET /api/transactions`, `/statistics/{summary,by-category,monthly,overview,forecast}`, `/export/{excel,pdf}`. Отсутствующий параметр = «оба скоупа». На уровне repository добавлено поле `TransactionFilter.Deposit`; все агрегации (`AggregateByCategory`, `AggregateMonthlyRange`, `GetSummary`, `GetAverageMonthlyCategoryExpenses*`, `FindAll`) получили новый параметр `deposit string` (пустая строка = без фильтра). `RegularItemForecast.Deposit` отдаётся клиентам, чтобы в прогнозе можно было показать значок по карточке без второго запроса. DR-children наследуют `deposit` у parent'а (`POST /api/detail-requests/:id/transactions`). `POST /api/wishlist/:id/link/:tx_id` приводит deposit транзакции к deposit'у wishlist-итема — чтобы paid-amount по scope оставался консистентным. Лимиты расходов **пока** считаются по обоим scope'ам (раздельные лимиты — отдельная задача).
- **Backfill миграция.** `cmd/migrate` дополнен шагом `backfillDeposit`: всем существующим `transactions`/`wishlist` без поля `deposit` (или с пустым) проставляется `bank`. Идемпотентно, повторные запуски безопасны.
- **Helper `models.NormalizeDeposit(d)`.** Любые пустые/неизвестные значения клиентов схлопываются в `bank` (вызывается в `Create`/`Upsert` обоих repository, в `*Handler.Create/Update`, в `sync.decode*Payload`).
- **Тесты.** `TestTransactionRepo_DepositFilter` (bank/cash/без-фильтра + empty→bank normalization) и `TestNormalizeDeposit_Defaults`.

### [1.24.1] — 2026-05-20

#### Added
- **Tests.** Покрытие `middleware/cors.go` (CORS-заголовки, OPTIONS-preflight → 204), `middleware/admin.go` (no-claims → 401, не-admin → 403, неправильный type-cast → 403, admin → passes), нотификаций (`Read` per-id, идемпотентность, `?limit=`, 401 на unauthenticated), repo-уровневые тесты для `transaction_repo.FindAll` (date/type filter), `GetAverageMonthlyCategoryExpenses` (3-месячное окно), `category_repo.FindByID` + `FindModifiedSince`, `user_repo.BackfillUserInfo` (cross-collection update + clear-avatar `$unset`) и `SoftDelete` (идемпотентность + `deleted_at` фактически записывается).

#### Changed
- **Coverage-scope (`make test-backend-cover`).** `-coverpkg` теперь ограничен `backend/{config,handlers,middleware,models,repository}` — исключены `cmd/*` (CLI-утилиты), `main.go` (wiring), `internal/mongotest` (тестовые хелперы), `handlers/export.go` (Excel/PDF — e2e-территория), `handlers/icons.go` (multipart upload — e2e-территория). См. `docs/E2E_PLAN.md`. Coverage поднялся с 45.3% → **65.7%** на тех же тестах, и метрика стала отражать «бизнес-логику HTTP API», а не общий объём кода.

### [1.24.0] — 2026-05-19

#### Added
- **Auto-backfill UserInfo-снимков** при правке профиля. Загрузка/удаление аватара (`POST|DELETE /api/admin/users/:id/avatar`) и переименование `display_name` (`PATCH /api/admin/users/:id`) теперь синхронизируют денормализованные `created_by`/`last_modified_by`/`creator`/`assignee`/`uploaded_by` во всех 5 коллекциях (`transactions`, `wishlist`, `categories`, `detail_requests`, `category_icons`). До этого snapshot фиксировался при создании записи и не обновлялся, поэтому если юзер поставил аватар постфактум, старые транзакции продолжали возвращать пустой `avatar_url` — UI рисовал инициалы. Теперь чтобы догнать существующие записи достаточно один раз перезагрузить тот же файл аватара (новый `?v=<ts>` триггерит backfill). Отдельный CLI-скрипт миграции не нужен.
- `repository.BackfillUserInfo(ctx, db, userID, displayName, avatarURL)` — кросс-коллекционный `UpdateMany`. `avatarURL == ""` → `$unset` на slot.avatar_url (mirror `User.AvatarURL` с `omitempty`). На sync-pull коллекциях (`transactions`/`wishlist`/`categories`) дополнительно бампается `updated_at`, чтобы Android-клиент подхватил изменения на следующем pull.

#### Notes
- Без Mongo-транзакций: prod на standalone `mongo:4.4.18` (replica-set не поднят), multi-document транзакции недоступны. Падение посередине backfill оставит часть snapshot'ов стейлыми; следующий аналогичный вызов докатит остальное. Для семейного приложения с единичными правками профиля — приемлемо.

### [1.23.0] — 2026-05-19

#### Added
- **First-run setup wizard.** Публичные эндпоинты `GET /api/setup/status` и `POST /api/setup/init` для интерактивной настройки на чистой базе. `Status` возвращает `needs_setup: true`, когда `users.count == 0`. `Init` создаёт первого админа (login/password/display_name) — доступен только пока в БД нет пользователей; идентичен `/auth/login` по выдаче (access+refresh JWT + LoginResponse). Заменяет необходимость вручную вызывать `cmd/create_user` после первого деплоя; CLI остаётся для headless-сценариев.
- **JSON импорт/экспорт всей системы (admin).** `GET /api/admin/export` возвращает self-contained снимок (`schema_version=1`): users с bcrypt-хешами, categories, category_icons (data в base64), transactions (parents-first ordering для корректной вставки detail-request child'ов), wishlist, detail_requests. История уведомлений / refresh-токены / sync-метаданные не экспортируются (derived state). `POST /api/admin/import` принимает `{mode, snapshot}`: `merge` (по умолчанию) пропускает существующие записи по `_id` (и по `login` для пользователей), `replace` очищает коллекции (категории/иконки/транзакции/wishlist/detail_requests/notifications + всех пользователей кроме вызывающего админа) перед импортом. Для тех же UUID-коллизий импорт скипает запись. `updated_at` всех импортированных записей бампается на `now()` — Android-клиенты подхватят их на следующем `sync/pull`. Лимит payload'а — 50 MiB.
- **`UserRepository.CountAll`** — счётчик не-удалённых пользователей, используется setup-хендлером.

#### Notes
- Формат экспорта — не замена бэкапа Mongo. Не сохраняет историю уведомлений, метаданные синхронизации, refresh-токены. Резервное копирование БД остаётся отдельной задачей.

### [1.22.0] — 2026-05-19

#### Added
- **`create_user` CLI в prod-образе.** `backend/Dockerfile.prod` теперь собирает второй бинарь `./cmd/create_user` и копирует его в `/app/create_user`. Distroless-образ не имеет шелла, поэтому первичная инициализация на prod/RPi разворотах требует прямого вызова бинаря через `docker compose exec`. До этого CLI существовал только как `go run ./cmd/create_user` в Makefile и был недоступен на хостах без Go (RPi). Документация в `docs/RPI_DEPLOY.md` ранее ссылалась на несуществующий флаг `-create-user` у серверного бинаря — исправлено: правильный вызов `/app/create_user -login … -password … -name …`, поддерживаются `-avatar` и `-admin`.
- Тот же бинарь добавлен в dev-`backend/Dockerfile` для консистентности с prod.

### [1.21.0] — 2026-05-19

#### Added
- **`POST /api/wishlist/:id/link/:tx_id`** — привязка существующего расхода к wishlist/regular-итему. Бэкенд для UI-кнопки «Привязать к существующему» в Прогнозе. Валидирует, что транзакция expense, не soft-deleted, ещё не связана и не часть закрытого detail-request. Категория транзакции приводится к категории wishlist-итема (чтобы pie-слайс соответствовал тому, что получает «Куплено»/«Оплачено»). Если такой категории нет в expense-секции — она клонируется с цветом и иконкой из wishlist-секции через `CategoryRepository.EnsureInSection`. Для `once`-итемов также выставляется `purchased=true`.
- **`GET /api/transactions?unlinked=true`** — список кандидатов на привязку: только expense, без `wishlist_id`, без `parent_id`, без открытого/закрытого detail-request, без `excluded_from_stats`. Игнорирует параметр `type=` (фильтр сам подставляет expense).

#### Tests
- `TestWishlist_LinkExisting` — happy-path привязка с clone категории; once-итем флипается в purchased; повторная привязка → 409; income → 400; missing tx → 404.
- `TestTransactions_UnlinkedFilter` — eligible expense возвращается, привязанный/income/closed-DR-parent отфильтровываются.

### [1.20.0] — 2026-05-15

#### Added
- **Лимиты расходов на категории (Phase 5).** `Category.monthly_limit *float64` — опциональный месячный лимит на expense-категорию (income/wishlist игнорируют). `UpdateCategoryRequest.MonthlyLimit` использует кастомный `NullableFloat` тип, чтобы JSON-tri-state (`absent` / `null` / `number`) был различим: ключ отсутствует — поле не трогается, `null` — лимит снимается через `$unset`, число — устанавливается через `$set`. Стандартный Go `*float64` / `**float64` не различает absent от null в `encoding/json`.
- **`GET /api/categories/limits-progress?month=YYYY-MM`** — возвращает по каждой expense-категории с заданным лимитом: `spent`, `limit`, `percent`, плюс `total_limit` + `total_spent` + `total_percent` (суммируются только по категориям с лимитом — категории без лимита не «разбавляют» отношение). По умолчанию — текущий календарный месяц (с 1-го по последний день, UTC).
- **Notification subsystem** — новая коллекция `notifications`, family-wide события с per-user read-state.
  - Модель `Notification`: `type` (`category_limit_exceeded` | `global_limit_exceeded`), `period` (YYYY-MM), `category_id` (для категорийных), snapshot `limit` / `spent` / `category_name`, `read_by []string` (массив user_id).
  - Unique-индекс `(type, period, category_id)` обеспечивает дедуп: один алерт на категорию на месяц (MVP-правило «(a)»). Global-нотификация сидит в этом же индексе с `category_id=""`.
  - `GET /api/notifications` — список (newest-first, default limit=50) с `read: bool` на каждой записи (per-user view) + `unread_count`.
  - `POST /api/notifications/read-all` — `$addToSet user_id` во все записи. Идемпотентно.
  - `POST /api/notifications/:id/read` — пометить одну (фронт пока не использует, оставлено на будущее).
- **`LimitChecker`** — фоновый триггер, дёргается из `TransactionHandler` после Create/Update/Delete расходных транзакций (income/initial_balance не трогаются). Запускается через `go ...` с собственным 5-секундным контекстом, чтобы request-context закрытие не оборвало проверку. Считает текущий месяц по `Category` категории с `monthly_limit` + общий лимит как сумму всех заданных лимитов; вызывает `NotificationRepository.EnsureExceeded` (insert с дедупом на уникальном индексе).

#### Tests
- `models.TestNullableFloat_TriState` — 4 кейса: absent / null / number / zero. Гарантирует, что custom-unmarshaler различает absent от null (стандартный `*float64` не различает).
- `handlers.TestLimits_ProgressEndpoint` — round-trip: PATCH `monthly_limit=N` → книжим транзакцию → progress endpoint возвращает корректные `spent` / `percent` / `total_*`. Затем PATCH `monthly_limit:null` → endpoint снова пуст.
- `handlers.TestLimits_NotificationOnOverflow` — расход поверх лимита генерирует category-notification + global-notification (тест поллит до 2с т.к. триггер async); повторные over-limit расходы не плодят дубли (дедуп держится).

### [1.19.0] — 2026-05-13

#### Added
- **Управление пользователями (admin)** — phase 4 admin-консоли. Новая группа `/api/admin/users/*` под `AdminRequired`:
  - `GET /admin/users` — список с админ-полями (`login`, `is_admin`, `blocked_at`, `created_at`); soft-deleted скрыты, заблокированные включены (админу нужно их видеть, чтобы разблокировать).
  - `POST /admin/users` — создать (login + password + display_name + is_admin). 409 на коллизии логина (unique-индекс).
  - `PATCH /admin/users/:id` — partial-update login / display_name / is_admin / blocked. Pointer-семантика как в категориях. `blocked=true` ставит `blocked_at=now()`, `false` — unset.
  - `POST /admin/users/:id/password` — admin-reset (без старого пароля).
  - `POST /admin/users/:id/avatar` (multipart) — загрузка PNG/JPEG/SVG до 512KB, хранится inline в `users` коллекции (поля `avatar_mime`/`avatar_data`); `avatar_url` выставляется на `/api/users/:id/avatar?v=<ts>` (cache-buster при смене файла).
  - `DELETE /admin/users/:id/avatar` — снять.
  - `DELETE /admin/users/:id` — **soft-delete** (`deleted_at=now()`); UserInfo-снимки в существующих транзакциях/желаниях остаются валидными.
  - `GET /api/users/:id/avatar` (auth, не admin-only) — отдать байты (нужно всем клиентам, рендерящим аватары в записях).
- **`POST /api/auth/password`** (self) — смена собственного пароля с проверкой старого. Возвращает 401 «Старый пароль не подходит» если bcrypt не сошёлся.
- **Soft-delete + блокировка enforcement** — `/auth/login` возвращает 403 для заблокированного юзера; `/auth/refresh` — 401 для удалённого, 403 для заблокированного. `FindByLogin` исключает `deleted_at` (повторное создание логина после soft-delete не даст логиниться удалённому, но новый пользователь с тем же login не получится — unique-индекс этого не позволит до hard delete; этого достаточно для семейного приложения).
- **Safeguards в admin-handler'е**: нельзя снять с себя `is_admin` (поле `Blocked=true`), нельзя удалить себя, нельзя снять админку с последнего активного админа, нельзя удалить последнего админа.

#### Changed
- `User` модель получила `BlockedAt`/`DeletedAt` (`*time.Time`, omitempty), `AvatarMime`/`AvatarData` (inline blob, скрыты от JSON через `json:"-"`).
- `UserRepository.FindAll` / `EnsureAdmin` фильтруют soft-deleted; `FindByID` — нет (нужен для UserInfo-снимков в записях).

### [1.18.0] — 2026-05-13

#### Added
- **Refresh-токены**. Login теперь возвращает access (24ч) + refresh (30д) JWT. Новый эндпоинт `POST /auth/refresh` принимает `{refresh_token}` и выдаёт свежую пару (rotation). Refresh-токен помечен `token_type:"refresh"` в claims; `middleware.Auth` явно отвергает их на защищённых эндпоинтах (только `/auth/refresh` их принимает). Каждый токен карьерит уникальный `jti` (uuid) — последовательные refresh-вызовы в одну секунду производят разные подписи. Тест `TestAuth_Refresh` проверяет login→refresh→retry, отказ access-as-refresh, отказ refresh-as-access на `/auth/me`, и отказ garbage-токенов.

#### Fixed
- **`next_due_date` для never-paid регулярных расходов** теперь считается от `created_at` записи, а не от `time.Now()`. Раньше пользователь, добавивший месячный регулярник 7-го числа, видел в Прогнозе «след. оплата: 13.05» вместо ожидаемого «07.05» — теперь дата привязана к моменту создания записи. Запись с пустым `created_at` (теоретически возможный legacy случай) фоллбэчит на `now`, чтобы не было zero-time.

### [1.17.1] — 2026-05-13

#### Fixed
- **Дефолтные wishlist-категории выровнены с expense**: `wishlist:Дом` → `Жильё/ЖКХ`, `wishlist:Техника` → `Электроника` (вместе с цветом/иконкой). Когда вишлист-айтем помечался «Куплено», связный расход создавался с категорией из вишлиста — а та была `Дом`, в то время как expense-сид содержит `Жильё/ЖКХ`. Итог — две категории-дубля в pie chart, и поломанный визуал на Android (где для `Дом` нет иконки). `EnsureDefaults` теперь делает one-shot rename устаревших дефолтов перед основным upsert-проходом: ищет `section:oldName` с `is_default:true`, переименовывает in-place если `section:newName` ещё не существует, бампает `version` + `updated_at` для sync-клиентов. Тест `TestCategoryRepo_EnsureDefaultsRenamesLegacyWishlistNames` проверяет переименование + идемпотентность.

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

### [1.41.0] — 2026-05-26

#### Added
- **Разделение дохода по депозитам.** В Income action `Разделить доход` под «⋯» открывает `SplitIncomeModal.vue`: input-number «Делить на» (2–10), список prefilled-карточек `{amount × DepositChip}`, auto-balance последнего слота при правке предыдущих, чёткая валидация sum == parent. После split: parent скрыт из стандартного списка, дети видны со своими деньгами/депозитами. Кнопка `Расформировать` под «⋯» split-parent'а (видна когда включён чекбокс «Показать разделённые») и удаление split-child'а вызывают POST `/transactions/:id/unsplit` через `useDialog` confirm, после чего исходная запись восстанавливается и её можно разделить заново.
- **Фильтр «Показать разделённые»** на IncomeView (desktop inline + mobile filter-popover) — пробрасывает `include_split=true` в `GET /transactions`. Счётчик `activeFilterCount` учитывает его.

#### Changed
- **Actions-панель Income/Expenses overhaul.** Больше нет четырёх кнопок в ряд — оставлены только два quick-action (👁 показать/скрыть и 🗑 удалить); вторичные действия (Шаблон, Разделить/ЗнД) ушли под единый «⋯»-popover. Pencil-edit в actions удалён (inline-pencils в каждой клетке решают задачу полностью). Хелпер `renderActionsRow({ quick, more, compact })` в `utils/adaptiveTable.js` — единый рендерер для обоих view; в compact-режиме всё схлопывается в единый popover как раньше.

### [1.40.3] — 2026-05-25

#### Fixed
- **IB модалка**: focus-border `n-input-number` всё ещё обрезался по бокам — на `n-tabs-pane-wrapper` стоит `overflow: hidden` для slide-анимации. Override на `overflow: visible` + горизонтальное padding 6px на pane даёт 2px primary-ring место отрисоваться полностью.

### [1.40.2] — 2026-05-25

#### Fixed
- **DepositChip tooltip**: NTooltip перестал «прицепляться» к NDropdown'у — теперь tooltip оборачивает именно `<button>` чипа, а NDropdown снаружи. Хинт появляется при наведении на саму иконку, а не при раскрытии popover'а с опциями.

#### Changed
- **Initial balance шапка**: размер шрифта суммы 16→14px (строка с двумя счетами теперь не выпирает за рамку карточки на узких pane). «Не задан» — 13px.
- **Initial balance модалка**: таб-триггеры получили padding 10px по вертикали (раньше слипались с текстом «Сумма на…»), tab-pane получил `padding: 12px 4px 4px` чтобы 2px primary-border на focus'е `n-input-number` не обрезался по бокам, форма получила небольшой top-margin для воздуха под индикатором активной вкладки.

### [1.40.1] — 2026-05-25

#### Changed / Fixed
- **`DepositChip`** теперь оборачивается в `NTooltip` — на hover показывается `Счёт: <Карта|Наличные> · нажмите для смены` (раньше был только нативный `title` attribute, не стилизованный).
- **Колонка «Счёт»** в десктоп-таблицах Income/Expenses без заголовка (иконка self-explanatory). Width сокращён 44→36 px.
- **Initial balance** на IncomeView переделан: в карточке шапки одновременно показываются обе суммы (Банковская карта / Наличные) + кнопка «Изменить». Модалка теперь содержит вкладки bank/cash вместо радио-селектора «куда записать» — пользователь редактирует обе суммы за одно открытие. Удаление per-deposit.
- **NSelect высоты** «Счёт» на Statistics выровняли с соседями — убрали `size="small"` (TilePeriodPicker / NDatePicker по умолчанию medium).
- **Forecast wishlist таблица** теперь учитывает `forecastDeposit`: `wishlistOnly` фильтрует `wlStore.items` по `deposit`. Раньше при фильтре `cash` donut и summary стояли корректными, а таблица «Список желаний» показывала все элементы независимо от scope'а.
- **StatisticsView mobile**: триггеры периода и счёта разнесены в две отдельные кнопки — `NPopover` (период + tile picker / daterange внутри) и `NDropdown` (Все / Карта / Наличные). Иконка-триггер для счёта меняется по выбранному scope (wallet/card/cash).

### [1.40.0] — 2026-05-24

#### Added
- **Deposit scope (банковская карта / наличные).** Транзакции и wishlist-итемы получили поле «Счёт» (`bank` / `cash`, default — `bank`).
  - `Income/Expenses`: на каждой карточке (mobile) и в отдельной колонке таблицы (desktop) появляется `DepositChip` — кликабельная иконка, открывает dropdown переключения. В формах создания/редактирования — `n-radio-group` «Банковская карта» / «Наличные».
  - `Forecast`: общий селектор «Счёт» в шапке (фильтрует прогноз по scope, передаёт `?deposit=` в `/api/statistics/forecast`); в форме добавления wishlist/regular — тот же radio-group.
  - `Statistics`: селектор «Счёт» рядом с period picker'ом (desktop inline + mobile popover); параметр прокидывается в `summary` / `by-category` / `monthly`, а также в drill-down router-query (`/income?categories=…&deposit=…`).
  - Initial balance (модалка): тоже принимает `deposit`, дефолт — `bank`.
- **`utils/deposit.js`** — константы (`DEPOSIT_BANK` / `DEPOSIT_CASH` / `DEPOSIT_DEFAULT`), `DEPOSITS` metadata (label / shortLabel / `CardOutline`/`CashOutline` иконка из `@vicons/ionicons5`), `depositMeta()`, `normalizeDeposit()` сворачивает unknown→bank.
- **`components/DepositChip.vue`** — переиспользуемый чип: read-only (просто иконка + опциональный label) или `editable` (NDropdown с двумя пунктами, эмитит `update:modelValue`/`change`).
- **`stores/transactions.js`** — `filters.deposit` в стор, передаётся как `?deposit=` параметр в `/api/transactions`.
- **`api.statistics.forecast(params)`** — теперь принимает query-параметры (раньше всегда был без аргументов).

### [1.39.2] — 2026-05-22

#### Changed
- **Скроллбар в основном контенте теперь только под хедером.** Раньше внутренний `<n-layout>` имел `overflow-y: auto`, и скролл-контейнер охватывал и header, и content — справа от header'а проходила полоса. Теперь inner layout — flex-колонка с pinned-header'ом (`flex-shrink: 0`) и `<n-layout-content>` с собственным `overflow-y: auto`; скроллбар начинается ниже header'а.
- **Тематизированные нативные скроллбары.** Глобальные стили `::-webkit-scrollbar*` + Firefox `scrollbar-*` в `styles/theme.css`: прозрачный track, полупрозрачный thumb (8px, чуть темнее на hover), CSS-переменные `--scrollbar-thumb`/`--scrollbar-thumb-hover` переключаются между light/dark через `:root[data-theme='dark']`. Покрывает все нативно-скроллящие контейнеры (n-layout-content, custom overflow-блоки), приводя их к виду уже стилизованных списков (NotificationsList, CategoryDonutChart и т.п.).

#### Fixed
- **Состояние свёрнутого сайдбара сохраняется между обновлениями страницы.** Click на trigger-кнопке `<n-layout-sider>` теперь пишется в `localStorage['budget-sidebar-collapsed']`, и при следующем mount'е sidebar восстанавливается в том же состоянии. До этого `collapsed` всегда инициализировался в `false`.

### [1.39.1] — 2026-05-20

#### Added
- **Tests.** Новые unit-тесты для `utils/categoryIcons.js` (16 кейсов: builtin/custom/Pascal→kebab нормализация, fallback-палитра), `stores/iconCache.js` (8: resolve + cache, inflight coalescing, error retry, invalidate), `utils/adaptiveTable.js` (20: pencil/ok/cancel/popconfirm/popover render-фабрики + `useAdaptiveTable` с ResizeObserver-моком), `components/{FabButton,SettingsTabs,NotificationsList,NotificationBell,BulkFabRow,CategoryLabel}.vue` (props/emit/router-navigation/mobile-breakpoint/2-tap confirm/icon-cache integration). Всего +9 файлов, +94 теста; всего сейчас 18 файлов, 145 проходящих тестов.

#### Changed
- **Coverage-exclude (`vitest.config.js`).** В список исключённых из метрики добавлены `CategoryDonutChart.vue` (SVG-donut с анимацией и drilldown), `SwipeableCard.vue` (touch-gesture рейлы) и `SetupWizard.vue` (first-run multi-step). Unit-тесты на канвас и touch-события хрупкие; перенесены в Playwright e2e (см. `docs/E2E_PLAN.md`). Lines-coverage поднялся с 44.5% → **86.8%**.

### [1.39.0] — 2026-05-19

#### Added
- **First-run setup wizard.** `SetupWizard.vue` рендерится поверх всего layout'а, пока бэкенд отвечает `needs_setup=true` (probe в `App.vue → onMounted → /api/setup/status`). Двухшаговый flow: (1) форма создания админа — display_name / login / password+repeat с визуальным password-strength баром (0–5 баллов по длине + классам символов), не блокирующая submit; (2) необязательный импорт JSON — drag-n-drop файла или paste в textarea с inline-валидацией `schema_version`. После создания админа `auth.setAuth` берёт выданный токен; после import (или skip) `done` → `router.replace('/statistics')` без перезагрузки.
- **`/settings/portability` — Импорт / Экспорт.** Третья таба в `SettingsTabs.vue`. Скачивание JSON-снимка через `blob` response с парсингом `Content-Disposition`; импорт с radio-выбором режима (`merge` default / `replace`) + dropzone + inline textarea. Статистика импорта (импортировано/пропущено по типам) отображается в success-alert.
- Mobile-навигация: для админа в выпадающем menu секции «Настройки» добавлена новая ссылка «Перенос».

### [1.38.0] — 2026-05-19

#### Added
- **Тег «Привязано к…» в строке расхода.** Для каждой транзакции расходов с непустым `wishlist_id` рендерится round `NTag type="info"` с иконкой 🔗 и подписью `Регулярный: <name>` или `Желание: <name>`. На desktop вшит в колонку «Описание» (над текстом описания, или вместо него если описание пустое — режим inline-edit сохранён); на mobile — в `.tx-card-desc` перед текстом. Клик по тегу → `router.push('/forecast?focus=<wishlist_id>')`. Получаем wishlist'ы через общий `useWishlistStore` (fire-and-forget `fetch()` в `onMounted` ExpensesView).
- **Фокус на записи прогноза.** `ForecastingView` читает `route.query.focus` на маунте + watch'ит изменения; находит item в `wlStore`, переключает мобильную табу (`regular` ↔ `wishlist`) если нужно, ставит `focusedId`, скроллит к строке/карточке (`scrollIntoView({behavior:'smooth', block:'center'})`) и подсвечивает её через `row-class-name="fc-row-focus"` (desktop) / `:class="fc-card-focus"` (mobile). CSS-анимация `fc-focus-flash` — 2.5с amber-фон+обводка ease-out → прозрачный.

### [1.37.1] — 2026-05-19

#### Changed
- **Action-колонка Прогноза перестроена в 3 фиксированных слота.** Раньше в строке регулярного расхода рисовалось 4 кнопки (`refresh-or-placeholder | check | link | trash`) — для оплаченной строки `check` и `link` рендерились как `quaternary`-серые на серой подложке и читались как «пустое место» между ✗ и 🗑. Теперь центральная кнопка одна и переключается по состоянию: `✓ Оплачено` ↔ `✗ Отменить оплату`. Иконка-привязка сдвинута влево, trash остаётся справа. Wishlist получил такую же раскладку `[🔗 link-or-placeholder | ✓/✗ toggle | 🗑 trash]` — у уже-купленных позиций link-слот пустой, но trash остаётся в общей вертикали.

### [1.37.0] — 2026-05-19

#### Added
- **Привязка существующего расхода к wishlist/regular-итему.** Новый компонент `LinkExistingExpenseModal.vue` показывает список не связанных расходов (`/api/transactions?unlinked=true`, лимит 100) с поиском по назначению/категории/сумме; на каждой строке — `ConfirmActionButton` для двухтапового подтверждения. Привязка идёт через `POST /api/wishlist/:id/link/:tx_id` (api ≥ 1.21.0), который сам клонирует категорию из wishlist в expense с цветом/иконкой если её там нет, и для `once`-итемов выставляет `purchased=true`. Кнопка-иконка «Привязать» добавлена в actions-колонку и для регулярных, и для желаний (desktop), и в swipe-actions (mobile) — для wishlist скрывается когда `purchased=true`. После привязки store категорий, store wishlist и forecast обновляются, чтобы pie/легенда сразу подхватили возможную новую категорию.
- `wishlist.linkExisting(id, txId)` в `api/index.js`; `LinkOutline` импорт + новый `.swipe-action-info` стиль (`#2080f0`).

### [1.36.0] — 2026-05-19

#### Added
- **Autocomplete на свободно-вводимых полях форм.** «Источник» (Доходы), «Назначение» (Расходы), «Название» (Прогноз — wishlist + regular делят один ключ) теперь рендерятся как `<n-auto-complete>` с dropdown'ом из localStorage-истории недавно введённых значений. На каждом submit'е значение пушится в начало списка (дедуп — точное совпадение перемещается, не дублируется), pukoнечный список капается до 20 entries. `get-show: () => true` показывает dropdown даже на пустом input'е — на focus юзер сразу видит свои частые «Магнит / Зарплата / Интернет» без необходимости начать печатать или копировать через свайп-«Шаблон». Замена «обычной браузерной автоподстановке», которая в SPA-формах без real form-submit работает непредсказуемо.
- **`utils/inputHistory.js`** — общий helper: `loadHistory(key)` / `pushHistory(key, value)` / `historyOptions(key)`. Storage под префиксом `budget-history-` (легко вычистить в DevTools), graceful-fallback при QuotaExceeded / Safari private-mode, фильтрация corrupt-payload'ов. 7 тестов под `tests/utils/inputHistory.test.js` покрывают dedup, кап на 20, trim whitespace, broken JSON.

### [1.35.0] — 2026-05-19

#### Added
- **Mobile UX-анимации.** Шесть точечных `<Transition>` / `<TransitionGroup>` оборачивают ключевые swap'ы в mobile-вьюхах. Все классы объявлены глобально в `styles/theme.css` (`mobile-slide`, `fab-swap`, `bulk-icon`, `tx-list`, `tab-fade`). Длительности 120-220мс, animate только `transform` + `opacity` (GPU-friendly), `@media (max-width: 767px)` гейт где это имеет смысл только на мобильном, `prefers-reduced-motion: reduce` отключает всё. Места:
  - **Mobile add/edit ↔ list slide** в Income/Expenses + **list/editor swap** в AdminCategoriesView/UsersAdminView (`mobile-slide`, slide-from-side + fade ~220мс). В админ-вьюхах `:class="{hidden}"` заменён на `v-show` чтобы `<Transition>` мог цеплять enter/leave-классы — desktop поведение не задето.
  - **FAB-«+» ↔ BulkFabRow crossfade** в Income/Expenses/Forecast (`fab-swap` mode="out-in", fade+scale(0.85) ~150мс).
  - **Avatar ↔ bulk-circle swap** на карточке записи (Income/Expenses/Forecast wishlist) — `bulk-icon` mode="out-in", fade ~120мс. На Forecast regular fade-in/fade-out целого `.tx-card-left` при входе/выходе bulk-режима.
  - **Collapse-leave удалённой карточки** в Income/Expenses/Forecast — `<TransitionGroup name="tx-list" tag="div">` с `max-height` + `margin` collapse + opacity 0 + translateX(-12px), ~220мс. Enter не анимируется чтобы не было flicker'а при первом рендере страницы.
  - **Tab content fade** в Forecast (Аналитика/Регулярные/Желания) — `tab-fade`, opacity ~140мс, mobile-only.

### [1.34.0] — 2026-05-19

#### Added
- **`BulkFabRow.vue`** — общий компонент для mobile bulk-mode toolbar'a в стиле Android Scaffold floatingActionButton (см. `IncomeScreen.kt:124-148`). Ряд FAB-style кнопок снизу-справа, position: fixed; принимает массив `actions` с полями `{icon, title, variant, confirm, loading, onClick}`. Variant'ы: `default` (нейтральный surface-bg, для toggle/cancel), `primary` (синий), `danger` (красный). Confirm-вариант делает 2-tap (первый тап — pulse-анимация на 2.5с, второй — реальный onClick), чтобы случайный тап на FAB не сносил выбор.

#### Changed
- **Mobile bulk-mode переехал на FAB-row.** В Income / Expenses / Forecast (regular + wishlist) на мобильном при выборе записей FAB-«+» подменяется на 2-3 FAB'a (Hide/Show или Cancel-action, Delete, Отмена выбора). Inline-toolbar buttons (`Скрыть/Удалить/Отмена`) скрыты под `v-if="!isMobile"` — но «Выбрано: N» остаётся, чтобы пользователь видел счётчик не отрывая взгляда от карточек. Mirror Android UX.
- **Swipe-action цвета Hide/Template поменялись местами.** Hide теперь использует `swipe-action-info` (синий `#2080f0`) — действие неразрушительное, нейтральный toggle. Template — `swipe-action-warning` (оранжевый `#f0a020`) — привлекает внимание, т.к. переносит данные в форму добавления. Раньше пара была инвертирована.

#### Fixed
- **Template-свайп на мобилке открывает форму с заполненными полями.** Раньше `fillFromTemplate()` выставлял `form.value` и показывал snack «Форма заполнена по шаблону» — но сама форма на мобильном скрыта до нажатия FAB-«+», и пользователь оставался на экране списка без видимой формы и со снеком про мнимый успех. Теперь на `isMobile` дополнительно выставляется `mobileAdding=true` — форма открывается сразу с pre-fill'ом, snack убран как избыточный. Затронуло Income и Expenses (Wishlist шаблона не имеет).

### [1.33.0] — 2026-05-18

#### Added
- **Drill-down по клику на pie-чарте.** Слайс на «Расходы по категориям» / «Доходы по источникам» кликабелен: переход на `/expenses` или `/income` с pre-applied фильтром (`categories` + `from`/`to`). Период берётся из текущего picker'а Statistics (month → 1-е/последнее число месяца, year → 01-01/12-31, custom → as-is). `CategoryDonutChart` эмитит `drilldown(name)`, `StatisticsView` строит URL и пушит через `useRouter`. `IncomeView` и `ExpensesView` читают `route.query` в onMounted + watch и гидрируют локальные `filterCategories`/`filterRange`, чтобы UI-фильтра тоже отразил состояние.
- **Раскрытие «Прочее» по клику + кнопка «Назад» в центре donut'a.** Клик по синтетическому wedge'у «Прочее» не дрилдаунит, а скрывает все остальные категории (`hiddenSet = allLabels - groupedLabels`) — маленькие категории раздвигаются в полноразмерные wedges и становятся кликабельными для drilldown'a. В дырке donut'a появляется theme-aware кнопка «← Назад» (mount'ится при `hiddenStack.length > 0`); один клик pops top of stack, поддерживает nested-«Прочее» (когда мелкие категории сами объединяются в новое «Прочее»). Зеркалит Android-поведение 1.36.
- **`<CategoryLabel>`-компонент.** Один общий компонент рендерит `[иконка] <название>` без бейджа-фона: иконка тинтуется в `Category.color`, размер равен размеру текста, custom-SVG'и тинтуются через `mask-image` (alpha-only стандарт). Вертикальное выравнивание: `vertical-align: middle` на корневом inline-flex (центрирует относительно line-box родителя), `line-height: 1` на root + ico, `line-height: 1.1` на text + `transform: translateY(-1px)` на `.cat-label-ico` — компенсирует асимметрию descender/ascender, чтобы визуальный центр иконы совпадал с x-height текста на n-select / n-tag триггерах. Подключён в:
  - n-select dropdown'ах (`render-label`) на формах Income/Expenses/Forecast/Pay-modal/DetailRequest — иконка рядом с названием в раскрытом списке и (для single-tag-mode) в селекте-триггере. `render-tag` только на multi-select-фильтрах — на single-select'ах Naive вызывает его с placeholder ghost-option'ом, что порождало пустой pill с крестиком вместо плейсхолдера.
  - Multi-select фильтры Income/Expenses: `render-tag` оборачивает CategoryLabel в `NTag size="small"` (без margin-override — Naive сама задаёт spacing между chips, иначе высота control'a уезжает выше n-date-picker'a).
  - Mobile SwipeableCard на Income/Expenses (`tx-card-category`) — иконка рядом с названием категории в карточке записи.
  - Desktop NDataTable «Категория»-колонке для Income/Expenses и для регулярных расходов + wishlist в Forecast (inline-edit display).
  - Mobile карточки регулярных расходов + wishlist в Forecast, список children в `DetailRequestModal`.
  - Категории в `categories` store расширены: `options()` отдаёт `color`/`icon`/`icon_scale`; новые helper'ы `findByName(section, name)` и `findAcrossSections(name)` для lookup'ов по имени без перебора массива на каждый рендер.
- **Подписи на swipe-action кнопках (mobile).** Action-кнопки SwipeableCard на Income/Expenses (Скрыть·Шаблон·Удалить) и Forecast регулярных/wishlist (Оплачено·Куплено·Отменить·Не куплено·Удалить) рендерят иконку + текстовую подпись (column-flex, 10px caption), как в Android-клиенте — раньше пользователь видел только цветную иконку без значения.
- **Luminance-aware текст на выбранных `n-radio-button`-ах + mobile tab-strip'ах.** Новый стор-геттер `onPrimaryColor` (`textOnPrimary(activeTheme.primary)`, порог luminance 0.30) экспонирует контрастный цвет для primary-фонов. В `themeOverrides` добавлены `Radio.buttonColorActive` + `buttonTextColorActive` + `buttonBorderColorActive` (light и dark), используют тот же `textOnPrimary()` что и Button — выбранный radio на оранжевом/бирюзовом теперь рисуется тёмным текстом, на холодных primary — белым. То же для `.forecast-tabs` / `.settings-tabs` через CSS-var `--st-on-primary`.

#### Changed
- **Локализация date-picker'a (RU).** `NConfigProvider` получает `:locale="ruRU"` + `:date-locale="dateRuRU"` из `naive-ui`. Календарь автоматически: (а) начинает неделю с Понедельника (date-fns ruRU `weekStartsOn: 1`), (б) рисует заголовки дней «Пн/Вт/Ср/...» вместо «Su/Mo/Tu/...», (в) переименовывает футерные кнопки «Очистить» / «Подтвердить». Затрагивает все date-picker'ы (Доходы/Расходы/Прогноз/Statistics/Export). Кнопки футера подняты с tiny до small (28px высоты, 13px font, padding 0 14px) через глобальный CSS в `styles/theme.css` — popup mount'ится в body, scoped CSS не достаёт.
- **Mobile tab-strip'ы (settings / forecast / admin-sections) выровнены на пару 3/3px.** `.settings-tabs` / `.forecast-tabs` / `.admin-sections` mobile container + inner = `3px` (default Naive `borderRadius`) — все три tab UI совпадают по визуальному радиусу с соседними NCard'ами. SettingsTabs (Категории/Пользователи) также перешёл на `palette.cardSurface` bg вместо `palette.surface`: на dark-теме `cardSurface=#18181c` темнее, чем `surface=#1e1e1e`, поэтому верхняя и нижняя tab-полоски в `/settings/categories` теперь читаются единым блоком.
- **Swipe-action цвета — Naive-палитра + Material Grey на cancel-действиях.** Income/Expenses Hide=warning `#f0a020`, Template=info `#2080f0`, Delete=error `#d03050`. Forecast Regular/Wishlist Paid·Bought=success `#18a058`, Delete=error `#d03050`. На «отменяющих» свайпах (Forecast Regular «Отменить», Wishlist «Не куплено») использован Material Grey `#757575` — жёлтый warning там читался как «опасное действие», хотя действие неразрушительное (возврат в нейтральное состояние).

#### Fixed
- **CategoryLabel в mobile-tx-карточках не съезжает вверх + дата не переносится.** `.tx-card-row1` в Income/Expenses View'ах перешёл с `align-items: baseline` на `align-items: center` — `CategoryLabel` это `inline-flex` с собственным `line-height: 1`, его baseline считается от icon-box'a, не от текста, поэтому baseline-выравнивание визуально сдвигало категорию вверх относительно даты-sibling'a. `.tx-card-date` получил `white-space: nowrap` + `flex-shrink: 0`, чтобы «17.05.2026» не ломалась на две строки в узких карточках.

### [1.32.8] — 2026-05-15

#### Fixed
- **«Количество транзакций» убрано из чарта «Прогноз по категориям».** Breakdown в прогнозе смешивает исторический tx-средний и проектируемые wishlist-итемы — единого осмысленного tx-count там нет, поэтому раньше все строки показывали `0`. Добавил prop `hide-count` в `CategoryDonutChart` и проставил его в `ForecastingView`. На Statistics-чартах подпись осталась как было.

### [1.32.7] — 2026-05-15

#### Added
- **Подпись «Количество транзакций: N» в легенде `CategoryDonutChart`.** Берётся из поля `count` в `CategoryData` (бэкенд уже отдаёт). Для категорий **без** лимита надпись отрисовывается под названием категории; для категорий **с** лимитом — под прогресс-баром по левому краю (на одной строке с правой подписью «X / Y ₽ (Z%)», через `justify-content: space-between`). `StatisticsView` теперь пробрасывает `count` в `data` для обоих донат-чартов (expense + income).

### [1.32.6] — 2026-05-15

#### Changed
- **Иконка категории в легенде `CategoryDonutChart` центрируется по вертикали всего item'а.** Badge вынесен из `.cdc-row-main` как прямой ребёнок `.cdc-row`; `cdc-row-main` + `cdc-row-limit` переехали внутрь нового `.cdc-row-content` (flex-column). Теперь `align-items: center` на `.cdc-row` центрирует иконку по середине строки независимо от того, есть ли лимит-бар на второй строке. Раньше badge был привязан к верху строки на лимитных рядах.

### [1.32.5] — 2026-05-15

#### Changed
- **Лимит-бар в легенде `CategoryDonutChart` начинается под названием категории, а не под иконкой.** `.cdc-row-limit` получил `padding-left: 48px` (badge 36px + row gap 12px) — бар теперь стартует от колонки имени и тянется до правого края; подпись «X / Y ₽ (Z%)» остаётся по правому краю под суммой.

### [1.32.4] — 2026-05-15

#### Changed
- **Лимит-прогрессбар в легенде `CategoryDonutChart` теперь во всю ширину строки.** Бар сидит на отдельной строке под «название + сумма», тянется через весь row (а не только под meta-колонкой как в 1.32.3); подпись «X / Y ₽ (Z%)» по правому краю — лежит точно под суммой по категории. Hover-тултип с долей в диаграмме на сумме сохраняется.

### [1.32.3] — 2026-05-15

#### Changed
- **Лимит-прогрессбар в легенде `CategoryDonutChart` переехал внутрь meta-колонки.** Раньше бар + подпись лежали отдельной строкой под всей строкой легенды; теперь они подменяют собой строку «X% от чарта» под суммой категории, а доля диаграммы переехала в hover-тултип на самой сумме («Доля в диаграмме: 9.2%»). Строки без лимита остались как были (amount + %). Бар тянется на всю ширину meta-колонки (`align-self: stretch`); лимит-строкам выделен `min-width: 140px` чтобы текст «12 326 / 10 000 ₽ (123%)» не давил бар в полоску.

### [1.32.2] — 2026-05-15

#### Added
- **Прогресс-бар по лимиту в легенде pie-chart'а (`CategoryDonutChart`)** — те же стили, что и в `/settings/categories`: тонкая `n-progress` line + строка `<spent> / <limit> ₽ (<percent>%)` под основной строкой легенды. Рендерится только если у категории установлен `monthly_limit`. Цвет (`palette.income` / amber / `palette.expense`) синхронизирован с другими местами, где показывается лимит. `StatisticsView` подтягивает `catApi.limitsProgress()` параллельно с pie-данными и прокидывает name-индексированный map только в expense-донат (income лимиты не поддерживает). Лимиты всегда отражают **текущий календарный месяц** — не подстраиваются под выбранный период фильтра (monthly limit vs YTD сравнение было бы вводящим в заблуждение). Значения суммы блюрятся под `valuesHidden` так же, как и основная сумма строки.

### [1.32.1] — 2026-05-15

#### Fixed
- **Мобильный header больше не переполняется.** Четыре визуальных тоггла (цвет темы, скрыть/показать суммы, %/₽ диаграмм, тёмная тема) свернуты в один popover-«Внешний вид» под иконкой `ColorPaletteOutline`. Внутри — секция «Цвет темы» (theme-dots) + вертикальный список из трёх кнопок-тогглов с подписями. Так 7 иконок (avatar + DR-bell + NotificationBell + 4 визуальных + info) сжимаются до 4 (avatar + DR-bell + NotificationBell + visuals + info) и не выталкивают строку «<дата>» на следующую строку.
- **Прогресс по лимитам в админке обновляется сразу после сохранения.** `loadLimitsProgress()` выделен из `loadAll()` и вызывается после Create-with-limit, Update-with-monthly_limit-change и Delete-of-limited-category. Раньше прогресс-бары в списке категорий показывали устаревшие данные до перезагрузки страницы.
- **Стили карточки «Лимит расходов» (ExpensesView) выровнены с «Баланс на начало месяца» (IncomeView):** убрано `size="small"` (заголовок теперь default-NCard размера), сумма 18px (было 16px), цвета суммы и progress-bar тянутся из `palette.income` / `palette.expense` вместо hardcoded `#22C55E` / `#EF4444` — теперь в светлой/тёмной теме green/red точно совпадают с цветами доходов/расходов в остальной части UI. Прогрессбар amber на 80%+ остаётся хардкодом (палитра не несёт оранжевого токена). Тот же палитро-aware tint применён и к per-row progress в `AdminCategoriesView`.
- **«Баланс на начало месяца» на мобильном перенесён в history-view.** Раньше IB-карточка показывалась только при добавлении/редактировании дохода (была вложена в add-form wrapper). Теперь вынесена из add-wrapper'а; её собственный `v-show` показывает её только в history-режиме на мобильном (на десктопе — всегда), мирорит paзмещение «Лимит расходов» в Expenses.

### [1.32.0] — 2026-05-15

#### Added
- **Лимиты расходов на категории (Phase 5).** UI поверх api 1.20.0.
  - `AdminCategoriesView`: новое поле «Месячный лимит (₽)» (NInputNumber с `clearable`) — видно только для expense-категорий. PATCH использует tri-state: число → `monthly_limit:N`, очистка → `monthly_limit:null`, без изменения → ключ отсутствует. Прогресс-бар (NProgress, line) под каждой строкой списка категорий — пороги зелёный/жёлтый/красный на 80/100%. Текст-метка: `<spent> / <limit> ₽ (<percent>%)`. При создании новой категории лимит выставляется follow-up PATCH'ем (Create не принимает поле — мирор `icon_scale`).
  - `ExpensesView`: новая карточка-заголовок в левом pane «Лимит расходов» (read-only). Показывает текущий календарный месяц **независимо** от фильтра периода истории; если фильтр охватывает другой период, рядом отображается `n-tag` «тек. месяц» (success-стиль) чтобы пользователь не путал. Прогресс-бар + сумма потрачено / лимит + процент. Подписан на `store.items` через `watch(deep:true)` — обновляется после CRUD без перезагрузки.
- **Bell-popover для уведомлений в header.** Новые `NotificationBell.vue` + `NotificationsList.vue` + `stores/notifications.js`. Иконка-колокольчик (`NotificationsOutline`) с `n-badge` (unread count, max 9). Мобильный — `n-modal`, десктоп — `n-popover` bottom-end (мирорит DetailRequestBell). Список уведомлений (newest-first) с типом + категорией + suma/limit/period; кнопка «Прочитать все» помечает все через `POST /notifications/read-all` (per-user state). Polling раз в 60с пока вкладка видима (`document.hidden` чек), плюс refresh на `visibilitychange` (возврат во вкладку) и на изменение `store.items` в `ExpensesView` (с 800мс delay — backend-триггер async).
- API: `categories.limitsProgress(month?)`, `notifications.list/readAll/read` в `frontend/src/api/index.js`.

#### Changed
- `cat-row` в `AdminCategoriesView` теперь flex-column (badge+name сверху, progress-bar снизу) — чтобы лимит не загромождал основной ряд.

### [1.31.0] — 2026-05-15

#### Added
- **Desktop Forecast переведён на `NDataTable` (Phase 6)** — карточный list-стиль на десктопе (≥768px) заменён на две независимые `n-data-table` (Регулярные расходы / Список желаний), мирор Income/Expense. Mobile (≤767px) остаётся на SwipeableCard. Inline-edit pencil по каждой ячейке, bulk-checkbox, reassignable user-avatar (только wishlist).
- **Адаптивный `SplitPane`** — новый prop `stack-below` (px) + `ResizeObserver` на контейнере. Когда ширина контейнера меньше порога, слоты автоматически складываются в вертикальный стек (`gap: 16px` между ними), drag-divider прячется. CSS-правило `@media (max-width: 768px)` заменено на JS-driven класс `.stacked` — работает для любого breakpoint'а. Дефолт `stack-below: 768` сохраняет старое поведение Income/Expense.
- **Forecast: `stack-below="1280"`** — на узких экранах/контейнерах две таблицы складываются друг под другом, на широких — SplitPane c drag (storage-key `forecast-split`, default 50/50, min 30%, max 70%).
- **Per-table адаптивная компрессия (`utils/adaptiveTable.js`)** — общая утилита для Forecast / Income / Expenses: `useAdaptiveTable(threshold)` (ResizeObserver + `compact` флаг), фабрики `pencilBtn` / `okBtn` / `cancelBtn` / `iconActionBtn` / `renderActionButton` / `renderActionsPopover` / `plainTextCell`. Единый порог `COMPACT_TH = 740`. Когда pane < 740, ВСЕ оптимизации срабатывают одновременно: action-кнопки сворачиваются в «•••» popover (`EllipsisHorizontalOutline` с вертикальным списком действий и подписями), pencil-affordance скрываются, текстовые колонки получают `ellipsis: { tooltip: true }` (truncation «…» + tooltip с полным текстом по hover), `NPopconfirm` для destructive-операций сохраняется внутри popover'а.
- **Адаптивный паттерн распространён на Income / Expenses.** Колонки Категория / Источник / Назначение / Описание переключаются между двумя режимами:
  - Wide (≥740): `minWidth: 120`, inline-pencil top-right, full inline-edit.
  - Compact (<740): `minWidth: 70` + `ellipsis: { tooltip: true }`, pencil скрыт, текст сокращается «…» с тултипом.
  Action-кнопки (Скрыть/Показать, Шаблон, Запрос на детализацию у Expenses, Удалить) сворачиваются в «•••» popover. `size="small"` на n-data-table, `scroll-x` снят.

#### Changed
- **Колонки таблиц переразмерены под адаптивную модель.** Forecast/Регулярные: bulk-checkbox 36 / Название (minWidth 90, теги Частота + «Оплачено?» под ним) / Категория (compact `minWidth: 70` + ellipsis, wide `minWidth: 100` + inline-pencil) / Заметки / След. оплата (width 80, скрыта в compact) / Сумма (width 100) / Действия (compact 44 / wide 110). Wishlist: select 42 / Название (minWidth 90, тег «Куплено?» под ним) / Категория / Заметки / Сумма / Действия (compact 44 / wide 80). Income: select 36 / Дата 100 / Категория / Источник / Описание (flex с conditional minWidth + ellipsis) / Сумма (compact 110 / wide 150) / Действия (compact 44 / wide 100). Expenses аналогично + дополнительная action-кнопка детализации.
- **Pencil-affordance в правом-верхнем углу cell'ы по всему ряду** во всех адаптивных таблицах. `<div display:flex; align-items:flex-start>` с `flex:1; min-width:0` на content + pencil справа. Карандашики выровнены в одну вертикаль по столбцу. Колонка «Частота» удалена в Forecast (тег теперь под названием).
- **Колонки «Сумма» / суммы дохода-расхода — left-align** (как остальные колонки). Pencil — top-right, как у всех.
- **Action-колонки — right-anchored.** Forecast/Регулярные: 3 фиксированных слота `[Refresh-или-placeholder, Check, Trash]`, Trash всегда в одной вертикали по всем строкам, Refresh виден только при `paid_this_period`. Wishlist: 2 слота `[Toggle, Trash]`. Income/Expenses: 3-4 слота справа (`[Eye, Copy, (DetailReq?), Trash]`). Все — `quaternary` + `NTooltip` иконочные кнопки. Bulk-highlight через class `.fc-row-sel`.
- **`n-data-table size="small"` на всех таблицах записей** — Naive cell-padding 24 → 16 px на сторону, освобождается ~50 px на 7 колонок, action-кнопки видны без скролла даже при сжатии pane к `minLeft: 30%`.
- **Income / Expenses SplitPane: `max-left: 75 → 60`, `min-left: 20 → 25`** — пользователь больше не может сжать историю до состояния, где даже compact-режим таблицы не помещается. `SplitPane` clamp'ит сохранённую в localStorage позицию против актуальных min/max при mount'е (старый 75% больше не пересилит).
- **Expenses: порядок action-кнопок мирорит Forecast pattern** — `[DetailRequest-или-placeholder, Eye, Copy, Trash]`. Trash всегда в одной вертикали по всем строкам, Eye и Copy слева от него, ЗнД виден только у parent-расходов (когда отсутствует — невидимый 28-px placeholder сохраняет slot-позицию).

#### Removed
- Мёртвый desktop sub-card markup Forecast'а (`.regular-row*` flex-layout, `.inline-edit-icon`, `.user-assign-btn`, `.meta-sep`, `checkboxStyle()`, `pencilIconHtml`) — на десктопе теперь чистый NDataTable.

### [1.30.0] — 2026-05-14

#### Added
- **Swipe-to-reveal actions на мобильных карточках записей.** Новый компонент `SwipeableCard.vue` инкапсулирует жесты: горизонтальный свайп-влево раскрывает action-кнопки за карточкой (revealWidth 180 px = три кнопки по 60 px), вертикальный touch пропускается на скролл страницы, долгий тап эмитит `longpress`, обычный — `tap`. Synthetic click после свайпа подавляется флагом `swipeJustHappened`. Open-state schloss'ится тапом по контенту. `touch-action: pan-y` запрещает браузеру горизонтальный scroll'инг страницы во время свайпа.
- **Доходы / Расходы карточки**: swipe → Скрыть/Показать (warning), Шаблон (info), Удалить (danger). Tap = edit, long-press = bulk-select. Удаление — нативный `window.confirm` (после намеренного свайпа + tap'а отдельный popconfirm избыточен).
- **Forecast Регулярные / Желания карточки**: swipe → Оплачено / Куплено (success) либо Отменить оплату / Не куплено (warning) в зависимости от `paid_this_period` / `purchased`, плюс Удалить (danger). Действия дёргают существующие хендлеры (`openPayRegular`/`openPayWishlist`, `cancelRegularPaid`/`unpurchaseWishlist`, `wlStore.remove`).

#### Changed
- Mobile-card-iteration переведён на `<template v-for>` с разделением `SwipeableCard v-if="isMobile"` / `n-card v-else` — десктоп-вид Forecast (со сложной инлайн-разметкой) рендерится без swipe-обёртки.
- `onForecastLongPressStart` / `onForecastLongPressEnd` / `onForecastCardTap` удалены из ForecastingView — gesture-логика теперь в SwipeableCard. Оставлен `onForecastBulkLongPress(kind, id)` как @longpress-handler.
- Income/Expenses тоже потеряли свой local long-press timer code (`LONG_PRESS_MS`, `onLongPressStart/End`) — обёртка теперь у SwipeableCard.

### [1.29.0] — 2026-05-14

#### Changed
- **Forecast карточки на мобильном — tap-to-edit, как в Доходах/Расходах.** Сложная разметка с inline-pencil'ами и action-кнопками (Оплачено / Куплено / Отменить / Удалить) скрыта на мобильном через `v-if="!isMobile"`; вместо неё — компактная `.tx-mobile-row`: название + бейдж частоты/статуса, строка «категория · заметки · след. оплата ДД.ММ.ГГГГ», сумма справа цветом расхода. Long-press по карточке заводит bulk-режим (как раньше), тап (если не bulk) открывает edit-вью.
- **Edit-вью** = существующая форма «Добавить»: заголовок переключается на «Регулярный расход / Желаемая покупка», submit вызывает `wlStore.update` вместо create. В footer'е появляются Удалить (red-ghost) + Сохранить (primary) в одну строку, ниже — кнопка действия в зависимости от статуса: Оплачено/Куплено (success) если не оплачено в текущем периоде, Отменить оплату / Не куплено (default) если оплачено. Pay-кнопка открывает существующую prefilled-expense модалку (`openPayRegular` / `openPayWishlist`), Cancel-кнопка вызывает `cancelRegularPaid` / `unpurchaseWishlist`.
- Долгий тап теперь снова имеет `forecastLongPressFired`-флаг — после long-press последующий click подавляется (иначе bulk-select заодно открывал бы edit).
- **Desktop вид Forecast записей не менялся** — там по-прежнему inline-pencil'ы и кнопки действий. Конверсия в NDataTable вынесена в отдельный шаг.

### [1.28.0] — 2026-05-14

#### Fixed
- **Luminance-порог текста на primary-кнопке** 0.55 → 0.30: оранжевый (lum ≈0.43) и бирюзовый (≈0.34) теперь получают тёмный текст вместо нечитаемого белого на светлом саттурированном фоне. Холодные синий/зелёный/красный/фиолетовый/розовый имеют lum < 0.30 и остаются с белым текстом.
- **Поповер выбора иконки на узком экране** вылетал за viewport — Naive NPopover не shift'ит контент по горизонтали. Заменили на `NModal` (теперь и на десктопе тоже — centered modal надёжно работает в любых размерах вьюпорта).
- **DetailRequestBell**: на мобильном popover заменён на `NModal` (та же причина — вылазил за край); десктоп оставлен с popover'ом.
- **Бэйдж активных фильтров уезжал за бордер кнопки**: заменили `<n-badge>`-обёртку вокруг иконки на простой суффикс «Фильтр · N» в тексте кнопки.
- **Контраст label'ов в filter-popover** (ПЕРИОД / КАТЕГОРИИ) поднят: `var(--text-3)` → `var(--text-1)` + `opacity: 0.85` + `font-weight: 600` — читаемо на тёмной теме.

#### Changed
- **Mobile FAB** (`FabButton.vue`): круглая 56-px кнопка `+ Добавить` фиксирована bottom-right поверх bottom-nav на Income / Expenses / Forecast. Заменяет inline-«+ Добавить» в шапке карточки. Цвет = `primaryColor` из theme-store через `v-bind`. Скрыта во время add/edit и на десктопе. На Forecast «Аналитика» FAB тоже скрыт (нечего добавлять — только summary + pie).
- **Statistics period selector**: возвращён в один inline-ряд (как было до 1.27). Type-кнопки (Месяц / Год / Период) слева, value-picker справа. На десктопе — inline TilePeriodPicker / NDatePicker; на мобильном — компактная кнопка-триггер с иконкой календаря + текущим лейблом, открывает popover с picker'ом.

### [1.27.0] — 2026-05-14

#### Fixed
- **Текст на primary-кнопках читаем во всех темах.** Naive UI в dark-режиме автоматически переключал `textColorPrimary` на тёмный, давая нечитаемый dark-on-blue. Override считает relative luminance активного `primary`-цвета и принудительно ставит белый текст на тёмных/средних акцентах (синий / зелёный / красный / фиолетовый / бирюзовый / розовый) и `#1f1f1f` только на очень светлых (порог 0.55 — оранжевый/жёлтый/салат). Применяется одинаково в light и dark.
- **Заголовок в `card-back-header` (Income / Expenses / Forecast)** примыкал к back-стрелке: gap 8→12px + extra 2px margin-right на самой кнопке, чтобы стрелка не липла к рамке/тексту. Симметрично — `editor-header` в AdminCategoriesView / UsersAdminView.
- **Поповеры на узком экране (`<400px`) вылазили за viewport**: иконка-picker (AdminCategoriesView), DetailRequestBell. Контентам выставлен `width: min(<base>, calc(100vw − 32px))`, иконка-picker дополнительно переведён с `placement="bottom-end"` на `placement="bottom"` (центр) — Naive auto-shift'нет под viewport надёжнее.

#### Changed
- **Income / Expenses на мобильном — «+ Добавить» в шапке карточки** «История …», справа от заголовка (как в UsersAdminView), вместо full-width-кнопки над списком.
- **Edit-режим (Income / Expenses)**: кнопки «Удалить» и «Сохранить» — в одну строку (flex 1:1) вместо stack'а. Red-ghost слева, primary справа.
- **Фильтры списков (Income / Expenses) свернуты в popover** на мобильном: одна кнопка «Фильтр» с `FunnelOutline` + badge с числом активных фильтров. Внутри popover — date-range + categories multi-select + (для Expenses) checkbox «Показать закрытые запросы» + кнопка «Сбросить». Десктоп — фильтры inline как раньше.
- **Forecast Регулярные / Желания на мобильном — «+ Добавить» в шапке секции** справа от заголовка (а не full-width-кнопка над списком). «Пакетное редактирование» (большая кнопка) скрыто на мобильном — bulk-режим теперь заходит через **long-press** на карточку записи (то же, что в Доходах / Расходах).
- **Long-press**: 450 → **1000 мс** во всех видах. Короткое срабатывало слишком часто при обычной прокрутке.
- **Forecast add-вид** теперь полностью замещает список Регулярных / Желаний (раньше форма показывалась поверх, а список просвечивал ниже): добавлен флаг `!mobileForecastAdding` к `v-if` обоих `n-grid-item` секций.
- **Statistics period-фильтр на мобильном** — единая кнопка-триггер с текущим лейблом периода («Май 2026» / «2026» / «12.03 – 15.04»), открывает popover с button-group выбора режима + value-picker'ом (TilePeriodPicker / NDatePicker). На десктопе — прежний inline-ряд кнопок в карточке.

### [1.26.0] — 2026-05-14

#### Changed
- **Доходы / Расходы — карточный список вместо NDataTable на мобильном.** Тесная и невнятная таблица на узком экране заменена на вертикальный список карточек (одна запись на строку). Внутри: аватар автора слева (32px) или bulk-чекбокс, дата + категория в одной строке, источник/назначение и описание в строке `dim`, сумма справа жирным с цветом (`incomeColor` / `expenseColor`) — скрывается blur'ом по тогглу `valuesHidden`. Карточки `hidden=true` притуплены `opacity:0.45` как в desktop-таблице.
- **Тап по карточке → edit-вид** в той же добавочной форме. Поведение мирорит add-flow: тап заменяет «Историю» на форму с pre-fill'ом, в `#header` появляются back-стрелка и заголовок «Изменить доход / расход», submit-кнопка превращается в «Сохранить» (вызывает `store.update` вместо `store.create`); под ней — красная «Удалить запись» с popconfirm. На Income initial-balance карточка скрывается в edit-режиме как посторонний контекст.
- **Долгое нажатие (450 мс) на карточке → bulk-режим** с одновременным выделением этой записи. После long-press последующий `click` подавляется флагом `longPressFired`, чтобы тап заодно не открыл edit. В bulk-режиме обычный тап перекидывается на toggle-select.
- Кнопка «Пакетное редактирование» в шапке списка скрыта на мобильном (точка входа — long-press), на десктопе осталась.
- Пагинация — отдельный `NPagination` под списком карточек (заменяет встроенный paginator таблицы) с `size="small"` и центровкой.

### [1.25.0] — 2026-05-14

#### Changed
- **Statistics summary 2×2 на мобильном**: `n-grid` переведён с `:cols="3"` на `:cols="6"`, карточки Доходы / Расходы — `span="3 m:2"` (1/2 ширины на мобильном, 1/3 на десктопе), Баланс — `span="6 m:2"` (полная строка ниже на мобильном, 1/3 на десктопе). Десктопный вид не изменился.
- **Forecasting на мобильном — 3 табы** (Аналитика / Регулярные / Желания). Под `isMobile` (window<768) рендерится горизонтальная таб-полоса в стиле SettingsTabs.vue. Десктоп показывает все три секции в общем потоке как раньше.
  - Summary 4 → **2×2 на мобильном**: `span="2 m:1"` (½ ширины на мобильном, ¼ на десктопе).
  - На `n-grid-item` для секций «Регулярные» / «Желания» используется `v-if` (не `v-show`) — NGridItem не пропускает v-show на свой root, поэтому при `v-show=false` карточки рендерились пустыми внутри слота. С `v-if` неактивная секция полностью удаляется из vnode-дерева, активная занимает full-width.
  - **Add-record через «+ Добавить» с навигацией**. Форма «Добавить» в Аналитике скрыта на мобильном. В табах Регулярные / Желания над списком появляется кнопка «+ Добавить …» — клик предустанавливает `form.kind` и переключает `mobileForecastAdding=true`: всё прочее (summary, pie, list, табы) скрывается, видна только карточка формы с back-стрелкой в `#header`. Переключатель wishlist/regular в форме скрыт на мобильном (kind уже выбран табой). После submit состояние сбрасывается.
- **Income / Expenses — add-record nav на мобильном**: левая панель SplitPane (форма «Добавить» + Initial balance в Income) по умолчанию скрыта, видна история. Кнопка «+ Добавить доход / расход» поверх истории на мобильном; клик → переключение `mobileAdding=true`, форма появляется с back-стрелкой в `#header` карточки («← Новый доход / расход» внутри одной рамки, мирорит AdminCategoriesView editor). После успешного submit состояние сбрасывается и пользователь возвращается в историю. Десктоп — без изменений, обе панели рядом.

#### Fixed
- **AdminCategoriesView — таб-полоса разделов**: добавлен `gap: 6px` между лейблом и счётчиком, у `n-tag` счётчика увеличен `border-radius` до `8px` (`:deep(.n-tag)`).
- **Back-кнопка в add-/edit-вьюшках в тёмной теме** имела видимый фон от `quaternary`-стиля. Заменена на `<n-button text>` (без фона, без hover-подсветки) в AdminCategoriesView, UsersAdminView, IncomeView, ExpensesView, ForecastingView.

#### Added
- **Управление пользователями** (`/settings/users`, admin) — split-pane `UsersAdminView.vue` поверх `/api/admin/users/*` (api 1.19.0). Слева список пользователей с UserAvatar + бейджами (admin / заблокирован / вы); справа — редактор с полями login, отображаемое имя, чекбоксы «Администратор» / «Заблокирован», загрузка/удаление аватара, кнопка «Изменить пароль». Создание: login + password + display_name + is_admin; аватар доступен после первого сохранения (нужен id). Сортировка списка: свой первым → админы → по имени. Safeguards UI отзеркаливают бэкенд (нельзя снять админку/заблокировать/удалить себя).
- **Модальная смена пароля**: для своей учётки требует старый пароль (через `POST /api/auth/password`), для чужой — только новый + подтверждение (через `POST /api/admin/users/:id/password`). `canSubmitPassword` следит за длиной (≥4) и совпадением «новый/подтверждение».
- **Шапка «Настройки» на мобильном** — `SettingsTabs.vue` (горизонтальные таб-кнопки «Категории» / «Пользователи»), монтируется в App.vue над router-view'ом только при `isMobile && route.path.startsWith('/settings/')`. На десктопе подразделы по-прежнему живут в sidebar-дереве.
- Маршрут `/settings/users` (`adminOnly`) + редирект `/settings` → `/settings/categories`. Mobile bottom-nav «Настройки» по-прежнему открывает `settings/categories`; подсветка вкладки активна на любом `/settings/*` пути (через `mobileActiveKey`).

#### Changed
- Sidebar-дерево: «Категории» → «Управление категориями», добавлен пункт «Управление пользователями».
- **`AdminCategoriesView` — мобильная адаптация**:
  - Раздел (Расходы / Доходы / Желания) на ≤767px рендерится горизонтальной таб-полосой (flex-row, скрытый sections-title, центрированный текст со счётчиком); на десктопе — прежняя вертикальная колонка.
  - List + Editor стали взаимоисключающими: при `editing != null` список и таб-полоса скрываются, на левом краю редактора появляется кнопка «Назад» (`ArrowBackOutline`).
  - Иконка-сетка теперь адаптивная: на десктопе — фиксированные 16 × 2 (как было), на мобильном — `repeat(auto-fill, minmax(44px, 1fr))` чтобы тайлы были tap-friendly.
  - Удалён обманчивый плейсхолдер «Выберите категорию слева» на мобильном — на узких экранах списка нет «слева», показывался путаюший empty-state. Теперь editor-empty виден только на десктопе.
  - Breakpoint media-query'ев унифицирован: 720px → 767px чтобы совпадать с `isMobile = window.innerWidth < 768` (App.vue / SettingsTabs).

### [1.23.0] — 2026-05-13

#### Added
- **Поддержка refresh-токенов** (api 1.18.0). `auth_refresh_token` хранится в localStorage рядом с `auth_token`. Axios response-interceptor ловит 401 на любом protected-запросе, вызывает `/auth/refresh` с сохранённым refresh-токеном, на успехе обновляет оба токена в localStorage + ретраит исходный запрос. Inflight-дедупликация — параллельные 401 шарят один refresh-вызов через `refreshInflight` promise, чтобы не дёргать `/auth/refresh` несколько раз для одного event'а истечения. На неудаче refresh'а (включая истёкший refresh-токен) — wipe localStorage + dispatch `auth:expired` (App перенаправляет на логин). Auth store сохраняет/чистит `refresh_token` в setAuth/logout.

### [1.22.0] — 2026-05-13

#### Changed
- **Icon picker сетка зафиксирована на 16 × 2**: `grid-template-columns: repeat(16, 1fr)` вместо `auto-fill, minmax(56px, 1fr)`. Общее число тайлов жёстко ограничено 32 = «—» + recent + custom + «+». Видимый список recent теперь computed (хранилище ≤30, на показ режется до `30 − customsCount` чтобы загруженные пользовательские иконки всегда помещались в те же 2 ряда).
- **Hidden values в легенде pie chart блюрится, не пропадает**. Раньше `hideMoney` (= ruble+hidden) полностью прятал сумму. Теперь сумма всегда в DOM, в hidden-режиме применяется `filter: blur(6px) + user-select: none` (как у summary cards в Statistics). Layout строки больше не прыгает.
- **Tooltip pie chart в hidden-режиме маскирует значение**: было `{name}` (только лейбл, цифра уезжала). Стало `{name}: •••• ₽` — единица видна, цифры замаскированы, как в monthly bar tooltip.
- **Пакетное редактирование вишлиста**: кнопка «Куплено» убрана (помечать покупку требует prefilled-expense модалки с категорией/датой/юзером — это не пакетная операция). Оставлены «Не куплено» (видна если в выборе есть отмеченные купленными — отвязывает связные расходы через `wlApi.unlinkPeriod()` как single-row flow) и «Удалить».
- **Mobile-навигация получила пункт «Настройки» (только админам)**: 6-я вкладка с `SettingsOutline`, переход напрямую в `/settings/categories`. `mobileNavItems` стал computed.

#### Fixed
- `.admin-shell` высота на мобильной вёрстке: десктоп = `100vh − header − 48px`, мобила = `100vh − header − 96px` (учитывает `padding-bottom: 80px` под bottom-nav вместо обычных 24px). Без этого админка лезла под низ экрана на мобильных.

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

### [1.42.0] — 2026-05-26

#### Added
- **Разделение дохода по депозитам.** В bottom-sheet income-транзакции рядом с pencil — кнопка `CallSplit` («Разделить доход»). Видна только для канонической income-записи (не split-child, не split-parent, без DR). Открывает `SplitIncomeSheet`: stepper «Делить на» (2–10), prefilled-карточки `{amount × DepositSegmented}`, auto-balance последней строки на каждый ввод предыдущих, точная валидация sum (допуск 0.01 ₽). После save — POST `/transactions/:id/split` + `SyncWorker.enqueue` для подтягивания children/parent flip.
- **Filter section «Разделённые»** в IncomeScreen FilterCard — `DepositScopeChip` «Показывать разделённые» (Icons.AutoMirrored.Filled.CallSplit), пробрасывает `include_split=true` через VM/Repo/DAO в новую Room-секцию query.
- **Back-link на split-child** в TransactionDetailSheet — DetailRow «Разделение» с текстом «Часть от записи «…» от DD.MM.YYYY (… ₽)»; если parent скрыт (когда не включён фильтр) — fallback на «исходного дохода».
- **Расформировать** — swipe-delete (или delete-fab) на split-child / split-parent вместо обычного удаления показывает `AlertDialog` «Расформировать разделённый доход?»; positive → `POST /transactions/:id/unsplit` (для child использует `parentId`). После — parent восстановлен, его можно разделить заново.

#### Changed
- **`TransactionDao.observeFiltered`** + `TransactionRepository.observeFiltered` приняли параметр `includeSplit: Boolean = false`. SQL дополнен `$nor`-аналогом на `type='income' AND excluded_from_stats=1 AND parent_id='' AND detail_request_status IS NULL/=''`. Room schema не меняется (новые поля не вводились — переиспользуются `parent_id`/`excluded_from_stats`).
- **`ApiService.getTransactions`** — параметр `include_split: Boolean?`. **`ApiService.splitTransaction(id, SplitRequest)`** и **`ApiService.unsplitTransaction(id)`** — новые endpoints.
- **Бамп min API совместимости**: `ANDROID_MIN_REQUIRED` не поднят — фича online-only, старые клиенты просто не увидят кнопок.

### [1.41.3] — 2026-05-25

#### Changed
- **Рефакторинг карточки фильтров в общий `FilterCard` компонент.** Inline `AnimatedVisibility` + `Card` + «Фильтры»-header + «Всего/Сбросить»-footer был продублирован на Income / Expenses / Statistics / Forecast — каждое изменение шапки/подвала надо было править в 4 местах (из-за чего ранее заголовок «Фильтры» и кнопка «Сбросить» приехали в Stats/Forecast отдельным релизом). Вынесли в `components/FilterCard.kt`: shell владеет анимацией, header'ом и опциональным footer'ом; экраны передают только свои `FilterSection`'ы и набор fields для активности (`hasActiveFilters` + `onReset`). Размеры файлов экранов: Income −36 строк, Expenses −34 строки, Statistics −13 строк, Forecast −10 строк. Вся визуальная поверхность идентична 1.41.2.

#### Added
- **Кнопка «Сбросить» теперь и на Stats / Forecast** (паритет с Income / Expenses) — справа в footer'е карточки, видна только когда активен deposit-фильтр. Долгое нажатие на funnel в TopAppBar продолжает работать как раньше.

### [1.41.2] — 2026-05-25

#### Fixed
- **Скачок высоты Row «Всего: N / Сбросить»** при появлении кнопки сброса — `TextButton` имеет вшитый `min-height: 48dp`, после которого высота строки уезжала. Заменили на `Text` с `Modifier.clickable` + 8/2 dp padding. Размер ряда теперь стабильный независимо от того, активны ли фильтры.

#### Changed
- **FilterSection**: gap title↔content 6→4 dp — заголовки групп («Период», «Категории», «Счёт», «Параметры отображения») сидят теперь плотнее к своим чипам.
- **Stats / Forecast**: добавлен заголовок «Фильтры» (titleSmall + SemiBold) в карточку — паритет с Income / Expenses. Padding карточек выровнены к Income / Expenses (14×12 dp).

### [1.41.1] — 2026-05-25

#### Added
- **Кнопка «Сбросить» в карточке фильтров** Income/Expenses — справа от «Всего: N», видна только когда хотя бы один фильтр активен (категории / период / счёт / «Закрытые запросы»).
- **Long-press на funnel в TopAppBar** теперь сбрасывает все фильтры текущего экрана. Income/Expenses вызывают свою VM напрямую (shared instance); Statistics/Forecast — через `resetTrigger: Int` counter-prop, который MainScreen инкрементирует, а экраны слушают через `LaunchedEffect`. Long-press сопровождается haptic feedback. Сам funnel IconButton переведён на `Modifier.combinedClickable` (M3 IconButton не expose'ит onLongClick).
- В соответствующие VM добавлены методы `resetFilters()` (Income / Expenses / Statistics / Forecast).

#### Changed
- **Унифицированы заголовки карточек.** Карточка фильтров получила заголовок «Фильтры» в стиле `titleSmall + SemiBold`. IB-карточка (Income) перешла с центрированного `labelMedium` на левостороннее `titleSmall + SemiBold` — теперь стиль одинаков с «Лимит расходов».

### [1.41.0] — 2026-05-25

#### Changed
- **Унификация фильтров.** Income / Expenses / Statistics / Forecast теперь показывают фильтр одним способом — карточка с группами `FilterSection(title)` + LazyRow чипов. Заголовки групп слева сверху над каждым рядом, чипы единого стиля, активный = primary-фон + белая иконка.
- **Income / Expenses фильтр переписан** под chip-row подход:
  - **«Период»** — `PeriodChipsRow` (Месяц / Год / Период с пиктом-pickerами и DateRangeDialog). Активный chip отображает выбранное значение («Май 2026», «2026», «01.05 – 31.05»); тап по активному сбрасывает фильтр. Заменяет `DateRangePickerField` (большой outlined input).
  - **«Категории»** — `CategoryChipsRow` (multi-select c [`Все`] первой, иконка + название). Тап «Все» сбрасывает выбор; тап любой категории deactivates «Все» и activates её; накопительный выбор. Заменяет `CategoryFilterField` (dropdown + chip-pills).
  - **«Счёт»** — тот же deposit-chip LazyRow.
  - **Expenses**: «Параметры отображения» (чекбокс «Показать закрытые запросы») вынесен в свою FilterSection.
- **Statistics / Forecast**: chip-rows обёрнуты в FilterSection с заголовками «Период» / «Счёт».
- **Новые компоненты**: `components/FilterSection.kt`, `components/PeriodChipsRow.kt`, `components/CategoryChipsRow.kt`. `IncomeViewModel.selectIbMonth(year, month)` теперь принимает произвольный месяц.

### [1.40.6] — 2026-05-25

#### Changed
- **IB header polish**: title «Баланс на начало месяца» центрирован по карточке; gap между Calendar и Edit увеличен (4→12 dp), общий spacing между title-блоком и action-row 8→16 dp — выглядит менее зажато.
- **IB модалка под тёмной темой**: `TabRow.containerColor = Color.Transparent`, чтобы он наследовал `surfaceColorAtElevation` ModalBottomSheet'а вместо более тёмного `colorScheme.surface`.

### [1.40.5] — 2026-05-25

#### Changed
- **IB header (Доходы)**: иконки выбора месяца и редактирования теперь лежат горизонтально, обе tinted в primary. Маленький label месяца под иконками убран — он дублировал данные popup'а. Row вертикально центрирована по карточке.

#### Fixed
- **HorizontalPager перехватывал inner LazyRow scroll.** Default `pageNestedScrollConnection` я уже занулил в 1.40.4, но это только покрывает fling-propagation. Прямой pointer drag по `Modifier.scrollable` внутри пейджера всё ещё ловил unconsumed delta когда inner LazyRow дошёл до края. Добавлен `LocalInnerHorizontalScroll` CompositionLocal: каждая LazyRow с `TrackInnerHorizontalScroll(state)` публикует `state.isScrollInProgress` в общую `MutableState<Boolean>`, MainScreen передаёт `userScrollEnabled = !innerHScrolling.value` в `HorizontalPager`. Теперь свайп страниц замораживается пока пользователь активно прокручивает чипы фильтра.

### [1.40.4] — 2026-05-25

#### Changed
- **IB header**: компактная компоновка. Title + per-deposit balance rows слева; справа компактная Column с `CalendarMonth`-иконкой (открывает `TilePeriodPickerPopup` для выбора месяца) + `Edit`-иконкой (открывает sheet с табами) + крошечный label выбранного месяца под иконками. Раньше month-navigator `‹ Май 2026 ›` стоял отдельной строкой между title и amounts — дублировал control space с кнопкой «Изменить» и не масштабировался когда добавились два scope-row'а. Добавлен `IncomeViewModel.selectIbMonth(year, month)` для произвольного выбора (не только prev/next).
- **Income/Expenses фильтр**: deposit-чипы Row → LazyRow. Длинные label'ы («Банковская карта») горизонтально скроллятся вместо обрезки.

#### Fixed
- **HorizontalPager nested-scroll**: при горизонтальном свайпе внутри LazyRow (фильтр-чипы Stats/Forecast/Income/Expenses) после конца LazyRow pager инерционно листал на соседний экран. Дефолтный `PagerDefaults.pageNestedScrollConnection(Orientation.Horizontal)` забирает unconsumed scroll same-orientation детей. Заменили на пустой `NestedScrollConnection` — pager теперь слушает только прямые жесты на своём viewport'е.

### [1.40.3] — 2026-05-25

#### Changed
- **Statistics: period+deposit оба под funnel-toggle.** Раньше period-card висел всегда, deposit прятался под toggle. Теперь обе строки чипов лежат внутри одной AnimatedVisibility(filtersVisible) — экран по дефолту показывает summary cards сразу под TopAppBar. Чипы перевели на LazyRow, длинные label'ы (Банковская карта / Наличные) горизонтально скроллятся вместо обрезки в «Нали...».
- **Forecast: «Регулярные расходы» heading + empty-state.** Раньше заголовок и список рендерились только при regular.isNotEmpty(); на cash-scope без рег.расходов секции вообще не было видно — пользователь видел только пустой wishlist и не понимал что регулярных нет. Теперь heading всегда виден, при пустом списке — placeholder «Регулярных расходов нет». Deposit-чипы тоже в LazyRow.

#### Added
- **IncomeScreen: IB для двух счетов с tab-модалкой** (паритет с web 1.40.0).
  - `IncomeViewModel.ibByDeposit: StateFlow<Map<String, Transaction>>` — newest record per scope (collapse через `tx.deposit.ifBlank { "bank" }`).
  - Карточка-шапка показывает обе суммы рядом со своими иконками; «Не задан» для отсутствующего scope; eye-toggled mode заменяет цифры цветным placeholder'ом.
  - Sheet с TabRow (Карта / Наличные); per-tab amount string'и держатся в `mutableStateMapOf` — переключение табов сохраняет ин-флайт правки; на Save callback получает `Map<deposit, Double?>` и upserts только реально изменившиеся записи (untouched tabs не бампают updated_at).

### [1.40.2] — 2026-05-25

#### Added
- **Фильтр «Счёт» в Stats/Forecast** — карточка чипов теперь сворачивается под общий funnel-toggle (`FilterAlt` в TopAppBar), который раньше был только для Доходов/Расходов. AnimatedVisibility(expand/shrink) синхронизирована с `filtersVisible`. activeFilterCount badge учитывает deposit-фильтр на Доходах/Расходах.
- **Фильтр «Счёт» в Доходах/Расходах** — отдельная Row под Category/DateRange в drawer'е фильтров с теми же чипами Все / Карта / Наличные, что и в Stats.

#### Changed
- **`DepositScopeChip` компонент** — единая реализация FilterChip-а с `maxLines=1+softWrap=false+TextOverflow.Ellipsis` (фикс переноса «Налич\nные» на узких экранах) и явным белым tint иконки на selected (раньше Material3-default оставлял её тёмной поверх primary-фона).

#### Fixed
- **Forecast: regulars/wishlist при фильтре «Наличные»** — `forecast.regularItems?.takeIf { isNotEmpty }` слишком grubo фолбэчился к synthesize при пустом server-result (легитимный сценарий: scope без рег. расходов). Теперь fallback только когда `forecast == null` (offline). `wishlistOneOff` и synthesize-fallback дополнительно фильтруются по `filterDeposit` локально, чтобы wishlist карточки не лезли наружу из чужого scope.

### [1.40.1] — 2026-05-25

#### Changed / Fixed
- **StatisticsScreen / ForecastScreen**: deposit-фильтр (Все / Карта / Наличные) перенесён внутрь карточки period selector'а (Stats) и в собственную Card-карточку (Forecast) — раньше чипы лежали отдельной свободной строкой и визуально не группировались с периодом. Активный чип красится в primary-цвет (как period-chip).
- **TopAppBar overflow**: title Text получил `maxLines=1, softWrap=false, overflow=Ellipsis`, Crossfade обёрнут `Modifier.weight(1f, fill=false)`. На route'е `expenses` дата `today` скрывается, чтобы 4-иконочный action-row не давил title до per-letter обрезки.
- **Forecast crash на фильтре `Наличные`**: добавлен defensive guard в `ForecastViewModel.fetchForecast()` — `breakdown / regularItems / unpurchasedWishlist` приходящие из JSON как `null` (Gson не консультирует kotlin-дефолты при reflection-десериализации) заменяются на `emptyList()`. Связан с фиксом api 1.25.1.

### [1.40.0] — 2026-05-24

#### Added
- **Deposit scope (банковская карта / наличные).** Поле `deposit` (`bank`/`cash`, default `bank`) добавлено в `Transaction`/`WishlistItem`/`CreateTransactionRequest`/`UpdateTransactionRequest`/`CreateWishlistRequest`/`UpdateWishlistRequest`/`RegularItem` (`data/model/Models.kt`) и в Room (`TransactionEntity`/`WishlistEntity`, `Mappers.kt`).
- **Room v7 migration** (`MIGRATION_6_7`): `ALTER TABLE transactions ADD COLUMN deposit TEXT NOT NULL DEFAULT 'bank'` + то же для `wishlist`.
- **DAO/Repo фильтрация.** `TransactionDao.observeFiltered(deposit=?)` + `TransactionRepository.observeFiltered(deposit=…)`. `create()`/`update()` теперь принимают и нормализуют `deposit` (`ifBlank { "bank" }`).
- **Retrofit API params.** `?deposit=` на `getTransactions` / `getStatsSummary` / `getByCategory` / `getMonthlyStats` / `getForecast` / `getStatisticsOverview`.
- **ViewModels.** `IncomeViewModel` / `ExpensesViewModel` получили `_filterDeposit` + `setFilterDeposit()`; `StatisticsViewModel.selectDeposit()`; `ForecastViewModel.setFilterDeposit()` + `fetchForecast(deposit)`.
- **UI компоненты.** `components/DepositChip.kt` (read-only иконка либо editable + DropdownMenu) и `DepositSegmented` (`FilterChip` пара) — переиспользуются в формах и tx-карточках.
- **Экраны:**
  - `IncomeScreen` / `ExpensesScreen`: в add/edit-sheet добавлен `DepositSegmented`. На tx-карточке слева от категории — read-only `DepositChip`. Initial balance sheet принимает «Счёт» через `vm.saveInitialBalance(amount, deposit)`.
  - `ForecastScreen`: фильтр-чипы «Все/Карта/Нал» над summary'ями; в `AddWishlistSheet` и в edit-mode wishlist — `DepositSegmented`.
  - `StatisticsScreen`: фильтр-чипы «Все/Карта/Нал» под period selector'ом — прокидываются через `vm.selectDeposit()` во все аггрегации `/statistics/overview`.

### [1.39.0] — 2026-05-24

#### Added
- **Баннер «Доступно обновление приложения» на экране входа (страховка от lock-out).** После успешной проверки `/api/health` и перехода ко второму шагу `ConnectScreen` клиент дёргает `/api/version` и, если установленная версия меньше `ANDROID_LATEST`, под формой логина/пароля и кнопкой «Войти» появляется кликабельный баннер с предложением установить актуальную версию. Тап запускает тот же flow, что и баннер обновления в `MainScreen` (`ApkDownloader.download` → `ApkInstaller.install`), включая открытие настроек «Установка из неизвестных источников» при отсутствии permission'a. Если сервер репортит `ANDROID_MIN_REQUIRED > current`, рендерится fullscreen `MandatoryUpdateDialog` — пользователь может обновиться без прохождения авторизации. Версия перечитывается на каждый `Lifecycle.ON_RESUME`, чтобы новые билды на сервере появлялись без рестарта приложения. Мотивация: пользователь со старой версией клиента и протухшим refresh-token'ом не может попасть в `MainScreen`, где живёт основной апдейтер, — если новая версия чинит баг входа, такой клиент остаётся залоченным без ручной заливки APK по ADB. Теперь обновление доступно в любой момент после установления коннекта с сервером.

### [1.38.5] — 2026-05-21

#### Fixed
- **Пустой Wishlist + «исчезающие» новые записи на больших базах (15k+ транзакций).** `SyncEngine.pull()` мержил коллекции в порядке `transactions → wishlist → categories`. Цикл по 15k+ транзакциям выполняется десятки секунд, а Room через Flow прогрессивно пушит результат в UI — создавалась иллюзия завершённой синхронизации, хотя wishlist и хвост transactions ещё не доехали. Симптомы на свежем устройстве: список желаний пустой, недавно созданная через web-фронт запись не появляется, в Прогнозе не открывается детализация регулярного расхода (child-транзакция ещё не смержена). Перепорядочил: wishlist → categories → transactions (последние — чанками по 500). Wishlist и категории появляются за секунды.
- **Прогресс-бар «сбрасывался на 0» посередине синка.** `SyncWorker.enqueue` использовал `ExistingWorkPolicy.REPLACE`, и любой триггер во время идущего pull'a (CRUD-мутация, `NetworkObserver`, screen-resume reachability) убивал текущий worker. Частичный pull не успевал сохранить `lastSyncToken` (он обновляется только в самом конце), и новый worker стартовал заново с `since=0` — пользователь видел сброс баннера с «5500 / 23187» обратно на ноль. Перевёл на `KEEP` + добавил `Mutex.tryLock()` в `SyncEngine.sync()` (повторные триггеры теперь возвращают `Skipped` без сброса прогресса). Pending-мутации сидят в Room с `PENDING_*` флагами, поэтому пропуск дубль-enqueue безопасен — текущий sync их разгребёт на push-фазе либо следующий periodic-tick.
- **Двусоставный прогресс-бар.** `LinearProgressIndicator` в M3 1.x рисует gap между активным и inactive треком (segmented look лимитов). Перевёл на `gapSize = 0.dp` + `drawStopIndicator = {}` — теперь классический overlay (активная полоса поверх трека), как в `Лимит расходов`.
- **Фоновая синхронизация не работала при свёрнутом приложении.** `SyncWorker` запускался как обычный background worker и убивался OS на длинном first-sync'е (>10 минут в фоне = вылет по budget). Добавил `setForeground(SyncNotificationPusher.foregroundInfo)` в начало `doWork` — promotes до foreground service, тот же progress-notification работает как FGS-якорь. Manifest: `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` permissions + override `<service android:name="androidx.work.impl.foreground.SystemForegroundService" foregroundServiceType="dataSync">` (требование Android 14+ при использовании типизированного `FOREGROUND_SERVICE_TYPE_DATA_SYNC`).
- **`POST_NOTIFICATIONS` periodically auto-revoke (Android 13+)**: OS снимает permission, если приложение долго не открывали, — у пользователя слетали уведомления в системе. Запрос permission'a перенесён из ленивого тригера (toggle любого уведомления в настройках) в `MainActivity` cold-start: после login'a, если permission не выдан, диалог появляется сразу. Запрашиваем на каждом cold-start (`granted` — идемпотентно, deny — single deny на сессию).
- **Мерцание sync-баннера на любом взаимодействии.** После каждого CRUD/тапа/свайпа `SyncWorker` запускался для incremental-pull'а (push 0-1 op + fetch 0-5 рядов, всё за <300ms). `SyncProgressBus.state` мгновенно эмитил `Running` → баннер мигал на долю секунды → дёргался layout. Аналогично системная нотификация флешилась в шторке + `setForeground` промоутил воркер в FGS на эти 300ms. Добавил `SyncProgressBus.visibleState` — `transformLatest`-debounce: `Running` форвардится сразу только если `total > 500` (большой first-sync), иначе через 600ms — следующий эмит (Done) cancels delay и баннер никогда не появляется. Баннер в `MainScreen` и `SyncNotificationPusher.observeWhile` оба читают `visibleState`. `SyncWorker.doWork` также откладывает `setForeground` до первого Running через гейт — fast incremental sync не получает foreground-promotion и не флешит нотификацию.

#### Added
- **Глобальный индикатор синхронизации.** Новый `SyncProgress` StateFlow в `data/sync/` (`Idle`/`Running(phase, processed, total)`/`Done`/`Failed`); `SyncEngine` эмитит прогресс по фазам (`PUSH`/`FETCH`/`WISHLIST`/`CATEGORIES`/`TRANSACTIONS`/`POST`). В UI — `SyncProgressBanner` (mirror `UpdateBanner`-стиля) поверх pager'а в `MainScreen`: иконка, подзаголовок «Записи: 3214 / 15823», determinate-progressbar для фаз с известным total. Тики на transactions — раз на чанк (~30 апдейтов на 15k), на wishlist/categories — каждые 25 рядов при total > 50 (короткие списки не фликерят).
- **Системная нотификация прогресса.** Отдельный low-importance канал `budget_sync` (no sound, no peek, no badge), ongoing-уведомление с `setProgress(total, processed, false)`. `SyncNotificationPusher.observeWhile` подписывается на `SyncProgressBus` из `SyncWorker.doWork`; на любом терминальном состоянии (Done/Failed/Idle) или завершении worker'a — уведомление снимается. Пользователь видит прогресс в шторке, даже свернув приложение, и тап возвращает в `MainActivity`.
- **Periodic background sync.** `SyncWorker.enqueuePeriodic` — `PeriodicWorkRequest` с интервалом 15 минут (минимум WorkManager) + `NetworkType.CONNECTED` constraint + KEEP policy. Стартует на cold-start `MainActivity`, когда есть `serverUrl + authToken`. Отменяется вместе с one-shot unique-work на explicit logout. До этого изменения синк полагался только на ручные триггеры (CRUD/`NetworkObserver`/screen-resume) — пользователь, не открывавший приложение часами, получал stale-данные.

#### Changed
- **`OkHttp`-таймауты на retrofit-клиенте**: `readTimeout` 20s → 60s, `callTimeout` 30s → 180s. Старые значения тригерились на медленном Wi-Fi во время `/api/sync/pull` крупной семейной базы (ответ 15-25 MB сериализуется одним JSON'ом). 180s — потолок для honest first-sync; следующие incremental-pull'ы тривиальны.

### [1.38.4] — 2026-05-20

#### Added
- **Tests.** Unit-тест `LimitsProgressRepositoryTest` (Robolectric + MockWebServer): 200 → state заполняется (`period`/`total_limit`/`total_spent`), 5xx → stale-snapshot сохраняется, `clear()` обнуляет state.

#### Changed
- **JaCoCo coverage-excludes (`app/build.gradle`).** Из метрики исключены пакеты `data/api/**` (Retrofit-интерфейсы — нечего тестировать в unit, мокаются в repo-тестах) и `data/update/**` (in-app updater на AlarmManager + DownloadManager — реалистично тестируется только на устройстве через instrumentation). Compose UI и Activity entry points исключены ранее. См. `docs/E2E_PLAN.md` — Compose UI tests в `androidTest/` запланированы как Phase C.

### [1.38.3] — 2026-05-20

#### Fixed
- **Дубликаты категорий после переключения сервера.** Логаут не чистил Room, поэтому при logout → switch server → login на другом инстансе старые UUID-категории с прошлого сервера оставались рядом со свежепринятыми (`SyncEngine` мерджит по `_id`, конфликта нет → две записи «Жильё/ЖКХ»). Та же беда для transactions/wishlist/notifications. Добавил `AppContainer.wipeUserData()`: cancel pending `SyncWorker` → `db.clearAllTables()` → `prefs.clearLastSyncToken()`. Вызывается в `onLogout` ПЕРЕД `clearAuthAndSecurity()` (избежать окна с stale-данными и уже-снятым auth-token'ом). На 401-bounce не вызываем — это не явный logout, пользователь скорее всего вернётся на тот же сервер. Device-локальные настройки (тема, история URL'ов, PIN) не трогаем.
- `SyncWorker.cancel(context)` — публичный hook для отмены unique-work `msdnna_budget_sync`. Без него уже-в-очереди worker мог стартовать после wipe и неудачно дёрнуть сервер на промежуточном состоянии.

### [1.38.2] — 2026-05-19

#### Fixed
- **Аватар в `SettingsDialog`** — заголовочная карточка пользователя (со «Вы авторизованы») рисовала hand-rolled `Box+Text` с инициалами и не знала про `avatar_url`. Перевёл на общий `UserAvatar(displayName, avatarUrl, 36.dp)`, прокинул `avatarUrl` через `MainScreen → SettingsDialog`. Резолвинг URL — через тот же `RetrofitClient.serverRoot`, что и для строк транзакций (1.38.1).

### [1.38.1] — 2026-05-19

#### Fixed
- **Аватары пользователей теперь отображаются** (не только инициалы). Backend отдаёт `avatar_url` как относительный путь `/api/users/<id>/avatar?v=<ts>` — `<img>` в веб-клиенте поглощает page origin, но Coil `AsyncImage` не умеет резолвить URL без host'а и молча падал в onError, оставляя цветной фон с инициалами. `UserAvatar` теперь резолвит относительные `/...`-пути через новый `RetrofitClient.serverRoot` (текущий base URL без `/api/`), полностью-квалифицированные URL передаются как есть. Bearer-токен на запрос уже подкладывал Coil-`OkHttp`-interceptor в `BudgetApplication.newImageLoader`.

### [1.38.0] — 2026-05-19

#### Added
- **Привязка существующего расхода к wishlist/regular-итему.** Новый overlay-экран `LinkExpenseScreen.kt` (slide+fade поверх MainScreen, аналогично `DetailRequestsScreen`) показывает список не связанных расходов (`getTransactions(unlinked=true)`, лимит 100) с поиском по назначению/категории/сумме. Каждая строка — двухтаповый конфирм (первый tap красит amber «Подтвердить?», второй — `POST wishlist/:id/link/:tx_id`). На обоих swipe-картах (`SwipeableRegularItemCard` + `SwipeableWishlistCard`) добавлен синий action «Привязать» (цвет `0xFF2080F0`) рядом с «Удалить»; для wishlist скрывается когда `purchased=true`. После успешного линка хост-экран дёргает `CategoryRepository.loadAll()` (на случай если сервер клонировал категорию в expense) и `SyncWorker.enqueue()` (чтобы только что привязанная транзакция корректно ушла в Room через следующий pull).
- `ApiService.linkWishlistToExpense(id, txId)` + параметр `unlinked: Boolean? = null` у `getTransactions(...)`.

### [1.37.4] — 2026-05-16

#### Fixed
- **Прогресс-бар лимита в pie-легенде больше не «съезжает» при скрытии сумм.** Заменил `AnimatedVisibility(shrinkHorizontally)` на `Modifier.alpha(animatedFloat)` — текст остаётся в layout (его слот резервирован), просто становится прозрачным. Bar's `weight(1f)` теперь стабилен, правый край каждой строки на своём месте независимо от `valuesHidden`.
- **Счётчик активных фильтров учитывает «Показать закрытые запросы»** на Расходах. Раньше включал только `filterCats.size + (dateRange ? 1 : 0)`. Теперь для expenses-таргета: `+ (includeDetailed ? 1 : 0)` — `expensesVm.includeDetailed` пробрасывается тем же `collectAsStateWithLifecycle`-паттерном, что остальные filter-флоу. У income аналогичного тогла нет.
- **Скрыт hairline-просвет рельса под скруглением свайпабельной карточки во время свайпа.** Корень: при переходе corner-radius из 12dp → 0dp карточка проходит промежуточные значения (8dp, 5dp, …), и закруглённый угол вырезает часть карточки, через которую просвечивает уже частично-открытый rail. Решение: ×6 множитель на `swipeProgress` — `((offsetX / revealPx) * 6f).coerceIn(0,1)`. Угол достигает 0dp в первых ~17% свайпа, до того как rail успевает показаться существенной полосой. Дальше до конца свайпа угол остаётся плоским — артефакт стартового окна минимизирован ниже перцептивного порога. Применено ко всем трём (`SwipeableTransactionCard`, `SwipeableRegularItemCard`, `SwipeableWishlistCard`).

### [1.37.3] — 2026-05-16

#### Fixed
- **Bell-badge на тёмной теме** — мелкий "2" на красном фоне читался как серый из-за того, что MD3 `colorScheme.error` в dark-теме разрешается в более тёмно-розовый, а 10sp белого текста с SemiBold-весом по нему антиалиасился до неразличимого. Захардкожен яркий `Color(0xFFEF4444)` для фона + `FontWeight.Bold` (как у соседнего DR-badge с оранжевым `0xFFF0A020`).
- **LimitsSummaryCard hidden state** больше не выбивается из общего стиля — раньше показывал `•••• / •••• ₽` placeholder-текстом; теперь, как и в `SummaryCard`, — `Crossfade` на тинтированный Box-плашку (`Modifier.height(22.dp).width(140.dp).background(tint.copy(alpha=0.22f))`). Анимация 220ms.
- **Суммы лимитов в pie-легенде** скрываются при `valuesHidden`. Текст `"%.0f ₽"` обёрнут в `AnimatedVisibility(enter=fadeIn+expandHorizontally, exit=fadeOut+shrinkHorizontally)`; прогресс-бар внутри Row через `weight(1f)` плавно расширяется вправо когда текст уходит — мимика что текст "уезжает". Идея подсказана юзером.

#### Added
- **Счётчик активных фильтров на иконке в TopAppBar** — Box-badge в стиле bell/DR-бэйджей (Box + `align(TopEnd).offset((-4).dp, 6.dp)` чтобы не клиппиться TopAppBar'ом). Источник: `expensesVm.filterCats.size + (filterFrom!=null && filterTo!=null ? 1 : 0)` (аналогично для income). Цвет — `primaryColor` (отличается от error-bell, чтобы не сливался). Состояние пробрасывается из расшаренных VM (MainScreen уже использует тот же `viewModel(key=)`-инстанс, что Income/Expenses-экраны).

#### Changed
- **Свайпабельные карточки выпрямляют угол на стороне открытого action-rail'а.** Все 3 (`SwipeableTransactionCard`, `SwipeableRegularItemCard`, `SwipeableWishlistCard`) теперь считают `cardShape` из `offsetX.value`: при right-swipe (`offsetX > 0`) `topStart/bottomStart` → 0dp пропорционально прогрессу, `topEnd/bottomEnd` остаются 12dp. При left-swipe — наоборот. Карточка визуально "стыкуется" с rail-панелью вместо круглого зазора. Зеркалит фронт.

### [1.37.2] — 2026-05-15

#### Changed
- **Упрощён лимит-бар в `ChartLegend`** — справа от бара теперь только сам лимит (например, «50 000 ₽») вместо «spent/limit (percent%)». Потраченная сумма уже стоит на строке выше (это `slice.value`), а процент визуализирован самим прогресс-баром — обе цифры дублировались. Высота бара возвращена к 5dp (с 7dp), top-padding limit-row убран — компактнее по вертикали, теперь карточка с большим количеством категорий лучше помещается на экран.
- **Удалены `formatLegendMoney` и `LEGEND_NUMBER_FORMAT`** — стали dead code после упрощения форматирования (новый текст идёт через `"%.0f ₽".format(limit)`).

#### Added
- **`@Preview StatisticsDonutsPreview()` в `Charts.kt`** — две карточки Расходов/Доходов как на экране Статистики, рендерятся в Light + Dark в Android Studio. `DonutChart` детектит `LocalInspectionMode.current` и скипает sweep-анимацию (анимационный clock в превью не тикает; без этого слайсы остались бы свёрнутыми на 0).
- **Compose preview tooling**: `androidx.compose.ui:ui-tooling-preview` (release) + `androidx.compose.ui:ui-tooling` (debug-only) через compose-BOM. `@Preview CategoryLabelPreview()` в `CategoryLabel.kt` как шаблон для остальных leaf-композаблов.

### [1.37.1] — 2026-05-15

#### Fixed
- **Custom-SVG категории теперь тинтятся в цвет категории** (а не остаются прозрачными альфа-глифами, которые были невидимы на светлой теме). `CategoryLabel` навешивает `ColorFilter.tint(resolvedColor)` на `AsyncImage` так же, как built-in vector'ы получают `Icon(tint = …)`. Для типичных мono-glyph SVG (Wildberries / OZON / Магнит) это даёт тот же визуальный вес, что у встроенных иконок. Замечание: multi-colour логотип тинт сплющит — админам надо выбирать built-in key для таких случаев.
- **Custom-SVG больше не выпрыгивают по размеру** относительно built-in иконок в строках/dropdown'ах. Раньше `Modifier.size(computedSize * iconScale)` множил размер на `Category.iconScale` (значение для legend-badge'а 28dp×18dp — обычно 1.0–2.0). Без бейджа этот множитель распирал иконку шире текста. Теперь size зафиксирован на `computedSize` (равен размеру текста), `ContentScale.Fit` сохраняет aspect-ratio non-square SVG.

#### Added
- **CategoryLabel распространён на ForecastScreen** (последнее место, где он отсутствовал по предыдущему апдейту):
  - `SwipeableRegularItemCard` — «Регулярные расходы», sub-line «<категория> · <частота>» теперь начинается с иконки.
  - `SwipeableWishlistCard` — «Список желаний», аналогично.
  - `WishlistInteractiveSheet` — row «Категория» в view-mode рендерит CategoryLabel через новый параметр `valueContent` у `WishlistDetailRow`; edit-mode dropdown — стандартный CategoryLabel в каждой строке.
  - `AddWishlistSheet` (модалка «Добавить регулярный расход» / «Желаемая покупка») — dropdown категорий.
  - «Прогноз по категориям» breakdown rows — иконка слева от названия категории. Резолв сначала через `expenseCategories` (выводимый прогноз — это в основном расходы), фолбэк на `categories` (wishlist superset).

#### Changed
- `WishlistDetailRow(label, value, valueContent: (@Composable () -> Unit)? = null)` — необязательный slot для значения, чтобы «Категория»-row рендерила CategoryLabel вместо plain Text. Остальные вызовы (Периодичность / Статус / Заметки) продолжают работать на старом сигнатурном варианте.
- `SwipeableRegularItemCard`, `SwipeableWishlistCard`, `WishlistInteractiveSheet`, `AddWishlistSheet` приняли `categories` (где не было) + `serverUrl: String = ""` с дефолтами для обратной совместимости.

### [1.37.0] — 2026-05-15

#### Added
- **Иконка категории рядом с названием во всех списочных местах** — без бейджа с фоном, чистый глиф/SVG. Built-in vector'ы тинтятся в цвет категории (`resolveCategoryColor(name, color)`); custom-загрузки рендерятся в своих естественных цветах (логотипы вроде OZON / Wildberries / Магнит — тинт бы их размылил). Размер иконки автоматически подгоняется под размер текста названия (`style.fontSize.toDp()` для sp-юнитов; иначе 14dp дефолт). Покрыты все четыре места из ТЗ:
  - **AddExpenseSheet / AddIncomeSheet** — dropdown-пункты выбора категории при создании/редактировании записи.
  - **CategoryFilterField** — чекбокс-список фильтра по категориям (между checkbox'ом и названием).
  - **SwipeableTransactionCard** — строка списка транзакций (перед названием категории рядом с датой).
  - **TransactionDetailSheet** — bottom-sheet деталей записи: view-mode (под суммой) + edit-mode dropdown.
- **Новый общий компонент `ui/components/CategoryLabel.kt`** + публичный хелпер `categoryIconUrl(serverUrl, iconKey)` (выносил из приватной функции StatisticsScreen — теперь переиспользуется). API: `CategoryLabel(name, category, serverUrl, style, fontWeight, textColor, iconSize?, spacing, maxLines, overflow)`. `category=null` → плавный fallback на plain `Text` (для транзакций со снятой/удалённой категорией). Нет иконки в `Category.icon` → коллапсирует icon-слот, текст flush-left (никаких phantom-gap'ов).

#### Changed
- **Расширены сигнатуры shared-компонентов**:
  - `SwipeableTransactionCard(..., categories: List<Category> = emptyList(), serverUrl: String = "")` — defaults на пустые для обратной совместимости; вызовы из IncomeScreen/ExpensesScreen прокидывают свои `categories`/`serverUrl`.
  - `TransactionDetailSheet(..., serverUrl: String = "")` — добавлен между `categories` и `onAddCategory`.
  - `AddExpenseSheet(..., serverUrl: String = "")` / `AddIncomeSheet(..., serverUrl: String = "")` — добавлен после `categories`. Все 5 вызовов (Income/Expenses/Forecast×2/DetailRequest) пробрасывают свой `serverUrl`.
  - `CategoryFilterField(..., serverUrl: String = "")` — для рендера custom-иконок в фильтре.

### [1.36.1] — 2026-05-15

#### Fixed
- **Clockwise-sweep анимация вернулась на старт приложения и pull-to-refresh.** В 1.36.0 per-slice morph съел "часовое" появление чарта — entries растили radial от 0 даже при свежей загрузке. Теперь `CategoryDonut` пробрасывает в `DonutChart` `freshDataKey = remember(allSlices) { Any() }` — ref меняется только когда родитель прислал новый allSlices (refresh / period switch / cold start), но НЕ при legend-toggle (тогда меняется только `hidden`, не `allSlices`). На смену `freshDataKey` `DonutChart` snap'ает entries к таргет-значениям и анимирует отдельный `sweepProgress: Animatable 0→1`, который в Canvas-draw обрезает каждый слайс до `maxAngleFromStart = 360 × progress` — получается тот самый clockwise reveal. На toggle/`Прочее`-раскрытие путь остаётся per-slice morph. Иконки на слайсах ждут, пока sweep пересечёт их midline; клики игнорируются пока `sweepProgress < 1f`.
- **Скрытая через легенду категория при повторном показе возвращается на свою позицию** (а не в конец чарта). Алгоритм sync entries в morph-режиме переписан с двух фаз "update existing + append new" на единый merge: `targetEntries` строится в порядке `slices` с переиспользованием существующих `AnimSlice` по label; затем walk через `oldOrder` interleave'ит target-entries (в target-порядке) с exiting-entries (которые остаются на своих исходных позициях). Re-shown-категория попадает на свой target-индекс независимо от того, успело ли её предыдущее `animateTo(0)` доехать до конца (если не успело — reuse того же `Animatable`, его текущая coroutine отменяется при re-key LaunchedEffect, новый `animateTo(targetValue)` плавно реверсит из промежуточного значения).

### [1.36.0] — 2026-05-15

#### Added
- **Тап по доли pie-чарта → переход в "Расходы"/"Доходы" с фильтром по категории.** `CategoryDonut` принимает `onCategoryDrilldown`; `DonutChart` хит-тестит тап через `pointerInput { detectTapGestures }`, нормализует угол относительно -90° (12 часов) и находит слайс по cumulative-sum текущих анимированных значений. Из `StatisticsScreen` пробрасываются `onDrilldownExpense`/`onDrilldownIncome` с парой (fromIso, toIso) — период транслируется в календарные границы (MONTH → 1-е / последний день, YEAR → 01-01 / 12-31, RANGE → как есть). `MainScreen` дёргает `setFilterCategories` + `setDateRange` на расшаренном ExpensesViewModel/IncomeViewModel (тот же `viewModel(key=)`, что используют сами экраны), скроллит pager на нужную страницу и взводит `statsDrilldownTarget`. Системная кнопка "Назад" в этом режиме (`BackHandler(enabled = statsDrilldownTarget != null && currentPage == target)`) очищает фильтр + дату и возвращает на Статистику. Если пользователь сам ушёл свайпом/тапом на другую вкладку, флаг сбрасывается (`LaunchedEffect(pagerState.settledPage)`) — "came-from-stats"-семантика больше не действует. Outer-BackHandler регистрируется первым, чтобы внутренние per-page-хендлеры (selection-mode на Расходах) получали приоритет.
- **Тап по "Прочее" → раскрытие сгруппированных категорий.** `PieSlice` получил поле `groupedLabels: List<String>` (заполняется только в `groupSmallSlices` для synthetic-wedge). При клике по "Прочее" `CategoryDonut` программно добавляет все *не-сгруппированные* (крупные) метки в `hidden` → маленькие категории перестают сворачиваться, "Прочее" исчезает, и каждая открывается как полноразмерный wedge с собственной иконкой; дальнейший тап ведёт в Расходы по той же логике.

#### Changed
- **Анимация pie-чарта переписана с clockwise-sweep на per-slice morph.** Раньше при любом изменении `slices` (тоггл в легенде, обновление данных, смена периода) использовался единый `Animatable` 0..1, умножавшийся на sweep — получался одинаковый "часовой" реверс. Теперь `DonutChart` ведёт `mutableStateListOf<AnimSlice>` (label + Animatable<Float> + exiting-flag): при обновлении входа существующие entries `animateTo(newValue)` плавно интерполируют свои углы (соседи "раздвигаются", скрываемая доля схлопывается), новые entries растут от 0, исчезающие — анимируются до 0 и удаляются из списка. Иконки на слайсах позиционируются по текущим анимированным углам (двигаются вместе со слайсом) и фейдятся через порог `MIN_ICON_PCT` (не "поп"-в/из-видимости при росте/схлопывании). Зеркалит поведение ECharts pie-update в web `CategoryDonutChart.vue`.
- **Выравнивание прогресс-бара лимита в `ChartLegend`.** Высота бара 5dp → 7dp, `top = 2.dp` → `top = 4.dp` в limit-row; так колонка с текстом+баром визуально весит достаточно, чтобы 28dp-бейдж читался по-настоящему центрированным относительно всего item'а (тонкий бар смещал восприятие центра вверх к строке с названием+суммой).

### [1.35.4] — 2026-05-15

#### Fixed
- **Pie-легенда `ChartLegend`**: иконка категории центрируется по вертикали всего item'а (badge — sibling новой content-колонки, не вложен в main-row), прогресс-бар теперь сидит вплотную к основной строке (`top = 2.dp` вместо `4.dp` + убран `start = 36.dp` отступ — бар живёт внутри content-колонки и сам сдвинут на ширину badge). Зеркалит фикс CategoryLimitsScreen / web-CategoryDonutChart.

### [1.35.3] — 2026-05-15

#### Added
- **Прогресс-бар лимита в легенде pie-чарта** (StatisticsScreen, expense-донат). Только для категорий, где у админа задан `monthly_limit`. Под основной строкой легенды (badge + название + сумма) появляется компактный ряд: бар `weight(1f)` начинается под названием категории (offset 36.dp = badge 28.dp + row gap 8.dp), справа от бара — текст `<spent>/<limit> (<percent>%)`. Цвет бара green→amber→red на 80/100% — одинаковые пороги во всех точках: ExpensesScreen «Лимит расходов», CategoryLimitsScreen, теперь и в pie-легенде. `PieSlice` получил поля `limitTotal: Double?` + `limitSpent: Double` + `limitPercent: Double`; `StatisticsScreen` подтягивает `LimitsProgressRepository.state` и матчит по `category.name`. Income-донат лимиты не получает (бэкенд их там не считает).

### [1.35.2] — 2026-05-15

#### Fixed
- **Тоглы лимит-алёртов в `NotificationsScreen`** выглядят как соседние reminder-тоглы — `checkedThumbColor = primaryColor` + `checkedTrackColor = primaryColor.copy(alpha = 0.4f)`. Раньше у новых тогглов был solid-track (без alpha), и они читались как другой контрол на той же странице.

### [1.35.1] — 2026-05-15

#### Fixed
- **«Без лимита» в `CategoryLimitsScreen`** теперь читается на обоих темах — переключён с `colorScheme.outline` (placeholder-уровень контраста) на `colorScheme.onSurfaceVariant`.
- **Кастомные SVG-иконки рендерятся** в карточках `CategoryLimitsScreen` — раньше `CategoryBadge` смотрел только в `categoryIcon(key)` и отдавал пустой бейдж для `custom:<id>`. Теперь использует тот же путь, что и легенда pie-чарта: `parseCustomIconKey` → `AsyncImage` (Coil, через общий `BudgetApplication` ImageLoader с auth-интерсептором), масштаб через `iconScale`.
- **Дата в `NotificationsHistoryScreen`** — `outline` → `onSurfaceVariant` (та же логика, что и «Без лимита»).
- **Прогресс-бар стал цельным**, без визуального шва между filled/track половинами. Новый `OverlayProgress` (`ui/components/OverlayProgress.kt`) — простой `Box` с округлённым треком + наложенный сверху `Box` для filled-части; `LinearProgressIndicator` (M3) рисовал обе половины со своими округлёнными торцами и оставлял заметную «прорезь» в центре даже с `gapSize = 0.dp`. Применён в карточке «Лимит расходов» (ExpensesScreen) и в карточках `CategoryLimitsScreen`.

### [1.35.0] — 2026-05-15

#### Added
- **Phase 5 — Лимиты на категории + история уведомлений** (api 1.20.0). Полный читай/пиши UI и системные push'и поверх свежего backend'а.
- **Data layer**:
  - `Category.monthlyLimit: Double?` в DTO + `CategoryEntity.monthly_limit REAL` (nullable: `null` = лимит не задан; различает от 0₽-лимита). Room migration v5→v6 (`ALTER TABLE categories ADD COLUMN monthly_limit REAL`) + новая таблица `notification_history` (id, server_id, type, period, category_id/name, limit, spent, title, body, created_at, read_local, pushed_at). Маппинг round-trip покрыт `MappersTest`.
  - `LoginResponse.isAdmin` парсится + сохраняется в DataStore (`IS_ADMIN` ключ); экспонируется как `prefs.isAdmin: Flow<Boolean>`. Backfill через `/auth/me` тоже забирает флаг.
  - `LimitsProgressRepository` (in-memory `StateFlow`) — обёртка над `GET /api/categories/limits-progress`. Не персистится в Room: значения — серверная агрегация за текущий месяц, повторять её офлайн избыточно. Cached snapshot остаётся при оффлайне, read-only.
  - `NotificationHistoryRepository` — единый feed для bell-popover'а из двух источников: server-pulled limit-уведомления (через `/api/notifications`) + локальные reminder-фейринги (`NotificationReceiver` пишет ряд после каждого AlarmManager-фейринга). Dedup по `server_id`; per-user read-state через `read_local` + POST `/notifications/read-all`.
  - `CategoryRepository.patchMonthlyLimit` — admin-only PATCH с ручной сборкой JSON (literal `null` для очистки лимита, обходит default-Gson, который дропает null в map'ах). Online-only; offline-edits недоступны.
- **Sync hook**: `SyncEngine.postPullRefresh()` после каждого pull дёргает `LimitsProgressRepository.refresh` + `NotificationHistoryRepository.refreshFromServer`; новые история-row'ы передаются в `LocalAlertPusher.pushNewIfAllowed` (системный push через `NotificationManager`, с учётом per-toggle prefs + дедуп по `pushed_at`).
- **UI**:
  - **`ExpensesScreen`** — карточка «Лимит расходов» над списком записей (mirror «Начальный баланс» в `IncomeScreen`). Read-only progress bar + сумма / лимит + %; зелёный→amber→red на 80/100%. Плашка «тек. месяц» появляется когда фильтр истории выходит за рамки текущего календарного месяца (лимит-окно всегда фиксировано). Тап карточки для админа → `CategoryLimitsScreen`; для не-админа карточка не кликабельная.
  - **`CategoryLimitsScreen`** (новый, admin-only overlay) — карточный список expense-категорий с иконкой/цветом + текущим лимитом справа. Тап по карточке → `ModalBottomSheet` с `OutlinedTextField` (Decimal keyboard) + кнопками «Снять лимит» (если задан) и «Сохранить». Прогресс-бар под карточкой дублирует тинт суммарной карточки.
  - **`NotificationBell` в `MainScreen` TopAppBar** — глобальная иконка-колокольчик с red-bage счётчиком непрочитанных (9+ для >9). Тап → `NotificationsHistoryScreen` overlay со списком notification_history (newest-first), цветные иконки по типу (PriorityHigh для global, WarningAmber для category, NotificationsActive для reminder'ов). Кнопка «Прочитать все» в TopAppBar диалога. Unread-фон 18% errorContainer на каждой непрочитанной строке.
  - **`NotificationsScreen`** — две новые toggle-row над reminder-секцией: «Превышение лимита по категории» и «Превышение общего лимита». Дефолт обе включены. При активации запрашивается permission notify (как для reminders).
- **`NotificationReceiver`** теперь пишет ряд в `notification_history` (type=`expenses_reminder`/`income_reminder`, `pushed_at = createdAt` сразу, т.к. система получила push в тот же момент). Bell-история смешивает их с server-side limit-алёртами в одном feed'е.
- **Tests**: `MappersTest.\`category roundtrip preserves monthly_limit incl null\`` гарантирует, что миграция и маппинг не теряют новое поле.

### [1.34.0] — 2026-05-13

#### Added
- **Поддержка refresh-токенов** (api 1.18.0). `LoginResponse.refresh_token` парсится и сохраняется в `AppPreferences` рядом с access-токеном (`REFRESH_TOKEN` ключ DataStore). OkHttp-интерсептор `RetrofitClient` ловит 401 на любом protected-запросе, синхронно вызывает `tryRefresh()` (отдельный OkHttp-клиент без auth-interceptor чтобы не было рекурсии), на успехе сохраняет новую пару в `RetrofitClient.authToken`/`refreshToken` + вызывает `onTokensRefreshed` callback (MainActivity пишет в DataStore через `AppPreferences.setTokens`). Concurrent 401-ы шарят один refresh-вызов через `synchronized(refreshLock)`. На неудаче — чистит токены и вызывает `onUnauthorized` (как раньше). Кнопка logout + clearAuth также чистят оба токена. `ConnectScreen.onAuthenticated` сигнатура расширена `refreshToken: String`.

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
