#!/usr/bin/env bash
# Builds the app and widget with your signing identity, installs to /Applications and opens the settings window.
set -euo pipefail
cd "$(dirname "$0")"
xcodegen generate
xcodebuild -project NetWorth.xcodeproj -scheme NetWorth -configuration Release \
  -derivedDataPath build -allowProvisioningUpdates build | tail -3
APP="build/Build/Products/Release/NetWorth.app"
pkill -x NetWorth 2>/dev/null || true
rm -rf /Applications/NetWorth.app
ditto "$APP" /Applications/NetWorth.app
open /Applications/NetWorth.app
echo "Installed. Enter the URL and key, press Save & test, then right-click the desktop → Edit Widgets → search 純資産."
