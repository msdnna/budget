#!/usr/bin/env bash
# Build Budget Android APK
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Source local config if present (machine-specific paths and proxy)
if [ -f "$SCRIPT_DIR/local.env" ]; then
  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/local.env"
fi

# Android SDK and Java — can be overridden in local.env or shell environment
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

# SOCKS5 proxy for Gradle JVM (optional)
PROXY_OPTS=""
if [ -n "${SOCKS_PROXY_HOST:-}" ]; then
  PROXY_OPTS="-DsocksProxyHost=$SOCKS_PROXY_HOST -DsocksProxyPort=${SOCKS_PROXY_PORT:-1080} -DsocksProxyVersion=5"
fi
export GRADLE_OPTS="${PROXY_OPTS:+$PROXY_OPTS }-Dorg.gradle.internal.http.socketTimeout=300000"

cd "$SCRIPT_DIR"
./gradlew assembleDebug

VERSION="$(tr -d '[:space:]' < "$SCRIPT_DIR/VERSION")"
APK_NAME="msdnna-budget-app-v${VERSION}.apk"

# Remove stale APKs from previous versions so the directory only ever has the
# current build — avoids confusion when copying to nginx /apks/.
find "$SCRIPT_DIR" -maxdepth 1 -name 'msdnna-budget-app-v*.apk' -delete
# Legacy name from before the rename — clean up too.
rm -f "$SCRIPT_DIR/semejnyj-byudzhet-debug.apk"

cp app/build/outputs/apk/debug/app-debug.apk "./${APK_NAME}"
echo "APK: $SCRIPT_DIR/${APK_NAME}"

# Also drop into the repo-level /apks store, which is bind-mounted into the
# frontend nginx container at /apks/ for in-app updates. Stale APKs get purged
# so only the current version is reachable over HTTP.
APKS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/apks"
if [ -d "$APKS_DIR" ]; then
  find "$APKS_DIR" -maxdepth 1 -name 'msdnna-budget-app-v*.apk' -delete
  cp "./${APK_NAME}" "$APKS_DIR/${APK_NAME}"
  echo "Served at: /apks/${APK_NAME}"
fi
