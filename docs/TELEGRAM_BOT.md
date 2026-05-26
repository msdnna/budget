# Telegram-бот

Бот позволяет вносить доходы и расходы в свободной форме — текстом или голосом.
Сообщение проходит через локальную LLM (Qwen3.5-9B) и Whisper (faster-whisper);
бот предлагает распарсенный draft и сохраняет транзакцию в budget API после
подтверждения.

```
Telegram → bot → Whisper (audio→text) → LLM (text→JSON) → /api/transactions
                          │                  │
                          └── HTTP ──────────┴──→ внешние сервисы (см. ниже)
```

## Архитектурные режимы

LLM и Whisper можно держать в одном из двух режимов независимо друг от друга:

| Режим | Где живёт ML-сервис | Когда использовать |
|---|---|---|
| **local-бот, local-ML** | LLM на той же машине что и приложение, Whisper в бот-контейнере | dev-машина |
| **app на RPi + ML на ПК** | LLM/Whisper отдельным compose на десктопе с GPU; бот ходит по LAN | прод |

Бот общается с LLM/Whisper по OpenAI-совместимому HTTP API — переключение
режима управляется двумя env-переменными:

- `LLM_BASE_URL` — например `http://192.168.x.x:8001/v1`
- `WHISPER_BASE_URL` — пустой = faster-whisper встроен в бот; URL = удалённый

Оба сервиса описаны ниже отдельно — поднимать их можно в любой комбинации.

## Привязка пользователя к боту

Связка телеграм-аккаунт ↔ budget-user делается **одноразовым кодом**:

1. В web/Android — Settings → Telegram → «Привязать».
2. На странице/экране появится 6-символьный код (TTL 5 мин, формат Crockford-base32).
3. В Telegram отправить боту команду `/link КОД`.

Бот вызывает service-only эндпоинты `POST /api/telegram/link/confirm` и
`GET /api/telegram/me` — оба требуют header `X-Service-Token` (общий secret
между ботом и backend, см. `SERVICE_TOKEN` в `.env`). После привязки бот при
каждом сообщении резолвит `telegram_user_id → budget_user_id` и проксирует
запросы к API c `X-Act-As-User: <budget_user_id>`.

## Тюнинг точности распознавания (LLM)

Бот загружает на каждый запрос **полный контекст** из БД и подставляет в
системный промпт LLM. Это даёт несколько слоёв точности без fine-tuning:

1. **Категории + ключевые слова** — admin в `Settings → Категории` задаёт
   для каждой категории список keyword-подсказок (например, `"Продукты"`:
   `магнит, пятёрочка, ашан, перекрёсток`).
2. **Глоссарий** — общесемейный словарь term→meaning в `Settings → Глоссарий`
   (например `"магаз" = магазин`, `"Марина" = жена`).
3. **История пользователя** — топ-50 пар «контрагент → категория» из его
   транзакций (например `"Магнит → Продукты" (15 раз)`); поднимается ботом
   автоматически через `GET /api/telegram/context?user_id=...`.

Подробное правило для counterparty vs description в системном промпте:

- Если категория общая (Продукты/Транспорт/Кафе) → counterparty = магазин.
- Если категория сама — магазин/сервис (OZON/Wildberries) → counterparty =
  что куплено (товар), **не повтор** названия магазина.
- description — побочные нюансы (банк перевода, количество, период), **не**
  основной предмет.

## Деплой: приложение на RPi, ML на ПК

Идея: на RPi только лёгкие сервисы (backend / frontend / bot), а LLM и Whisper
живут на десктопе с GPU. Стек на десктопе можно держать в **отдельном
docker-compose** (например `~/llm-stack/docker-compose.yml`) — на RPi его
поднимать не нужно. Бот ходит к ним по LAN.

### 1. Поднять LLM на ПК (Qwen3.5-9B)

Два варианта на выбор — оба отдают OpenAI-compatible `/v1/chat/completions`.

#### Вариант A — llama.cpp (GGUF, тонкая настройка)

Хорошо, если хочется явно контролировать `--ctx-size`, `--n-gpu-layers`,
параметры sampling и держать модель в формате GGUF. Образ
`ghcr.io/ggml-org/llama.cpp:full-cuda` уже содержит server-mode.

