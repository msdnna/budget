#!/usr/bin/env bash
# tools/rpi-fetch-apk.sh — pull the signed Android APK matching android/VERSION
# from its GitHub Release into ./apks/. Released by
# .github/workflows/release-android.yml on `android/v*` tag pushes — this
# script is the consumer side on the Raspberry Pi, where building locally
# would need the Android SDK on ARM (overkill).
#
# Идемпотентно: skip когда файл уже на месте. Soft-fail если релиз ещё не
# доехал (release workflow всё ещё идёт) — `make rpi-update` всё равно
# поднимет новые API/WEB образы.
#
# Без зависимостей: только curl (есть везде). Для публичных репо работает
# анонимно. Для приватных — `GH_TOKEN=<pat>` в env (тот же PAT, что gh CLI
# использует). `gh release download` НЕ используется: на чистом Pi без
# `gh auth login` он отказывается работать даже с публичными релизами
# (см. https://cli.github.com/manual/gh_auth_login).
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

# Owner/repo из git remote — оба формата (https/ssh). Fallback на захардкоженный
# слаг, если remote отсутствует (rare — обычно репо клонируется через git).
REMOTE="$(git -C "$ROOT" remote get-url origin 2>/dev/null || echo "")"
case "$REMOTE" in
    https://github.com/*) REPO_SLUG="${REMOTE#https://github.com/}" ;;
    git@github.com:*)     REPO_SLUG="${REMOTE#git@github.com:}" ;;
    *)                    REPO_SLUG="msdnna/budget" ;;
esac
REPO_SLUG="${REPO_SLUG%.git}"

URL="https://github.com/${REPO_SLUG}/releases/download/${TAG}/${NAME}"

echo "[rpi-apk] Скачиваю $NAME из релиза $TAG…"
echo "[rpi-apk] URL: $URL"

# curl автоматически следует за 302 → release-assets.githubusercontent.com
# (короткоживущий signed URL). --fail → exit 22 на HTTP 4xx/5xx, --retry 3
# на transient-фейлах сети. Слеш внутри тега ('android/v...') не нужно
# URL-кодировать: github роутит `/releases/download/<tag>/<asset>` корректно
# с любым числом слешей в <tag>.
AUTH_ARGS=()
if [ -n "${GH_TOKEN:-}" ]; then
    AUTH_ARGS=(-H "Authorization: Bearer ${GH_TOKEN}")
fi

if ! curl -fL --retry 3 --retry-delay 2 "${AUTH_ARGS[@]}" \
         -o "$DEST.part" "$URL"; then
    rm -f "$DEST.part"
    cat >&2 <<EOF
[rpi-apk] curl не смог скачать релиз. Возможные причины:
[rpi-apk]   1) release-android workflow ещё не закончил (подожди минуту, ещё раз rpi-update).
[rpi-apk]   2) Релиз $TAG не запушен.
[rpi-apk]   3) Репо приватный → нужен GH_TOKEN=<personal-access-token> в env.
[rpi-apk] API/WEB запустятся, in-app update вернёт 404 пока APK не появится.
EOF
    exit 0
fi
mv "$DEST.part" "$DEST"

# APK = zip-контейнер. Первые 4 байта должны быть 50 4B 03 04. Защита от
# случая, когда GitHub отдал HTML-страницу с ошибкой 404 / rate-limit
# вместо бинаря.
magic="$(dd if="$DEST" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' ')"
if [ "$magic" != "504b0304" ]; then
    echo "[rpi-apk] $DEST не похож на zip/APK (magic=$magic) — удаляю." >&2
    rm -f "$DEST"
    exit 1
fi

echo "[rpi-apk] OK — $(du -h "$DEST" | cut -f1) в $DEST"
