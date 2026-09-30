#!/usr/bin/env bash
# Usage: net-worth-android/dev/seed.sh   (debug build installed on the running emulator/device)
set -euo pipefail
ADB="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb"
HERE="$(cd "$(dirname "$0")" && pwd)"
"$ADB" push "$HERE/seed-cache.json" /data/local/tmp/seed-cache.json
"$ADB" shell run-as com.isym444.networth mkdir -p files
"$ADB" shell run-as com.isym444.networth cp /data/local/tmp/seed-cache.json files/cache.json
echo "Seeded. Re-render by adding the widget or opening the app."
