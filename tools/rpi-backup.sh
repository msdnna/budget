#!/usr/bin/env bash
# Daily backup of the budget MongoDB. Dumps an archive via `docker exec` so we
# never touch the host's mongo client; rotates anything older than the retention
# window. Designed to be invoked from a systemd timer (see deploy/systemd/) or
# straight from cron.
#
# Environment variables (all optional):
#   BACKUP_DIR        — destination (default /var/backups/budget)
#   BACKUP_RETENTION  — days to keep (default 14)
#   MONGO_CONTAINER   — container name (default budget-mongodb)
#   ENV_FILE          — env-file with MONGO_USERNAME / MONGO_PASSWORD / MONGO_DB
#                       (default: <repo>/.env, walking up from this script)
#
# Exit non-zero on any failure so systemd / cron-reports surface it.

set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/budget}"
BACKUP_RETENTION="${BACKUP_RETENTION:-14}"
MONGO_CONTAINER="${MONGO_CONTAINER:-budget-mongodb}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${ENV_FILE:-$REPO_ROOT/.env}"

log() { printf '\033[1;36m[rpi-backup]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[rpi-backup]\033[0m %s\n' "$*" >&2; exit 1; }

# Load Mongo credentials from the deployment .env. We only read the keys we
# need — `set -a; source` would pull every variable into our shell.
if [[ ! -f "$ENV_FILE" ]]; then
  die "env file not found: $ENV_FILE (override with ENV_FILE=...)"
fi

read_env() {
  # Strip optional `export `, quotes, and a trailing comment.
  grep -E "^(export +)?$1=" "$ENV_FILE" \
    | tail -n1 \
    | sed -E "s/^(export +)?$1=//; s/^['\"]//; s/['\"]\$//; s/[[:space:]]*#.*//"
}

MONGO_USERNAME="$(read_env MONGO_USERNAME)"
MONGO_PASSWORD="$(read_env MONGO_PASSWORD)"
MONGO_DB="$(read_env MONGO_DB)"
MONGO_DB="${MONGO_DB:-budget}"

[[ -n "$MONGO_USERNAME" && -n "$MONGO_PASSWORD" ]] \
  || die "MONGO_USERNAME / MONGO_PASSWORD missing in $ENV_FILE"

if ! docker inspect "$MONGO_CONTAINER" >/dev/null 2>&1; then
  die "container '$MONGO_CONTAINER' not running"
fi

mkdir -p "$BACKUP_DIR"
chmod 0700 "$BACKUP_DIR"

STAMP="$(date +%Y%m%d-%H%M%S)"
DEST="$BACKUP_DIR/budget-$STAMP.archive.gz"
TMP="$DEST.partial"

log "dumping $MONGO_DB → $DEST"
# --archive streams a single binary archive to stdout; --gzip compresses inline.
# `docker exec -i` keeps the stdout pipe usable; we write a .partial file first
# so a half-finished dump never gets confused with a complete one.
docker exec -i "$MONGO_CONTAINER" mongodump \
    --archive --gzip --quiet \
    --uri="mongodb://${MONGO_USERNAME}:${MONGO_PASSWORD}@localhost:27017/?authSource=admin" \
    --db="$MONGO_DB" \
  > "$TMP"
mv "$TMP" "$DEST"

SIZE="$(du -h "$DEST" | cut -f1)"
log "wrote $DEST ($SIZE)"

# Rotate: anything older than BACKUP_RETENTION days, by mtime, removed.
log "pruning backups older than $BACKUP_RETENTION days"
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'budget-*.archive.gz' \
  -mtime +"$BACKUP_RETENTION" -print -delete | sed 's/^/  removed: /'

log "done."
