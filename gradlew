#!/bin/sh
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION="8.11.1"
GRADLE_SHA256="f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6"
CACHE_DIR="$APP_HOME/.gradle-dist"
DIST_DIR="$CACHE_DIR/gradle-$GRADLE_VERSION"
ZIP_FILE="$CACHE_DIR/gradle-$GRADLE_VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$DIST_DIR/bin/gradle" ]; then
  mkdir -p "$CACHE_DIR"
  if [ ! -f "$ZIP_FILE" ]; then
    echo "Téléchargement de Gradle $GRADLE_VERSION..."
    if command -v curl >/dev/null 2>&1; then
      curl -fL "$URL" -o "$ZIP_FILE"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP_FILE" "$URL"
    else
      echo "Erreur : curl ou wget est nécessaire." >&2
      exit 1
    fi
  fi

  if command -v sha256sum >/dev/null 2>&1; then
    echo "$GRADLE_SHA256  $ZIP_FILE" | sha256sum -c -
  fi

  command -v unzip >/dev/null 2>&1 || {
    echo "Erreur : unzip est nécessaire." >&2
    exit 1
  }
  rm -rf "$DIST_DIR"
  unzip -q "$ZIP_FILE" -d "$CACHE_DIR"
fi

exec "$DIST_DIR/bin/gradle" "$@"
