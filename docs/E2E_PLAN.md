# E2E test roadmap

Текущее unit-покрытие закрывает «бизнес-логику» (utility, store, handler, repo) — но
визуально-интерактивные компоненты и flow'ы, проходящие через несколько слоёв стека,
тестируются хрупко или вообще не тестируются. Этот документ фиксирует, что именно
было исключено из unit-метрики и под какой e2e-инструмент оно планируется.

## Что исключено из unit-coverage (и почему)

### Web (`frontend/vitest.config.js`)

```js
exclude: [
  ...
  // Уже e2e-территория:
  'src/components/CategoryDonutChart.vue',  // SVG donut + drilldown анимация
  'src/components/SwipeableCard.vue',       // touch-gesture рейлы
  'src/components/SetupWizard.vue',         // multi-step wizard первого запуска
],
```

* `CategoryDonutChart` — сложная SVG-анимация со sweep/morph, slot-preserving merge,
  slice-tap → drilldown. Unit-тест на VNode shape бесполезен (визуальная регрессия не
  ловится), а на canvas/DOM — хрупкий.
* `SwipeableCard` — drag-rail с touch-событиями, opacity-фейдами, click-suppression
  после touch. Жесты в `happy-dom` не дотягивают до реального поведения.
* `SetupWizard` — first-run flow (3 шага + создание admin'а + POST `/setup/init`),
  смысл в нём только end-to-end на свежей БД.
* `views/**` — все экраны (уже исключены ранее). Это контейнеры, реальный smoke лучше делать в e2e.

### Backend (`Makefile :: COVER_PKGS`)

* `cmd/*` (seed_loadtest, migrate, create_user) — CLI-утилиты, прогоняются вручную через make-таргеты.
* `handlers/export.go` (Excel/PDF) — XLSX/PDF golden-tests планируем как e2e, чтобы
  проверять реальные форматы файлов на конкретные значения. Чистые helpers
  (`cellName`, `txTypeLabel`, `truncate`) уже покрыты unit'ами.
* `handlers/icons.go` (multipart upload + filesystem serve) — multipart + IO,
  e2e проверит на реальном файле.
* `main.go` — wiring, валидируется через build + smoke в CI.
* `internal/mongotest` — сами тестовые хелперы.

### Android (`android/app/build.gradle :: coverageExcludes`)

* `data/api/**` — Retrofit interfaces (только аннотации, нечего тестировать в unit;
  мокаются через MockWebServer в repo-тестах).
* `data/update/**` — in-app updater (AlarmManager + DownloadManager + content-provider),
  реалистично тестируется только на устройстве через instrumentation.
* `ui/**` — Compose UI, требует `androidTest/`.
* `MainActivity*`, `notifications/**` — Android entry points / system push handlers.

## E2E план — по компонентам

### 1. Backend — `backend/internal/e2e_test.go` (highest ROI)

**Стек**: уже есть `testcontainers-go` (`internal/mongotest`). Достаточно поднять весь
HTTP stack через `httptest.NewServer(routes.Setup(...))` поверх контейнера Mongo.

**Что покрывает**:
* Полный JWT-flow: register/login → access+refresh → 401 → /auth/refresh → retry.
* CRUD-сценарии для каждой коллекции с реальными правами и валидацией.
* Sync push+pull round-trip (offline-режим Android-клиента).
* Detail-requests flow (create → AddChild → Close → дочерние tx появляются в stats).
* Wishlist link-existing + unlink-period.
* XLSX/PDF export — открыть результат через xlsx/pdf-парсер, проверить ячейки/строки.
* Icons upload + serve (multipart, проверка sniffed mime).
* Setup wizard (`/setup/init`) — на свежей БД допускается; на заполненной — 403.
* Import/export round-trip (JSON snapshot v1).

**Ориентир покрытия**: +15-20% к backend total (доберёт `cmd/migrate` логику через
прогон фактической миграции на seed-БД, если включить в e2e).

### 2. Web — Playwright

**Стек**: `frontend/e2e/` + `playwright.config.js`. Запускается:

```bash
docker compose up -d mongodb backend           # реальный backend + Mongo (testcontainers альтернатива — Mongo Docker)
cd frontend && yarn build && yarn preview      # SPA на 4173
yarn playwright test                            # headless хром на 4173 → /api → backend
```

CI: `.github/workflows/release-web.yml` уже билдит — добавить `e2e` job, который поднимает
backend в сервис-контейнере и гоняет Playwright.

**Сценарии** (5-7 спецификаций — реалистичный объём для первой итерации):

| Спека | Что покрывает |
|---|---|
| `auth.spec.js` | login → /income → reload → авто-login через refresh-token; logout |
| `transactions.spec.js` | создать расход → проверить в /expenses; inline-edit amount; swipe-delete |
| `donut.spec.js` | /statistics → donut виден, slice-tap уводит в /expenses?category=... с подсветкой |
| `wishlist.spec.js` | создать wishlist-item → «Куплено» → подтверждение модала → tx в /expenses |
| `setup.spec.js` | свежая БД → wizard видим → создание admin'а → /login без ошибок |
| `export.spec.js` | /settings/portability → скачать JSON → его же импорт → 200 |
| `notifications.spec.js` | админ ставит limit → создаёт расход → bell получает badge |

**Ориентир покрытия**: задача e2e — не поднять % unit-coverage, а отсечь регрессии в
`views/**`/`CategoryDonutChart.vue`/`SwipeableCard.vue`/`SetupWizard.vue`. Поэтому
**не** запускаем web-coverage с `playwright/v8 instrumented` — это другая воронка.

### 3. Android — два варианта (выбираем после первой итерации web/backend)

#### Вариант A: Compose UI tests (`androidTest/`)
Самый канонический путь, но дорогой в CI.

**Стек**: `androidx.compose.ui:ui-test-junit4` + `androidx.compose.ui:ui-test-manifest`
+ `androidx.test.ext:junit` + Hilt-free DI (AppContainer уже singleton).
В тесте инициализируется `AppContainer.setup(context)` с тестовым Room (in-memory)
и MockWebServer вместо backend.

**Сценарии** (5-7 экранов):

| Тест | Что покрывает |
|---|---|
| `LoginScreenTest` | поля логина → tap Войти → переход в /income |
| `IncomeScreenTest` | список загружается → tap «+» → создаётся запись → видна в списке |
| `ExpensesScreenTest` | swipe-удаление → undo snackbar |
| `StatisticsDonutTest` | tap slice → переход в /expenses с фильтром |
| `BulkSelectTest` | long-press → multi-select → bulk-delete confirm |
| `SettingsAppLockTest` | включение PIN → бан-доступ → разблокировка |
| `OfflineSyncTest` | airplane-mode toggle → запись в offline → unlock → sync push |

**CI стоимость**: эмулятор Android-API 34 через `reactivecircus/android-emulator-runner`
на GH Actions ubuntu-runner — ~5-7 минут на каждый прогон, нужен KVM (доступен только
на linux runners с `--privileged`). Альтернатива: Firebase Test Lab — платная.

#### Вариант B: Maestro (YAML-flow)
Проще CI: один процесс `maestro test flows/login.yaml` против реального эмулятора.

**Сценарии**: те же 5-7, но flow-уровневые (tap → assertVisible). Coverage не собирает
(нет JaCoCo agent), зато падает заметно быстрее на регрессиях UX.

**Когда выбирать**: Maestro — для smoke в CI; Compose UI tests — для критических flow'ов
(auth, sync, app-lock) с асёртами на состояние.

## Что НЕ делаем в e2e

* **Performance бенчмарки** — отдельный набор `androidx.benchmark` / Lighthouse.
* **Visual regression (скриншоты)** — отложено до отдельного запроса; Playwright
  поддерживает, но порог false-positive высокий на компактах.
* **Mutation testing** — overkill для проекта такого размера.

## Roadmap

1. **Phase A (текущий заход — DONE)**:
    * Восстановление unit-coverage до >80% web, ~65% backend, исключение e2e-территории.
    * Документ (этот файл).

2. **Phase B (следующий заход)**:
    * Backend `internal/e2e_test.go` с реальными сценариями — главный ROI.
    * Web Playwright: 5-7 спецификаций + GH Actions job.

3. **Phase C (после B)**:
    * Android Compose UI tests: 3-5 критичных экранов + AppContainer test-harness.
    * Если CI-эмуляторы окажутся болезненными — переключение на Maestro в CI и Compose UI
      tests только локально.
