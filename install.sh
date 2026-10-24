#!/usr/bin/env bash
# Install Webmote on the TV, switch on its remote service and open it (it shows the address and PIN).
#   TV_HOST=<tv ip> ./install.sh                     # builds from this source
#   TV_HOST=<tv ip> APK=Webmote-0.1.0.apk ./install.sh   # or installs a release APK
# Uses adb. Where macOS blocks the stock adb, set CLEANYOURTV to a clone of github.com/Gathin23/cleanyourtv with
# its tools set up (tools/venv), and its tools/tv.py is used instead.
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP address}"
if [ -n "${APK:-}" ]; then
  [ -f "$APK" ] || { echo "no such file: $APK" >&2; exit 1; }
  APK="$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"
fi
cd "$(dirname "$0")"
PKG=io.github.maxhayim.webmote
SERVICE=$PKG/.RemoteService

if [ -n "${CLEANYOURTV:-}" ]; then
  TOOLS="$(cd "$CLEANYOURTV/tools" && pwd)"
  PY=$TOOLS/venv/bin/python
  [ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
  export MSYS_NO_PATHCONV=1
  tv() { "$PY" -I "$TOOLS/tv.py" "$@"; }
  push() { "$PY" -I "$TOOLS/b64push.py" "$1" "$2" >/dev/null; }
else
  ADB="${ADB:-adb}"
  command -v "$ADB" >/dev/null || { echo "adb not found: install Android platform-tools, or set CLEANYOURTV" >&2; exit 1; }
  "$ADB" connect "$TV_HOST:5555" >/dev/null
  tv() { "$ADB" -s "$TV_HOST:5555" shell "$@"; }
  push() { "$ADB" -s "$TV_HOST:5555" push "$1" "$2" >/dev/null; }
fi

if [ -z "${APK:-}" ]; then
  [ -f local.properties ] || { [ -n "${ANDROID_HOME:-}" ] && echo "sdk.dir=$ANDROID_HOME" > local.properties; } || true
  if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
  ./gradlew assembleRelease -q --console=plain
  APK=app/build/outputs/apk/release/app-release.apk
fi

push "$APK" /data/local/tmp/webmote.apk
OUT=$(tv 'pm install -r /data/local/tmp/webmote.apk; rm -f /data/local/tmp/webmote.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: pm install failed" >&2; exit 1 ;; esac

# Add the remote service to the accessibility services already on, keeping theirs (many TVs hide the setting).
CUR=$(tv settings get secure enabled_accessibility_services | tr -d '\r')
case "$CUR" in
  *"$SERVICE"*) echo "remote service already on" ;;
  ""|null) tv settings put secure enabled_accessibility_services "$SERVICE"; echo "remote service on" ;;
  *) tv settings put secure enabled_accessibility_services "$CUR:$SERVICE"; echo "remote service on" ;;
esac
tv settings put secure accessibility_enabled 1
tv "am start -n $PKG/.MainActivity >/dev/null; echo opened"
echo "Installed. Open the address shown on the TV on your phone or computer, and type the PIN."
