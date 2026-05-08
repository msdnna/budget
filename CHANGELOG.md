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
