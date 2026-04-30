# Семейный бюджет

Приложение для учёта семейного бюджета: доходы, расходы, аналитика и экспорт в PDF. Монорепозиторий, включающий Go-бэкенд, Vue 3 фронтенд и нативное Android-приложение.

## Стек технологий

| Компонент | Технологии |
|-----------|------------|
| Бэкенд | Go 1.25, MongoDB 8, JWT (HS256) |
| Фронтенд | Vue 3, Naive UI, Vite, Nginx |
| Android | Kotlin, Jetpack Compose, Retrofit2 |
| Инфраструктура | Docker Compose, multi-stage builds |

## Структура репозитория

```
budget-go/
├── backend/        # Go REST API (порт 8080)
├── frontend/       # Vue 3 SPA (порт 8082)
├── android/        # Android-приложение (Kotlin/Compose)
├── docker-compose.yml
├── Makefile
└── .env.example
```

## Предварительные требования

**Для запуска через Docker (рекомендуется):**
- Docker Engine 24+
- Docker Compose v2

**Для локальной разработки:**
- Go 1.25+
- Node.js 22+, npm
- MongoDB (или запустить через Docker, см. `make mongo-up`)

**Для сборки Android:**
- JDK 17+
- Android SDK (API 34), переменная `ANDROID_HOME`
- Gradle (обёртка `gradlew` входит в репозиторий)

## Быстрый старт (Docker)

```bash
# 1. Скопировать и настроить переменные окружения
cp .env.example .env
# Отредактировать .env: задать сильный JWT_SECRET и при необходимости прокси

# 2. Поднять все сервисы
make up
# или напрямую:
docker compose up --build -d

# 3. Открыть приложение
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
| `HTTP_PROXY` | HTTP-прокси для Docker builds | _(пусто)_ |
| `HTTPS_PROXY` | HTTPS-прокси для Docker builds | _(пусто)_ |
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

При локальной разработке переменные `MONGO_USERNAME`, `MONGO_PASSWORD`, `MONGO_DB` берутся из окружения или из значений по умолчанию в `Makefile` (совпадают с `.env.example`).

## Команды Makefile

```
make up              # Запустить все сервисы в Docker (detached)
make up-logs         # Запустить с выводом логов
make down            # Остановить Docker-сервисы
make clean           # Удалить контейнеры, тома и образы
make logs            # Просмотр логов Docker
make dev             # MongoDB в Docker + бэкенд + фронтенд локально
make dev-backend     # Только Go-бэкенд
make dev-frontend    # Только Vue dev-server
make mongo-up        # Только MongoDB в Docker
make build           # Собрать бинарники (Go + Vue)
make install         # Установить зависимости
make tidy            # Обновить go.sum
```

## Сборка Android

```bash
# 1. Настроить машинно-специфичные параметры
cd android
cp local.env.example local.env
# Отредактировать local.env: прописать ANDROID_HOME, JAVA_HOME
# и при необходимости SOCKS5-прокси

# 2. Собрать APK
./build.sh
# APK появится: android/semejnyj-byudzhet-debug.apk
```

Параметры `local.env`:

| Переменная | Описание |
|------------|----------|
| `ANDROID_HOME` | Путь к Android SDK |
| `JAVA_HOME` | Путь к JDK 17+ |
| `SOCKS_PROXY_HOST` | Хост SOCKS5-прокси (пусто = без прокси) |
| `SOCKS_PROXY_PORT` | Порт прокси (по умолчанию `1080`) |

## Создание пользователей

Приложение использует JWT-аутентификацию. Пользователи создаются вручную через вспомогательный скрипт (см. `backend/`). Регистрация через интерфейс не предусмотрена.

## Порты по умолчанию

| Сервис | Порт |
|--------|------|
| Фронтенд (Nginx) | 8082 |
| Бэкенд (Go API) | 8080 |
| MongoDB | 27017 |
| Vue dev-server | 5173 |
