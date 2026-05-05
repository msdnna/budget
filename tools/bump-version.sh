#!/usr/bin/env bash
# Bump the semantic version of a service.
#
# Usage: ./tools/bump-version.sh <service> [major|minor|patch]
#   service: api | web | android
#   bump:    major | minor | patch   (default: patch)
#
# For android, also updates versionCode and versionName in app/build.gradle.
set -euo pipefail

SERVICE="${1:-}"
BUMP="${2:-patch}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  echo "Usage: $0 <api|web|android> [major|minor|patch]"
  exit 1
}

[ -n "$SERVICE" ] || usage

case "$SERVICE" in
  api)     VERSION_FILE="$ROOT/backend/VERSION" ;;
  web)     VERSION_FILE="$ROOT/frontend/VERSION" ;;
  android) VERSION_FILE="$ROOT/android/VERSION" ;;
  *) usage ;;
esac

CURRENT="$(cat "$VERSION_FILE" | tr -d '[:space:]')"
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT"

case "$BUMP" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  patch) PATCH=$((PATCH + 1)) ;;
  *) usage ;;
esac

NEW="$MAJOR.$MINOR.$PATCH"
echo "$NEW" > "$VERSION_FILE"
echo "[$SERVICE] $CURRENT → $NEW"

if [ "$SERVICE" = "android" ]; then
  # build.gradle reads versionName/versionCode from android/VERSION at evaluation
  # (see top of app/build.gradle). No edits to build.gradle needed.
  VERSION_CODE=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))
  echo "  android/app/build.gradle → versionCode=$VERSION_CODE versionName=\"$NEW\" (computed from VERSION)"

  # Update ANDROID_LATEST default in both docker-compose files so the backend
  # advertises the new APK to existing installs after a redeploy.
  for f in "$ROOT/docker-compose.yml" "$ROOT/docker-compose.prod.yml"; do
    if [ -f "$f" ]; then
      sed -i -E "s|(ANDROID_LATEST=\\\$\\{ANDROID_LATEST:-)[^}]+(\\})|\\1$NEW\\2|" "$f"
      echo "  $(basename "$f") → ANDROID_LATEST default = $NEW"
    fi
  done
  echo "  (ANDROID_MIN_REQUIRED unchanged — bump it manually only on breaking API changes)"
fi
