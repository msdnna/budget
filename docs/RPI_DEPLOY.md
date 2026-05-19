# Раскатка на Raspberry Pi (home server)

Полный шаг-за-шагом для развёртывания стека `budget-go` (MongoDB + Go API + Vue
SPA) на Raspberry Pi 4B / 5 в режиме «домашнего сервера в LAN, без TLS».

Стек на Pi только тянет готовые multi-arch образы из GHCR — никаких локальных
сборок Go или Yarn. Релизы выпускаются через GitHub Actions
(`release-api.yml`, `release-web.yml`), которые публикуют образы под
`linux/amd64` и `linux/arm64` одновременно.

> **TL;DR**, если Pi уже готова:
> ```bash
> git pull && make rpi-update
> ```

## 1. Железо и подготовка SD-карты

- **Pi 4B (8 GB) или Pi 5** — оба тянут стек с двукратным запасом по RAM.
- **USB3-SSD** настоятельно рекомендуется. MongoDB пишет в журнал постоянно и
  убивает SD-карту износом за месяцы. Минимум — перенесите `/var/lib/docker`
  на SSD; чище — корневая ФС целиком на SSD (Pi 4 умеет USB-boot из коробки,
  Pi 5 — тем более).
- **ОС: Raspberry Pi OS Lite (64-bit, Bookworm).** Скачать через
  [Raspberry Pi Imager](https://www.raspberrypi.com/software/), там же удобно
  заранее задать hostname / SSH / Wi-Fi.

Полезные настройки в Imager (шестерёнка):

- Hostname: `budget` (так mDNS отдаст `budget.local`).
- Включить SSH с публичным ключом.
- Локаль / часовой пояс (`Europe/Moscow` или ваш).

После прошивки воткнуть SSD, включить Pi, дождаться доступности по
`ssh <user>@budget.local`.

## 2. Bootstrap

На Pi, под обычным пользователем:

```bash
sudo apt update && sudo apt install -y git
git clone https://github.com/msdnna/budget.git /opt/budget
cd /opt/budget
sudo ./tools/rpi-bootstrap.sh
```

Скрипт делает (всё идемпотентно — можно перезапустить):

| Что | Зачем |
| --- | --- |
| `apt update` + базовые пакеты | свежие apt-метаданные, `curl`, `gnupg`, `jq`, `cron` |
| Docker CE + compose-plugin (официальный репозиторий) | docker-плагины из debian-репо отстают на 1–2 релиза |
| `avahi-daemon` | mDNS — Pi виден как `budget.local` без статической IP |
| `unattended-upgrades` | автообновление только security-патчей, без ребутов |
| `vm.swappiness=10` | Mongo предпочитает дёргать диск, чем уходить в swap |
| `<user>` → docker group | работа с `docker` без sudo |
| hostname → `budget` | мэппинг под `budget.local`; отключается `KEEP_HOSTNAME=1` |

**После первого запуска нужно перелогиниться** (или `newgrp docker`), чтобы
группа `docker` подхватилась.

## 3. Конфиг

```bash
cp .env.rpi.example .env
${EDITOR:-nano} .env
```

Обязательные поля:
- `MONGO_USERNAME` / `MONGO_PASSWORD` / `MONGO_DB`
- `JWT_SECRET` — `openssl rand -hex 32`
- `TZ`

Опционально: `WEB_HOST_PORT=80` (по умолчанию 80, можно изменить, если на Pi
уже что-то слушает).

## 4. GHCR pull

Если репо публичный — login не нужен, `docker pull` сходит анонимно.

Если приватный:
```bash
# на github.com → Settings → Developer settings → Personal access tokens (classic)
# scope: read:packages
echo "<GHCR_PAT>" | docker login ghcr.io -u <github-user> --password-stdin
```

Локальное registry-зеркало (если планируете) настраивается в
`/etc/docker/daemon.json` независимо от стека:
```json
{
  "registry-mirrors": ["http://<mirror-host>:5000"]
}
```
После правки — `sudo systemctl restart docker`.

## 5. Запуск

```bash
make rpi-up
```

Что произойдёт:
- `API_VERSION` / `WEB_VERSION` берутся из `backend/VERSION` и `frontend/VERSION`
  (можно переопределить переменной окружения).
- `docker compose pull` тянет `ghcr.io/<owner>/budget-{backend,frontend}:<ver>`.
- Mongo 8 поднимается с capped WT-кэшем 1 GB.
- Frontend слушает `0.0.0.0:80`. Backend и Mongo на host-портах не видны.

Проверка:
```bash
make rpi-logs                    # все логи стека
curl http://localhost/api/health # должен ответить {"status":"ok"}
```
С другого устройства в LAN — `http://budget.local`.

### Создание первого пользователя

Prod-образ распространяется на distroless без шелла, поэтому отдельная утилита
`create_user` лежит рядом с серверным бинарём и вызывается напрямую:

```bash
docker compose -f docker-compose.rpi.yml exec backend /app/create_user \
  -login alice -password 'secret' -name 'Alice Smith'
```

Опционально: `-avatar 'https://…'` и `-admin` (первому пользователю обычно
имеет смысл выдать админку, чтобы он мог создавать остальных через
`/settings/users` в Web UI).

## 6. Бэкапы

```bash
sudo make rpi-backup-install
```
Ставит systemd-юнит + таймер на ежедневный `mongodump` в
`/var/backups/budget/budget-YYYYMMDD-HHMMSS.archive.gz` (`--archive --gzip`),
ротация — 14 дней (`BACKUP_RETENTION`). `Persistent=true` подхватывает
пропущенные запуски, если Pi была выключена.

Однократный бэкап вручную:
```bash
make rpi-backup-now
```

### Восстановление

```bash
# на остановленном стеке (или в свежую коллекцию):
docker exec -i budget-mongodb mongorestore \
    --archive --gzip --drop \
    --uri="mongodb://$MONGO_USERNAME:$MONGO_PASSWORD@localhost:27017/?authSource=admin" \
  < /var/backups/budget/budget-YYYYMMDD-HHMMSS.archive.gz
```
`--drop` сначала чистит коллекции — снимите, если хотите слить с тем, что в БД.

### Переезд на NAS

Когда появится NAS — поменяйте `BACKUP_DIR` в
`deploy/systemd/budget-backup.service` (через `systemctl edit budget-backup.service`,
без правки самого юнит-файла в репо). Сам скрипт не привязан к пути.

## 7. Обновление до нового релиза

После публикации новой версии (вы запушили тег `api/v...` или `web/v...`, CI
собрал и опубликовал образ):

```bash
cd /opt/budget
make rpi-update
# git pull --ff-only → docker compose pull → docker compose up -d
```

`docker compose up -d` пересоздаёт только контейнеры с изменёнными образами;
Mongo не трогается. Volume `mongodb_data` переживает обновления.

Откатиться на пред. версию — пин `API_VERSION` / `WEB_VERSION`:
```bash
API_VERSION=1.19.2 WEB_VERSION=1.31.4 make rpi-up
```

## 8. Доступ из LAN

- **Web**: `http://budget.local` (mDNS) или по IP Pi.
- **Android-клиент**: уже умеет искать сервер на `/api/health` —
  `ServerDiscovery` пробежит по подсети. Введите `budget.local` или IP
  вручную, если автопоиск не нашёл.
- **API**: только через nginx фронта (`/api/...`). Backend на хосте не
  публикуется — обращаться к нему напрямую можно только через
  `docker compose exec backend ...`.

## 9. Если что-то пошло не так

```bash
make rpi-logs                              # стрим всех логов
docker compose -f docker-compose.rpi.yml ps  # статус контейнеров
docker stats                                  # RAM/CPU по контейнерам
systemctl status budget-backup.timer          # таймер бэкапа
journalctl -u budget-backup.service -n 50     # последний запуск бэкапа
```

Известные грабли:

- **`exec format error` при `docker compose up`** — образ не multi-arch. На
  Pi архитектура `aarch64`; убедитесь, что в `release-{api,web}.yml`
  `platforms: linux/amd64,linux/arm64` присутствует, и тег пересобран.
- **Mongo долго стартует на первом запуске** — `start_period: 60s` в
  healthcheck, normal. Backend ждёт `service_healthy`.
- **`budget.local` не резолвится** — проверьте, что на клиенте включен mDNS
  (macOS / iOS / Linux+avahi умеют из коробки; Windows — `Bonjour Print Services`
  или `apt-get install avahi-utils && avahi-resolve -n budget.local`).
- **Свободное место на диске** — `du -sh /var/lib/docker /var/backups/budget`.
  Сначала ротация (`tools/rpi-backup.sh` снимает старые), потом
  `docker image prune -a` (удалит образы старых версий).

## 10. План на потом (не для v1)

- **NAS-бэкапы**: rsync из `/var/backups/budget` на сетевую шару (отдельный
  systemd timer).
- **TLS**: если когда-нибудь захочется внешний доступ — Caddy в качестве
  reverse-proxy перед фронтом (`reverse_proxy localhost:80`,
  `tls internal` для локального CA, либо ACME через Cloudflare-DNS-challenge).
  Тогда `WEB_HOST_PORT=8082` и frontend в loopback.
- **Tailscale / Cloudflare Tunnel**: альтернатива выкатке порта наружу,
  устанавливаются независимо от стека.
- **Monitoring**: `docker stats` хватает для одной Pi; для нескольких — лёгкий
  Prometheus + Node Exporter + cAdvisor (отдельный compose-файл).
