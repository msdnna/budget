# Contributing

Документ описывает рабочий процесс: ветки, коммиты, версионирование и сборка релизов. Если что-то расходится с реальностью — правьте этот файл в том же PR.

## Состав репозитория и независимое версионирование

В репозитории три **независимо версионируемых** компонента, каждый со своим файлом `VERSION` (semver, MAJOR.MINOR.PATCH):

| Компонент | Файл версии | Где используется |
|-----------|-------------|------------------|
| Backend (Go API) | `backend/VERSION` | embed → `appVersion` → `GET /api/version` |
| Web (Vue SPA) | `frontend/VERSION` | информационно (попап в шапке); собирается в bundle |
| Android | `android/VERSION` | читается в `app/build.gradle` → `versionName`, `versionCode = MAJOR*10000 + MINOR*100 + PATCH`; раздаётся через `/api/version` (`ANDROID_LATEST` / `ANDROID_MIN_REQUIRED`) |

Версии **не синхронизируются** между компонентами — каждая бампается только когда меняется именно этот компонент.

## Когда бампать версию

**Бампать на каждое содержательное изменение, попадающее в репозиторий**, по правилам [semver](https://semver.org/lang/ru/):

| Изменение | Какой bump |
|-----------|-----------|
| Багфикс, не меняющий API/UI/контракт | `patch` |
| Новая фича, обратно совместимая | `minor` |
| Несовместимое изменение API/контракта/схемы данных | `major` |

Примеры:

- Добавили новый эндпоинт `/api/foo` → `bump-api BUMP=minor`.
- Поправили багу в расчёте статистики, ответ тот же → `bump-api BUMP=patch`.
- Удалили или переименовали поле в ответе API → `bump-api BUMP=major`. Если это ломает Android-клиент, **также** поднимите `ANDROID_MIN_REQUIRED` в `.env` / `docker-compose*.yml` до текущей версии Android-клиента, поддерживающего новый контракт.
- Поменяли цвет в теме — `bump-web BUMP=patch`.
- Добавили офлайн-синхронизацию в Android — `bump-android BUMP=minor`.

Не бампайте версию для коммитов вида: документация, форматирование, refactor без поведенческих изменений, изменения CI / dev-инфраструктуры. Используйте префиксы `docs:` / `chore:` / `refactor:` (см. ниже) — они идут без bump'а.

## Команды для бампа

```bash
make bump-api     BUMP=patch|minor|major
make bump-web     BUMP=patch|minor|major
make bump-android BUMP=patch|minor|major
make version      # посмотреть текущие
```

`make bump-android` дополнительно:

- пересчитывает `versionCode` для `app/build.gradle`;
- правит дефолт `ANDROID_LATEST` в `docker-compose.yml` и `docker-compose.prod.yml`. Чтобы клиенты увидели новую версию через `/api/version`, нужно **пересобрать backend** (`make up` для dev / `make prod-build && make prod-up` для prod).

`ANDROID_MIN_REQUIRED` бампается **вручную** только при breaking-изменениях API.

## Стиль коммитов (Conventional Commits)

Каждый коммит — одна логическая единица. Формат:

```
<type>(<scope>): <subject>

[optional body]

[optional footer, e.g. BREAKING CHANGE: ...]
```

### Типы

| Тип | Когда использовать | Влияние на версию |
|-----|-------------------|---------------------|
| `feat` | Новая возможность | `minor` |
| `fix` | Багфикс | `patch` |
| `perf` | Оптимизация без новой фичи | `patch` |
| `refactor` | Перестройка кода без изменения поведения | без bump |
| `docs` | Только документация | без bump |
| `test` | Только тесты | без bump |
| `chore` | Сборка, зависимости, dev-инструменты | без bump |
| `build` | Сборочный pipeline / Dockerfile / Makefile | обычно без bump |
| `style` | Форматирование, без логики | без bump |

### Скоупы

Используйте скоуп компонента: `backend`, `web`, `android`, `prod`, `infra`, `release`. Если изменение задевает несколько компонентов — допускается опустить скоуп или указать самый «крупный».

### Breaking changes

Несовместимые изменения помечайте одним из двух способов (или обоими):

```
feat(backend)!: rename /api/transactions/list to /api/transactions

BREAKING CHANGE: клиенты до v1.12.0 перестанут работать; ANDROID_MIN_REQUIRED поднят до 1.12.0.
```

`!` после скоупа и/или футер `BREAKING CHANGE:` → обязательный `major`-bump соответствующего компонента.

### Примеры хороших сообщений

```
feat(android): добавить дискавери сервера через сканирование подсети
fix(backend): корректно считать initial_balance в balance, исключив из income-stats
chore(release): bump android 1.15.0 → 1.15.1
docs: описать процедуру восстановления MongoDB из дампа
feat(web)!: переехать с moment.js на dayjs

BREAKING CHANGE: формат сохранённых фильтров изменился
```

## Рабочий процесс

### Ветки

- `main` — стабильная ветка, ровно она деплоится в продакшен.
- `develop` — интеграционная, сюда летят фичи.
- `feat/*`, `fix/*`, `chore/*` — feature-ветки от `develop`.

Релиз = merge `develop` → `main` (или fast-forward). Прямые коммиты в `main` запрещены, кроме hotfix.

### Цикл фичи

1. Ответвиться от `develop`: `git switch -c feat/server-discovery`.
2. Делать атомарные коммиты с conventional-сообщениями.
3. **Перед последним коммитом** в фиче — выполнить bump соответствующего компонента: `make bump-android BUMP=minor`. Версия в `VERSION` идёт **тем же** PR.
4. Описать изменения в `CHANGELOG.md` под секцией `## [Unreleased]` соответствующего компонента (см. `CHANGELOG.md` — формат [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/)).
5. Открыть PR в `develop`. Описание PR = краткая версия changelog-секции.
6. После merge'а — переместить запись из `[Unreleased]` в новую секцию `## [<service>-<version>] — <YYYY-MM-DD>`.

### Тэги

Тэги пушатся **только из `main`** после merge'а релизной ветки и имеют формат:

```
api/v<major>.<minor>.<patch>
web/v<major>.<minor>.<patch>
android/v<major>.<minor>.<patch>
```

Создание тэга:
```bash
git switch main
git pull
git tag -a api/v1.7.0 -m "api 1.7.0"
git push origin api/v1.7.0
```

В одном merge может появиться несколько тэгов, если бампнулись несколько компонентов.

## Перед каждым коммитом — чек-лист

- [ ] `VERSION` соответствующего компонента бампнут (если изменение содержательное).
- [ ] Запись в `CHANGELOG.md` под `[Unreleased]`.
- [ ] Сообщение коммита в Conventional Commits.
- [ ] Не закоммитили локальные конфиги (`.env`, `android/local.env`, `local.properties`), бинарники из `cmd/*` (`backend/migrate`, `backend/seed_loadtest`), кеши (`build/`, `.gradle/`, `node_modules/`, `frontend/.yarn/cache/`), APK (`apks/*.apk`, `android/*.apk`).
- [ ] **Quality gate затронутого компонента** зелёный: `make lint-{component}` + `make test-{component}` (см. ниже). Если изменение задело несколько компонентов — для каждого. Чисто инфраструктурные правки (docs / `.gitignore` / CI yaml без кода) гейт пропускают.
- [ ] Сборка проходит:
  ```bash
  make build-backend       # go build → backend/bin/budget-go
  make build-frontend      # vite build → frontend/dist/
  cd android && ./gradlew :app:assembleDebug
  # либо: make android       # debug APK через build.sh
  ```

## Линтинг

Все три компонента покрыты статическим анализом и форматированием. **Lint должен быть чистым (0 предупреждений)** — `--max-warnings=0` для web, `golangci-lint run` без exceptions для backend, ktlint + detekt без issues для Android. Lint-проблемы фиксим, а не глушим конфигом (исключения — только для известного false-positive с обоснованием в комментарии).

```bash
make lint            # Агрегированный HTML-отчёт по всем компонентам → reports/lint.html
make lint-backend    # gofmt -l + go vet ./... + golangci-lint v2 (govet/errcheck/staticcheck/
                     # ineffassign/unused/bodyclose/gocritic/revive/misspell/unconvert/prealloc)
make lint-web        # ESLint 10 flat config (--max-warnings=0) + Prettier 3 --check
                     # cd frontend && corepack yarn lint:fix     # авто-фикс ESLint
                     # cd frontend && corepack yarn format       # авто-фикс Prettier
make lint-android    # ktlint 1.6 (через JavaExec) + detekt 1.23 — оба через ./gradlew
make format-android  # ktlintFormat — авто-форматирование Kotlin
```

Конфиги:
- `backend/.golangci.yml` — golangci-lint v2 (с overrides для test-файлов).
- `frontend/eslint.config.js` — flat config (`@eslint/js` + `eslint-plugin-vue` flat/recommended + `eslint-config-prettier`).
- `frontend/.prettierrc.json` — singleQuote, no-semi, printWidth 100, trailing-comma all, arrow-parens always.
- `android/config/detekt/detekt.yml`, `android/.editorconfig` (ktlint).

## Тестирование

Тесты — обязательны для нового кода (минимум — happy-path юнит-тест). Без зелёного `make test-{component}` коммит не уходит.

```bash
make test                       # Агрегированный HTML-отчёт → reports/test.html (status + durations + coverage%)
make test-backend               # Go unit, -race (testcontainers integration не запускает)
make test-backend-cover         # + coverage → backend/cover.html
make test-backend-integration   # build-tag integration (нужен Docker для testcontainers)
make test-web                   # Vitest 4 + happy-dom 20 + @vue/test-utils + axios-mock-adapter
make test-web-cover             # + coverage → frontend/coverage/index.html
make test-android               # JUnit 4 + Robolectric + MockWebServer + Turbine
make test-android-cover         # + JaCoCo → android/app/build/reports/jacoco/jacocoTestReport/html/index.html
```

Где что лежит:
- **Backend**: `*_test.go` рядом с прод-кодом; общая инфраструктура для интеграционных тестов — `backend/internal/mongotest` (`sync.Once`-контейнер + per-test БД через `dbCounter`). Интеграция автоматически скипается, если Docker недоступен или передан `-short`.
- **Web**: `frontend/tests/` и `frontend/src/**/__tests__/`. Стабы Naive UI — по Vue-внутреннему `name:` (`Popover`, **не** `NPopover`), это легко словить как «component not stubbed».
- **Android**: `android/app/src/test/` (Robolectric для Android-зависимых классов, чистый JUnit для логики). HTTP — через MockWebServer, корутины — Turbine. JaCoCo подключён через `:app:jacocoTestReport`.

### Покрытие

Цели — *расти со временем*, без жёсткого минимума на PR. Текущий baseline:

| Компонент | Coverage (line) | Заметки |
|-----------|-----------------|---------|
| Backend | ~51% | handlers + repository + middleware + helpers |
| Web | ~62% | 88% на Pinia stores, 85% на utility-компонентах, 76% на `api/index.js` |
| Android | ~19% | security 60%, discovery 36%, db 25%, sync 15% — UI/Compose не покрыт |

Регрессия coverage на PR — допустима, если объяснена в описании (например, добавили большой UI-блок без юнит-тестов). Полностью пропускать тесты для нового нетривиального кода — нет.

### Агрегированный отчёт

`make lint` и `make test` собирают единый HTML-отчёт в `reports/` через `tools/aggregate-reports.py` (статус / длительность / coverage% с прогресс-барами по каждому компоненту). Удобно гонять локально перед PR или цеплять артефактом в CI.

## Локальная среда

`.env` и `android/local.env` — **гитингорятся**. Дефолтные значения для них лежат в `.env.example` и `android/local.env.example`. Если добавляете новую переменную — отразите её и в `*.example` файле, и в README.

### Сборка через прокси

Если ваша сеть требует HTTP-прокси для скачивания зависимостей (Go modules, Yarn/npm-registry, Gradle, Docker, corepack):

- Backend / frontend (Docker): задайте `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` в `.env` — они пробрасываются как build-args в Dockerfile.
- Android Gradle: задайте `SOCKS_PROXY_HOST` / `SOCKS_PROXY_PORT` в `android/local.env` — `build.sh` пробросит их в JVM.

## Релизы и Android-обновления

Распространение Android-сборок устроено через сам сервер (in-app updater):

1. `make bump-android BUMP=...` (это правит `ANDROID_LATEST`-дефолты в compose).
2. `make android-release` — собрать подписанный APK.
3. Положить APK в `./apks/` (он же том `/usr/share/nginx/html/apks/` в nginx-фронте).
4. Пересобрать backend: `make up` (dev) или `make prod-build && make prod-up` (prod).
5. Существующие установки получат уведомление об обновлении при следующем `Lifecycle.RESUME`.

Для критических обновлений: одновременно поднять `ANDROID_MIN_REQUIRED` в `.env` (или прямо в compose). Тогда клиенты ниже этой версии получат **обязательный** диалог обновления, не закрываемый по «отмена».

## Безопасность

- `JWT_SECRET` в проде — обязательно случайные 32+ байта (`openssl rand -hex 32`).
- MongoDB в проде слушает только внутреннюю Docker-сеть — не пробрасывайте порт наружу.
- Регистрация через UI отсутствует by design. Пользователи создаются только `make create_user`.
- Не коммитьте `.env`, `local.env`, `local.properties`, keystore, любые токены и подобное. Для пробивки прокси/IP-адресов используйте `*.example`-файлы.
