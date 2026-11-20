#!/bin/sh
# SAMAVETA Gradle launcher.
#
# The binary gradle-wrapper.jar cannot be committed from this development environment (it is
# produced by `gradle wrapper`). This POSIX launcher therefore:
#   1. uses an already-installed `gradle` when one matches the pinned version, otherwise
#   2. downloads the pinned distribution and runs it.
# Either way the Gradle *version* is fixed by gradle/wrapper/gradle-wrapper.properties, so builds
# are reproducible. Run `gradle wrapper` once on a machine with Gradle installed to replace this
# script with the canonical generated wrapper.

set -e

PROPS="$(dirname "$0")/gradle/wrapper/gradle-wrapper.properties"
if [ ! -f "$PROPS" ]; then
  echo "error: $PROPS not found" >&2
  exit 1
fi

DIST_URL=$(sed -n 's/^distributionUrl=//p' "$PROPS" | sed 's/\\:/:/g')
VERSION=$(basename "$DIST_URL" | sed 's/-bin\.zip$//')

if command -v gradle >/dev/null 2>&1; then
  INSTALLED=$(gradle --version 2>/dev/null | sed -n 's/^Gradle //p' | head -1)
  if [ "$INSTALLED" = "$VERSION" ]; then
    exec gradle "$@"
  fi
fi

CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/$VERSION"
DIST_DIR="$CACHE/extracted"
if [ ! -x "$DIST_DIR/gradle-$VERSION/bin/gradle" ]; then
  echo "Downloading Gradle $VERSION ..."
  mkdir -p "$CACHE"
  ZIP="$CACHE/gradle-$VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$ZIP" "$DIST_URL"
  else
    wget -q -O "$ZIP" "$DIST_URL"
  fi
  mkdir -p "$DIST_DIR"
  unzip -q -o "$ZIP" -d "$DIST_DIR"
fi

exec "$DIST_DIR/gradle-$VERSION/bin/gradle" "$@"
