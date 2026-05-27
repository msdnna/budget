#!/usr/bin/env bash
# Bump the semantic version of a service.
#
# Usage: ./tools/bump-version.sh <service> [major|minor|patch]
#   service: api | web | android | bot
#   bump:    major | minor | patch   (default: patch)
#
# For android, also updates versionCode and versionName in app/build.gradle.
# For bot, reads/writes telegram_bot/pyproject.toml `version = "..."` line.
set -euo pipefail

SERVICE="${1:-}"
BUMP="${2:-patch}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  echo "Usage: $0 <api|web|android|bot> [major|minor|patch]"
  exit 1
}

[ -n "$SERVICE" ] || usage

BOT_PYPROJECT="$ROOT/telegram_bot/pyproject.toml"

case "$SERVICE" in
  api)     VERSION_FILE="$ROOT/backend/VERSION" ;;
  web)     VERSION_FILE="$ROOT/frontend/VERSION" ;;
  android) VERSION_FILE="$ROOT/android/VERSION" ;;
  bot)
    # No standalone VERSION file — single source of truth is pyproject.toml,
    # which is also what release-bot.yml CI checks against the git tag.
    VERSION_FILE=""
    ;;
  *) usage ;;
esac

if [ "$SERVICE" = "bot" ]; then
  CURRENT="$(awk -F'"' '/^version = / {print $2; exit}' "$BOT_PYPROJECT")"
else
  CURRENT="$(cat "$VERSION_FILE" | tr -d '[:space:]')"
fi
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT"

case "$BUMP" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  patch) PATCH=$((PATCH + 1)) ;;
  *) usage ;;
esac

NEW="$MAJOR.$MINOR.$PATCH"
if [ "$SERVICE" = "bot" ]; then
  # In-place edit of `version = "X.Y.Z"`. Pattern is anchored to BOL +
  # exact prefix to avoid touching other `version` keys (e.g. in
  # `[project.optional-dependencies]` block — there aren't any today, but
  # being defensive).
  sed -i -E "s|^(version = )\"[^\"]+\"|\\1\"$NEW\"|" "$BOT_PYPROJECT"
  echo "[bot] $CURRENT → $NEW (telegram_bot/pyproject.toml)"
else
  echo "$NEW" > "$VERSION_FILE"
  echo "[$SERVICE] $CURRENT → $NEW"
fi

if [ "$SERVICE" = "android" ]; then
  # build.gradle reads versionName/versionCode from android/VERSION at evaluation
  # (see top of app/build.gradle). No edits to build.gradle needed.
  VERSION_CODE=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))
  echo "  android/app/build.gradle → versionCode=$VERSION_CODE versionName=\"$NEW\" (computed from VERSION)"

  # Update ANDROID_LATEST default in both docker-compose files so the backend
  # advertises the new APK to existing installs after a redeploy.
  for f in "$ROOT/docker-compose.yml" "$ROOT/docker-compose.prod.yml" "$ROOT/docker-compose.rpi.yml"; do
    if [ -f "$f" ]; then
      sed -i -E "s|(ANDROID_LATEST=\\\$\\{ANDROID_LATEST:-)[^}]+(\\})|\\1$NEW\\2|" "$f"
      echo "  $(basename "$f") → ANDROID_LATEST default = $NEW"
    fi
  done
  echo "  (ANDROID_MIN_REQUIRED unchanged — bump it manually only on breaking API changes)"
fi
