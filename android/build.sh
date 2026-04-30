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

cp app/build/outputs/apk/debug/app-debug.apk ./semejnyj-byudzhet-debug.apk
echo "APK: $SCRIPT_DIR/semejnyj-byudzhet-debug.apk"