1. Скачай GGUF-чекпойнт Qwen3.5-9B (например с
   [huggingface.co/Qwen/Qwen3.5-9B-Instruct](https://huggingface.co))
   и положи в `./models/Qwen3.5-9B-UD-Q4_K_XL.gguf`.

2. Подними сервис:

```yaml
# ~/llm-stack/docker-compose.yml
services:
  llama-cpp:
    container_name: llama-cpp
    image: ghcr.io/ggml-org/llama.cpp:full-cuda  # CPU-only: :full
    runtime: nvidia                              # убрать если CPU
    restart: unless-stopped
    environment:
      NVIDIA_VISIBLE_DEVICES: all
      LLAMA_ARG_N_GPU_LAYERS: 20                 # 0 для CPU
      LLAMA_ARG_CTX_SIZE: 32768
      LLAMA_ARG_MODEL: /models/Qwen3.5-9B-UD-Q4_K_XL.gguf
      LLAMA_ARG_HOST: 0.0.0.0
      LLAMA_ARG_PORT: 8001
    volumes:
      - ./models:/models
    ports:
      - "8001:8001"
    # `--jinja` включает встроенные chat-templates Qwen; thinking-режим
    # отключён, чтобы ответы были быстрыми и без `<think>` блоков.
    command: --server --jinja --chat-template-kwargs '{"enable_thinking":false}' --reasoning-budget 0
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8001/health || exit 1"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 60s
```

```bash
cd ~/llm-stack
docker compose up -d llama-cpp
```

Проверка:

```bash
curl http://localhost:8001/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model":"qwen",
    "messages":[{"role":"user","content":"привет, ответь одним словом"}],
    "max_tokens":10
  }'
```

В `.env` на RPi:

```env
LLM_BASE_URL=http://192.168.x.x:8001/v1
LLM_MODEL=Qwen3.5-9B-UD-Q4_K_XL.gguf
```

#### Вариант B — Ollama (проще, без скачивания GGUF вручную)

Подойдёт, если не хочется возиться с моделями: Ollama сам тянет веса и
держит OpenAI-compatible слой на `:11434/v1`.

```yaml
# ~/llm-stack/docker-compose.yml
services:
  ollama:
    container_name: ollama
    image: ollama/ollama:latest
    runtime: nvidia                # убрать если CPU
    restart: unless-stopped
    environment:
      OLLAMA_HOST: 0.0.0.0:11434
      OLLAMA_KEEP_ALIVE: 24h       # держать модель в VRAM
    ports:
      - "11434:11434"
    volumes:
      - ollama_data:/root/.ollama

volumes:
  ollama_data:
```

```bash
cd ~/llm-stack
docker compose up -d ollama
docker exec ollama ollama pull qwen2.5:7b   # или qwen3:8b / qwen2.5:14b
```

Проверка:

```bash
curl http://localhost:11434/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen2.5:7b","messages":[{"role":"user","content":"привет"}]}'
```

В `.env` на RPi:

```env
LLM_BASE_URL=http://192.168.x.x:11434/v1
LLM_MODEL=qwen2.5:7b
```

> **Замечание про модели.** Бот делает structured-output через
> `response_format=json_schema` + `strict: true`. Это поддерживают Qwen2.5+
> и Qwen3. Старые модели (Mistral, Llama-2) могут игнорировать строгий
> schema-mode и возвращать prose — переключиться нечем. Используйте Qwen.

### 2. Поднять Whisper на ПК

Самый простой путь — готовый docker-образ
[fedirz/faster-whisper-server](https://github.com/fedirz/faster-whisper-server):
OpenAI-compatible API, CUDA/CPU варианты, тот же `WhisperModel` под капотом,
что и у локального бот-режима.

Добавь сервис в тот же `~/llm-stack/docker-compose.yml`:

```yaml
services:
  # ... llama-cpp или ollama выше ...

  whisper:
    container_name: whisper-server
    image: fedirz/faster-whisper-server:latest-cuda    # CPU: :latest-cpu
    runtime: nvidia                                     # убрать если CPU
    restart: unless-stopped
    environment:
      WHISPER__MODEL: Systran/faster-whisper-small      # или -medium / -large-v3
      WHISPER__COMPUTE_TYPE: float16                    # int8 на CPU
      WHISPER__DEVICE: cuda                             # cpu для CPU варианта
    ports:
      - "8002:8000"
    volumes:
      - whisper_cache:/root/.cache/huggingface

volumes:
  whisper_cache:
```

```bash
cd ~/llm-stack
docker compose up -d whisper
```

Первый запуск тянет ~250 MB модели (`small` int8) или ~750 MB (`medium`)
из HuggingFace в named volume. Кеш переживает рестарты/ребилды.

Проверка:

```bash
curl -F file=@sample.ogg -F model=Systran/faster-whisper-small \
     -F language=ru \
     http://localhost:8002/v1/audio/transcriptions
```

Должен вернуть `{"text":"..."}`.

### 3. Прописать в `.env` на RPi

```env
# .env на Raspberry Pi
# IP десктопа в LAN — куда смотрят LLM + Whisper
LLM_BASE_URL=http://192.168.x.x:8001/v1          # llama.cpp; или :11434/v1 для Ollama
LLM_MODEL=Qwen3.5-9B-UD-Q4_K_XL.gguf             # имя из llama.cpp; или qwen2.5:7b для Ollama

WHISPER_BASE_URL=http://192.168.x.x:8002/v1
WHISPER_REMOTE_MODEL=Systran/faster-whisper-small
WHISPER_LANGUAGE=ru

TELEGRAM_BOT_TOKEN=...
SERVICE_TOKEN=...                                # тот же, что и в backend
```

Замени `192.168.x.x` на LAN-IP десктопа.

### 4. Поднять стек на RPi

```bash
make rpi-up                          # backend + frontend
COMPOSE_PROFILES=bot make rpi-up     # + telegram-bot
```

В `docker-compose.rpi.yml` бот сидит под `profiles: ["bot"]` — отдельный
профиль, чтобы можно было выключить голос/LLM без правки compose. Также
там `WHISPER_BASE_URL=${WHISPER_BASE_URL:?...}` — без значения compose
откажется поднимать бот, чтобы не было «работает на CPU RPi и валится»
сюрпризов.

### 5. Что должно быть в логах при первом запуске

```text
bot: starting bot, api=http://backend:8080 llm=http://192.168.x.x:8001/v1 proxy=...
bot.whisper_client: remote whisper endpoint=http://192.168.x.x:8002/v1 model=Systran/...
bot: whisper ready (remote)
aiogram.dispatcher: Run polling for bot @your_bot_name id=...
```

Если `whisper ready` не появилось — открой `docker logs budget-telegram-bot` и
ищи стектрейс из `make_transcriber`. Бот не падает на ошибке Whisper —
голосовые сообщения он откажется обрабатывать, но текст продолжит работать.

## Деплой: всё локально (dev)

Один Docker-сетап на разработческой машине:

```bash
# Скачай модели Whisper в bot_whisper_cache (произойдёт автоматически при первом запуске)
make bot-up                          # build + start + show progress
make bot-logs                        # tail
```

При первом запуске faster-whisper скачает модель `small` (≈250 MB) в named
volume `bot_whisper_cache`. Кеш переживает ребилды.

### Тяжёлая модель / GPU

Если есть свободные ресурсы GPU и нужен `medium`/`large-v3-turbo`:

```env
WHISPER_MODEL=medium
WHISPER_DEVICE=cuda
WHISPER_COMPUTE_TYPE=float16
```

И в `docker-compose.yml` под `telegram-bot` добавь `runtime: nvidia`.

## Известные подводные камни

### HF Xet через корпоративный прокси

При скачивании больших моделей HuggingFace может уйти в Xet (chunked storage),
который плохо себя ведёт через HTTP-прокси (наблюдалось обрыв на ~50%
файла). Бот выставляет `HF_HUB_DISABLE_XET=1` и
`HF_HUB_DOWNLOAD_TIMEOUT=600` перед импортом, но это не всегда помогает —
если первая загрузка зависла, удали partial-blob:

```bash
docker exec budget-telegram-bot rm -rf /cache/huggingface/models--Systran--faster-whisper-small
make bot-up
```

### Telegram заблокирован без прокси

Если бот не может достучаться до api.telegram.org (`TelegramNetworkError`),
проверь:

- `HTTPS_PROXY` в `.env` (HTTP-прокси формата `http://host:port`)
- `make bot-up` подгружает `.env` вручную через `set -a; . ./.env; set +a`
  — если запускаешь руками, делай так же либо `export HTTPS_PROXY=...`

### Compose env priority

`docker compose` берёт переменные из shell с приоритетом над `.env`. Если в
shell `HTTPS_PROXY` пустая (unset) или `WHISPER_BASE_URL` пустая, compose
интерполирует пустую строку, а не значение из `.env`. Поэтому Makefile-таргет
`bot-up` явно подгружает `.env` в shell-make перед запуском compose.

## Связанные документы

- [`docs/RPI_DEPLOY.md`](RPI_DEPLOY.md) — общий runbook деплоя на Raspberry Pi
- [`CHANGELOG.md`](../CHANGELOG.md) — изменения по компонентам
- [llama.cpp server docs](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)
  — все CLI-флаги для тонкой настройки
- [Ollama OpenAI compatibility](https://github.com/ollama/ollama/blob/main/docs/openai.md)
  — какие части OpenAI API поддерживаются
- [fedirz/faster-whisper-server](https://github.com/fedirz/faster-whisper-server)
  — Whisper-сервис с OpenAI-compatible API
