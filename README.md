# Семейный бюджет

| Компонент | Lint + Test | Build | Coverage | Release |
|-----------|-------------|-------|----------|---------|
| Backend (Go)     | [![lint+test](https://img.shields.io/github/actions/workflow/status/msdnna/budget/ci.yml?branch=main&jobName=Backend&label=)](https://github.com/msdnna/budget/actions/workflows/ci.yml) | [![build](https://img.shields.io/github/actions/workflow/status/msdnna/budget/ci.yml?branch=main&jobName=Backend%20build&label=)](https://github.com/msdnna/budget/actions/workflows/ci.yml) | [![coverage](https://codecov.io/gh/msdnna/budget/branch/main/graph/badge.svg?flag=backend)](https://codecov.io/gh/msdnna/budget) | [![release](https://img.shields.io/github/actions/workflow/status/msdnna/budget/release-api.yml?label=)](https://github.com/msdnna/budget/actions/workflows/release-api.yml) |
| Web (Vue 3)      | [![lint+test](https://img.shields.io/github/actions/workflow/status/msdnna/budget/ci.yml?branch=main&jobName=Web&label=)](https://github.com/msdnna/budget/actions/workflows/ci.yml) | [![build](https://img.shields.io/github/actions/workflow/status/msdnna/budget/ci.yml?branch=main&jobName=Web%20build&label=)](https://github.com/msdnna/budget/actions/workflows/ci.yml) | [![coverage](https://codecov.io/gh/msdnna/budget/branch/main/graph/badge.svg?flag=web)](https://codecov.io/gh/msdnna/budget) | [![release](https://img.shields.io/github/actions/workflow/status/msdnna/budget/release-web.yml?label=)](https://github.com/msdnna/budget/actions/workflows/release-web.yml) |
| Android (Kotlin) | [![lint+test](https://img.shields.io/github/actions/workflow/status/msdnna/budget/ci.yml?branch=main&jobName=Android&label=)](https://github.com/msdnna/budget/actions/workflows/ci.yml) | [![build](https://img.shields.io/github/actions/workflow/status/msdnna/budget/ci.yml?branch=main&jobName=Android%20build&label=)](https://github.com/msdnna/budget/actions/workflows/ci.yml) | [![coverage](https://codecov.io/gh/msdnna/budget/branch/main/graph/badge.svg?flag=android)](https://codecov.io/gh/msdnna/budget) | [![release](https://img.shields.io/github/actions/workflow/status/msdnna/budget/release-android.yml?label=)](https://github.com/msdnna/budget/actions/workflows/release-android.yml) |

Приложение для совместного учёта семейного бюджета: доходы, расходы, лист желаний, статистика, прогнозы и экспорт. Монорепозиторий из трёх независимо версионируемых компонентов: Go-бэкенд, Vue 3 фронтенд и нативное Android-приложение с офлайн-режимом.

## Возможности

### Финансовый учёт
- **Доходы и расходы** с произвольными категориями, источниками/целями, описанием
- **Начальный баланс** — отдельный тип транзакции, учитывающийся в балансе, но исключённый из статистики доходов
- **Список желаний** с собственными категориями
- **Множественный выбор** на любом списке (long-press на Android, чекбоксы на вебе) для массового удаления
- **Inline-редактирование** записей по полям (карандаш → инпут) без модальных окон
- **Переназначение автора** записи (`created_by`) включая записи без автора
- **Скрытие сумм** в одно нажатие — глобальный тумблер в шапке (CSS-blur на вебе, сплошные плашки на Android)

### Аналитика и экспорт
- **Статистика** по месяцам, категориям, общий обзор (`/api/statistics/overview` — bulk-эндпоинт)
- **Период** статистики — двусторонняя синхронизация: tile-picker дат ⇄ перетаскивание по графику (ECharts brush)
- **Прогноз** трат и доходов на ближайшие месяцы
- **Экспорт** в Excel и PDF (PDF — с DejaVu Sans, поддерживает кириллицу)

### Аутентификация и пользователи
- JWT (HS256, TTL 24 ч); регистрация через UI не предусмотрена
- Создание пользователей через CLI: `make create_user USER_LOGIN=... USER_PASSWORD=... USER_NAME=...`
- В каждой записи проставляется `created_by` / `last_modified_by` (id, имя, аватар)

### Android-приложение
- **Native UI** на Jetpack Compose, Material 3, светлая/тёмная тема
- **Офлайн-режим** с двусторонней синхронизацией: Room + WorkManager + `/api/sync/pull|push` с разрешением конфликтов через UI
- **In-app обновления** — баннер + обязательный диалог при критическом обновлении; APK загружается с сервера (`/apks/<file>.apk`)
- **App lock** — PIN (PBKDF2) + биометрия, тайм-аут блокировки, восстановление паролем
- **Server discovery** — сканирование подсети по `/api/health` для автоопределения сервера
- **Уведомления-напоминания** — daily / weekly / monthly / quarterly с picker дней и времени
- **Pull-to-refresh** + skeleton-загрузка во всех списках

### Веб-интерфейс
- Vue 3 + Naive UI, светлая/тёмная тема (свой `tokens.js` + CSS-переменные)
- Tile-grid period picker (4×3 month/year)
- Per-view stores транзакций (фильтры/пагинация изолированы между Income и Expenses)

### Версионирование
- У каждого сервиса свой `VERSION` (см. `backend/VERSION`, `frontend/VERSION`, `android/VERSION`)
- Бэкенд отдаёт текущие версии и минимально допустимую версию Android-клиента через `GET /api/version`
- Бамп — через `make bump-api|bump-web|bump-android BUMP=patch|minor|major` (см. [CONTRIBUTING.md](CONTRIBUTING.md))

## Стек технологий

| Компонент | Технологии |
|-----------|------------|
| Бэкенд | Go 1.25, Gin, MongoDB 8, JWT (HS256), errgroup, embed; golangci-lint v2, testcontainers-go |
| Фронтенд | Vue 3, Pinia, Naive UI, ECharts, Vite, Nginx; Node 24 LTS + Yarn 4 (через corepack); ESLint 10, Prettier 3, Vitest 4 + happy-dom |
| Android | Kotlin, Jetpack Compose, Material 3, Retrofit2, Room, WorkManager, DataStore, Biometric; ktlint 1.6, detekt 1.23, JUnit 4 + Robolectric + MockWebServer + Turbine |
| Инфра | Docker Compose (dev + prod), distroless backend, multi-stage builds; агрегированный HTML-отчёт по линту/тестам трёх компонентов |

## Структура репозитория

```
budget-go/
├── backend/                # Go REST API (порт 8080)
│   ├── cmd/
│   │   ├── create_user/    # создание пользователя
│   │   ├── migrate/        # ad-hoc миграции
│   │   └── seed_loadtest/  # генератор данных для нагрузочного теста
│   ├── handlers/           # HTTP-хендлеры
│   ├── repository/         # MongoDB-репозитории
│   ├── Dockerfile          # dev-образ
│   ├── Dockerfile.prod     # distroless prod-образ
│   └── VERSION             # текущая версия API
├── frontend/               # Vue 3 SPA (порт 8082, nginx)
│   └── VERSION             # текущая версия web-клиента
├── android/                # Android (Kotlin/Compose)
│   └── VERSION             # текущая версия Android-клиента
├── apks/                   # бандл APK для in-app обновлений (подмонтирован в nginx)
├── tools/                  # bump-version.sh, build-android-release.sh
├── docker-compose.yml      # dev (порты наружу)
├── docker-compose.prod.yml # prod (порты на loopback, distroless)
├── Makefile
├── CHANGELOG.md
└── CONTRIBUTING.md
```

## Предварительные требования

**Для запуска через Docker (рекомендуется):**
- Docker Engine 24+
- Docker Compose v2

**Для локальной разработки:**
- Go 1.25+
- Node.js 24+ (LTS); пакетный менеджер — Yarn 4, запинен в `frontend/package.json` через `packageManager` и подключается через **corepack** (поставляется с Node — ничего ставить вручную не нужно)
- MongoDB 8 (или поднять через Docker, см. `make mongo-up`)

**Для сборки Android:**
- JDK 21
- Android SDK (compileSdk 35), переменная `ANDROID_HOME`
- Gradle (используется обёртка `gradlew`)

## Быстрый старт (Docker, dev)

```bash
# 1. Скопировать и настроить переменные окружения
cp .env.example .env
# Отредактировать .env: задать сильный JWT_SECRET и при необходимости прокси

# 2. Поднять все сервисы
make up
# или напрямую:
docker compose up --build -d

# 3. Создать первого пользователя
make create_user USER_LOGIN=alice USER_PASSWORD='strong-pass' USER_NAME="Alice"

# 4. Открыть приложение
# Фронтенд: http://localhost:8082
# API:       http://localhost:8080
```

## Конфигурация

Все настройки хранятся в файле `.env` (создаётся из `.env.example`). Файл не попадает в Git.

| Переменная | Описание | По умолчанию |
|------------|----------|--------------|
| `MONGO_USERNAME` | Пользователь MongoDB | `admin` |
| `MONGO_PASSWORD` | Пароль MongoDB | `password` |
| `MONGO_DB` | Имя базы данных | `budget` |
| `JWT_SECRET` | Секрет подписи токенов (мин. 32 символа) | — **обязательно задать** |
| `TZ` | Временная зона | `UTC` |
| `ANDROID_LATEST` | Версия APK, выдаваемая клиентам в `/api/version` | подставляется при `bump-android` |
| `ANDROID_MIN_REQUIRED` | Минимально допустимая версия Android-клиента | бампается вручную при breaking-изменениях API |
| `HTTP_PROXY` / `HTTPS_PROXY` | HTTP-прокси для Docker builds | _(пусто)_ |
| `NO_PROXY` | Исключения из прокси | `localhost,…` |

Сгенерировать надёжный `JWT_SECRET`:
```bash
openssl rand -hex 32
```

## Локальная разработка

```bash
# Установить зависимости (Go модули + npm)
make install

# Запустить MongoDB в Docker, бэкенд и фронтенд локально
make dev

# Или по отдельности:
make mongo-up        # MongoDB в Docker
make dev-backend     # Go-сервер на :8080
make dev-frontend    # Vue dev-server на :5173
```

При локальной разработке переменные `MONGO_USERNAME`, `MONGO_PASSWORD`, `MONGO_DB` берутся из окружения или из значений по умолчанию в `Makefile`.

Подробности по контрибьютингу, бампу версий и стилю коммитов — в [CONTRIBUTING.md](CONTRIBUTING.md).

## Команды Makefile (основные)

```
# Dev
make up              # Запустить все сервисы (build + detached)
make up-logs         # То же, но с потоком логов
make down            # Остановить
make logs            # Хвостить логи

# Prod
make prod-build      # Собрать prod-образы (distroless backend + nginx)
make prod-up         # Запустить prod-стек
make prod-down       # Остановить prod-стек
make prod-logs       # Хвостить prod-логи

# Локальная разработка
make dev             # MongoDB в Docker + бэкенд + фронтенд локально
make dev-backend     # Только Go-бэкенд
make dev-frontend    # Только Vue dev-server
make install         # Установить зависимости
make tidy            # go mod tidy

# Версионирование
make version                       # Показать текущие версии всех сервисов
make bump-api BUMP=patch|minor|major
make bump-web BUMP=patch|minor|major
make bump-android BUMP=patch|minor|major

# Линтинг (clean = 0 ошибок)
make lint            # Агрегированный HTML по всем компонентам → reports/lint.html
make lint-backend    # gofmt + go vet + golangci-lint v2
make lint-web        # ESLint 10 (--max-warnings=0) + Prettier 3 (--check)
make lint-android    # ktlint 1.6 + detekt 1.23 (через ./gradlew)
make format-android  # Авто-форматирование Kotlin через ktlint

# Тесты
make test                # Агрегированный HTML по всем компонентам → reports/test.html
make test-backend        # Go unit (-race, без integration)
make test-backend-cover  # + coverage → backend/cover.html
make test-backend-integration  # build-tag integration (нужен Docker для testcontainers)
make test-web            # Vitest 4 + happy-dom
make test-web-cover      # + coverage → frontend/coverage/index.html
make test-android        # JUnit 4 + Robolectric + MockWebServer + Turbine
make test-android-cover  # + JaCoCo → android/app/build/reports/jacoco/.../index.html

# Android
make android         # Debug APK
make android-release # Release APK (нужен keystore)

# Пользователи
make create_user USER_LOGIN=alice USER_PASSWORD=secret USER_NAME="Alice"

# Нагрузочный тест
make seed-loadtest [CLEAR=1] [EXPENSES=...] [INCOMES=...] [WISHLIST=...]
make loadtest-up     # Переключить backend на отдельную БД budget_loadtest
make loadtest-restore
make loadtest-drop
```

## Сборка Android

```bash
cd android
cp local.env.example local.env
# Отредактировать local.env: ANDROID_HOME, JAVA_HOME, опциональный SOCKS5

./build.sh
# APK появится: android/msdnna-budget-app-v<version>.apk
```

Параметры `local.env`:

| Переменная | Описание |
|------------|----------|
| `ANDROID_HOME` | Путь к Android SDK |
| `JAVA_HOME` | Путь к JDK 21 |
| `SOCKS_PROXY_HOST` | Хост SOCKS5-прокси (пусто = без прокси) |
| `SOCKS_PROXY_PORT` | Порт прокси (по умолчанию `1080`) |

Для release-сборки требуются `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` (см. `tools/build-android-release.sh`).

## Развёртывание в продакшене (домашний сервер с публичным доступом)

Целевой сценарий: один сервер, публичный домен, внешний обратный прокси (например, системный nginx) терминирует TLS и проксирует HTTP-трафик в Docker-стек, который слушает только loopback. MongoDB наружу не выставлен.

### 1. Подготовка сервера

- Установить Docker Engine 24+ и Docker Compose v2.
- Открыть в файрволе только 80/443 наружу. Порты 8080 (API) и 8082 (web) останутся на `127.0.0.1`.
- Получить TLS-сертификат (Let's Encrypt / certbot или иной).

### 2. Клонирование и `.env`

```bash
git clone <url> budget && cd budget
cp .env.example .env
# Минимум обязательно отредактировать:
#   JWT_SECRET    — длинная случайная строка (openssl rand -hex 32)
#   MONGO_PASSWORD — нетривиальный пароль
#   TZ
# Прокси — оставить пустыми, если сервер не за корпоративным прокси
```

### 3. Внешний reverse proxy

Прод-compose биндит сервисы на loopback (`127.0.0.1:8080` API, `127.0.0.1:8082` web). Системный nginx должен:

- терминировать TLS;
- проксировать `/` → `127.0.0.1:8082` (web + статические APK на `/apks/`);
- проксировать `/api/` → `127.0.0.1:8080` (если хотите ходить в API напрямую с домена; иначе nginx-фронта прокси `/api/` уже умеет — тогда внешний proxy просто на 8082).

Минимальный пример для внешнего nginx:

```nginx
server {
    listen 443 ssl http2;
    server_name budget.example.com;

    ssl_certificate     /etc/letsencrypt/live/budget.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/budget.example.com/privkey.pem;

    client_max_body_size 25M;     # APK

    location / {
        proxy_pass         http://127.0.0.1:8082;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }
}

server {
    listen 80;
    server_name budget.example.com;
    return 301 https://$host$request_uri;
}
```

Внутренний nginx фронта уже разбирает `/api/` на бэкенд и `/apks/` на смонтированный том — отдельно проксировать `/api/` на 8080 наружу не нужно.

### 4. Запуск

```bash
make prod-build
make prod-up
make prod-logs   # убедиться, что бэкенд поднялся
```

### 5. Создание первого пользователя

```bash
make create_user USER_LOGIN=alice USER_PASSWORD='very-strong' USER_NAME="Alice"
```

Регистрация через UI отсутствует по дизайну — пользователи создаются только администратором.

### 6. Раздача Android-APK для in-app обновлений

```bash
make android-release   # требует keystore (см. выше)
# APK копируется в android/msdnna-budget-app-v<version>.apk;
# для раздачи положить файл в ./apks:
cp android/msdnna-budget-app-v*.apk ./apks/
```

`./apks` смонтирован в nginx-фронт как `/apks/` (read-only). Backend отдаёт текущую/минимальную версию через `/api/version`, клиент собирает URL APK сам: `<server_root>/apks/msdnna-budget-app-v<latest>.apk`.

При бампе Android-версии (`make bump-android BUMP=...`) `ANDROID_LATEST` в `docker-compose.prod.yml` обновляется автоматически — после этого нужно пересобрать backend: `make prod-build && make prod-up`.

### 7. Бэкап MongoDB

В прод-конфиге Mongo не пробрасывает порты. Снимать бэкап изнутри сети контейнера:

```bash
docker exec budget-mongodb \
  mongodump --uri="mongodb://$MONGO_USERNAME:$MONGO_PASSWORD@localhost:27017/?authSource=admin" \
  --archive --gzip > "backup-$(date +%F).gz"
```

И восстановление:
```bash
docker exec -i budget-mongodb \
  mongorestore --uri="mongodb://$MONGO_USERNAME:$MONGO_PASSWORD@localhost:27017/?authSource=admin" \
  --archive --gzip < backup-2026-05-05.gz
```

### 8. Обновление (zero-state-loss)

```bash
git pull
make prod-build
make prod-up    # docker compose сам пересоздаст контейнеры
```

Том `mongodb_data` персистентный — данные сохраняются между обновлениями.

## Порты

| Сервис | Dev | Prod |
|--------|-----|------|
| Фронтенд (Nginx) | `0.0.0.0:8082` | `127.0.0.1:8082` |
| Бэкенд (Go API)  | `0.0.0.0:8080` | `127.0.0.1:8080` |
| MongoDB          | `0.0.0.0:27017` | _(не выставлен наружу)_ |
| Vue dev-server   | `0.0.0.0:5173` | — |

## История изменений

См. [CHANGELOG.md](CHANGELOG.md).

## Лицензия

[Apache License 2.0](LICENSE). © msdnna.
