#!/usr/bin/env bash
# Builds a signed release APK into ../Releases/Webmote-<version>.apk, for a GitHub release.
# The key stays outside the repo: SIGNING_PROPERTIES, or ../Signing/signing.properties next to the repo folder
# (storeFile, storePassword, keyAlias, keyPassword). Never commit either. Losing the key means people can't
# update in place.
set -euo pipefail
cd "$(dirname "$0")"
export SIGNING_PROPERTIES="${SIGNING_PROPERTIES:-$(cd .. && pwd)/Signing/signing.properties}"
[ -f "$SIGNING_PROPERTIES" ] || { echo "no signing.properties at $SIGNING_PROPERTIES" >&2; exit 1; }
VERSION=$(sed -n "s/.*versionName '\(.*\)'.*/\1/p" app/build.gradle)
OUT="${RELEASES_DIR:-$(cd .. && pwd)/Releases}"
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
[ -f local.properties ] || { [ -n "${ANDROID_HOME:-}" ] && echo "sdk.dir=$ANDROID_HOME" > local.properties; } || true
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
./gradlew clean testReleaseUnitTest assembleRelease -q --console=plain
cp app/build/outputs/apk/release/app-release.apk "$OUT/Webmote-$VERSION.apk"
# The Home Assistant integration, for installing without HACS: a zip to unpack by hand, and a tar.gz for one
# command in the Terminal add-on. Same names every release, so releases/latest/download/... always works.
rm -f "$OUT/webmote-home-assistant.zip" "$OUT/webmote-home-assistant.tar.gz"
(cd custom_components && zip -qrX "$OUT/webmote-home-assistant.zip" webmote -x '*/__pycache__/*' \
  && COPYFILE_DISABLE=1 tar --exclude __pycache__ -czf "$OUT/webmote-home-assistant.tar.gz" webmote)
shasum -a 256 "$OUT/Webmote-$VERSION.apk" "$OUT/webmote-home-assistant.zip" "$OUT/webmote-home-assistant.tar.gz"
