#!/usr/bin/env bash
# tools/rpi-fetch-apk.sh — pull the signed Android APK matching android/VERSION
# from its GitHub Release into ./apks/. Released by
# .github/workflows/release-android.yml on `android/v*` tag pushes — this
# script is the consumer side on the Raspberry Pi, where building locally
# would need the Android SDK on ARM (overkill).
#
# Idempotent: skips when the target file is already present. Soft-fails when
# gh is missing or the release tag doesn't exist yet (release workflow may
# still be running) — `make rpi-update` should still bring up the new
# API/WEB images even if the APK side lags by a few minutes.
#
# Requires `gh` CLI (apt: gh, или https://cli.github.com). For private repos
# `gh auth login` или `GH_TOKEN=<pat>` обязательны; для публичных репозиториев
# gh скачивает анонимно.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(tr -d '[:space:]' < "$ROOT/android/VERSION")"
NAME="msdnna-budget-app-v${VERSION}.apk"
DEST="$ROOT/apks/$NAME"
TAG="android/v${VERSION}"

mkdir -p "$ROOT/apks"

if [ -s "$DEST" ]; then
    echo "[rpi-apk] $NAME уже в ./apks/ — пропуск."
    exit 0
fi

if ! command -v gh >/dev/null 2>&1; then
    cat >&2 <<'EOF'
[rpi-apk] gh CLI не установлен.
[rpi-apk] Поставь:  sudo apt install gh   (или https://cli.github.com)
[rpi-apk] Затем:    gh auth login   (только для приватного репо)
[rpi-apk] APK не скачан — in-app update будет отдавать 404 пока файл
[rpi-apk] не появится в ./apks/. Сервисы API/WEB продолжат работать.
EOF
    exit 0
fi

echo "[rpi-apk] Скачиваю $NAME из релиза $TAG…"
if ! gh release download "$TAG" --pattern "$NAME" --dir "$ROOT/apks/"; then
    echo "[rpi-apk] Релиз $TAG не найден или asset отсутствует." >&2
    echo "[rpi-apk] Возможно release-android workflow ещё не закончил — запусти rpi-update ещё раз через минуту." >&2
    exit 0
fi

# APK = zip-контейнер. Первые 4 байта должны быть 50 4B 03 04. Защита от
# случая, когда gh release download выдал, например, HTML с ошибкой 404 в
# обёртке (раньше так случалось при rate-limit).
magic="$(dd if="$DEST" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' ')"
if [ "$magic" != "504b0304" ]; then
    echo "[rpi-apk] $DEST не похож на zip/APK (magic=$magic) — удаляю." >&2
    rm -f "$DEST"
    exit 1
fi

echo "[rpi-apk] OK — $(du -h "$DEST" | cut -f1) в $DEST"
