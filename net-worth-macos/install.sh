#!/usr/bin/env bash
# Builds the app and widget signed with your Apple Development certificate but no provisioning profile (so there is no
# 7-day expiry; only the certificate's own 1-year life), installs to /Applications and opens the settings window.
set -euo pipefail
cd "$(dirname "$0")"
xcodegen generate
xcodebuild -project NetWorth.xcodeproj -scheme NetWorth -configuration Release \
  -derivedDataPath build build | tail -3
APP="build/Build/Products/Release/NetWorth.app"
pkill -x NetWorth 2>/dev/null || true
rm -rf /Applications/NetWorth.app
ditto "$APP" /Applications/NetWorth.app
killall chronod NotificationCenter 2>/dev/null || true   # reinstalling can leave the widget daemon with stale desktop-widget state; it restarts itself
open /Applications/NetWorth.app
echo "Installed. Enter the URL and key, press Save & test, then right-click the desktop → Edit Widgets → search 純資産."
