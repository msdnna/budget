#!/usr/bin/env bash
# Build a signed release APK.
#
# Required env vars (set in environment or android/local.env):
#   ANDROID_KEYSTORE_FILE     — absolute path to .jks / .keystore file
#   ANDROID_KEYSTORE_PASSWORD — keystore password
#   ANDROID_KEY_ALIAS         — key alias inside the keystore
#   ANDROID_KEY_PASSWORD      — key password (often same as keystore password)
#
# Generate a keystore (first time only):
#   keytool -genkey -v -keystore ~/budget.jks -alias budget \
#     -keyalg RSA -keysize 2048 -validity 10000
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$SCRIPT_DIR/../android"

if [ -f "$ANDROID_DIR/local.env" ]; then
  # shellcheck disable=SC1091
  source "$ANDROID_DIR/local.env"
fi

for var in ANDROID_KEYSTORE_FILE ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
  if [ -z "${!var:-}" ]; then
    echo "Error: $var is not set"
    echo "Set it in the environment or in android/local.env"
    exit 1
  fi
done

if [ ! -f "$ANDROID_KEYSTORE_FILE" ]; then
  echo "Error: keystore not found: $ANDROID_KEYSTORE_FILE"
  exit 1
fi

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_KEYSTORE_FILE ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD

PROXY_OPTS=""
if [ -n "${SOCKS_PROXY_HOST:-}" ]; then
  PROXY_OPTS="-DsocksProxyHost=$SOCKS_PROXY_HOST -DsocksProxyPort=${SOCKS_PROXY_PORT:-1080} -DsocksProxyVersion=5"
fi
export GRADLE_OPTS="${PROXY_OPTS:+$PROXY_OPTS }-Dorg.gradle.internal.http.socketTimeout=300000"

cd "$ANDROID_DIR"
./gradlew assembleRelease

VERSION="$(tr -d '[:space:]' < "$ANDROID_DIR/VERSION")"
OUTPUT="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
DEST="$ANDROID_DIR/msdnna-budget-app-v${VERSION}.apk"

find "$ANDROID_DIR" -maxdepth 1 -name 'msdnna-budget-app-v*.apk' -delete
rm -f "$ANDROID_DIR/semejnyj-byudzhet-release.apk"

cp "$OUTPUT" "$DEST"
echo "APK: $DEST"
