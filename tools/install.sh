#!/usr/bin/env bash
# Build and install Remotely on the Mi Box over the network.
# Usage: tools/install.sh <box-ip> [debug|release]
set -euo pipefail
cd "$(dirname "$0")/.."
IP="${1:?usage: tools/install.sh <box-ip> [debug|release]}"
VARIANT="${2:-release}"
[[ "$VARIANT" == release ]] && tools/make-keystore.sh
TASK="assemble$(tr '[:lower:]' '[:upper:]' <<< "${VARIANT:0:1}")${VARIANT:1}"
tools/gw.sh -q ":app:$TASK"
APK="app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
adb connect "$IP:5555"
adb -s "$IP:5555" install -r "$APK"
# Allow autostart after boot on Android 10+ (Google TV); harmless on Android 9.
adb -s "$IP:5555" shell appops set app.remotely SYSTEM_ALERT_WINDOW allow || true
adb -s "$IP:5555" shell monkey -p app.remotely -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 || true
echo "Installed $APK on $IP"
