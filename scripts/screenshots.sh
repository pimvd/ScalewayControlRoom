#!/bin/bash
# Takes the App Store screenshots of the demo in the iOS simulator.
# Run after a Release simulator build into ./build (see screenshots.yml).
# Writes AppStore/screenshots/en-US/*.png at 1320 x 2868 (iPhone 6.9").
set -euo pipefail

bundle=com.pvd.scwcontrolroom
out=AppStore/screenshots/en-US
app=$(find build/Build/Products/Release-iphonesimulator -maxdepth 1 -name '*.app' | head -1)
[ -n "$app" ] || { echo "No simulator build in ./build"; exit 1; }

# The newest Pro Max: its screen is the 6.9" size App Store Connect wants.
# The workflow may have picked and booted it already, during the build.
udid=${SIMULATOR_UDID:-$(xcrun simctl list devices available --json | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
for runtime in sorted((k for k in devices if "iOS" in k), reverse=True):
    for d in sorted(devices[runtime], key=lambda d: d["name"], reverse=True):
        if d["name"].endswith("Pro Max"):
            print(d["udid"]); sys.exit()
sys.exit("No Pro Max simulator")
')}
echo "Using simulator $udid"

xcrun simctl boot "$udid" 2>/dev/null || true  # already booting is fine
xcrun simctl bootstatus "$udid" -b
xcrun simctl ui "$udid" appearance light
xcrun simctl status_bar "$udid" override --time 9:41 --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100
xcrun simctl install "$udid" "$app"

# Every shot starts the app cold with a launch argument naming the screen; the
# app turns the demo on and opens it (ScreenshotRoute in src/app/_layout.tsx).
# A deep link would stop at iOS's "Open in" prompt.
mkdir -p "$out"
rm -f "$out"/*.png
shot() {
  xcrun simctl terminate "$udid" "$bundle" 2>/dev/null || true
  xcrun simctl launch "$udid" "$bundle" -screenshotRoute "$2"
  sleep "${3:-8}"
  xcrun simctl io "$udid" screenshot --type=png "$out/$1.png"
  echo "Took $1"
}
# The first launch also adds the demo key and loads its data.
shot 01-instances "/" 15
shot 02-instance "/instance/demo-1"
shot 03-serverless "/serverless"
shot 04-logs "/logs?kind=serverless&id=demo-c1"
shot 05-costs "/costs"
shot 06-instance-logs "/logs?kind=instance&id=demo-3"

# Identical pictures mean the app never got past one screen (or a system
# prompt): fail rather than commit them.
if [ "$(md5 -q "$out"/*.png | sort -u | wc -l)" -ne "$(ls "$out"/*.png | wc -l)" ]; then
  echo "Some screenshots are identical; the app did not reach every screen."
  exit 1
fi
