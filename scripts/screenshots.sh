#!/bin/bash
# Takes the App Store screenshots of the demo in the iOS simulator.
# Run after a Release simulator build into ./build (see screenshots.yml).
# Writes AppStore/screenshots/en-US/*.png at 1320 x 2868 (iPhone 6.9").
set -euo pipefail

bundle=com.pvd.controlroom
out=AppStore/screenshots/en-US
app=$(find build/Build/Products/Release-iphonesimulator -maxdepth 1 -name '*.app' | head -1)
[ -n "$app" ] || { echo "No simulator build in ./build"; exit 1; }

# The newest Pro Max: its screen is the 6.9" size App Store Connect wants.
udid=$(xcrun simctl list devices available --json | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
for runtime in sorted((k for k in devices if "iOS" in k), reverse=True):
    for d in sorted(devices[runtime], key=lambda d: d["name"], reverse=True):
        if d["name"].endswith("Pro Max"):
            print(d["udid"]); sys.exit()
sys.exit("No Pro Max simulator")
')
echo "Using simulator $udid"

xcrun simctl boot "$udid"
xcrun simctl bootstatus "$udid" -b
xcrun simctl ui "$udid" appearance light
xcrun simctl status_bar "$udid" override --time 9:41 --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100
xcrun simctl install "$udid" "$app"

# Every shot starts the app cold from a deep link, so no shot depends on the
# one before it.
open_url() {
  xcrun simctl terminate "$udid" "$bundle" 2>/dev/null || true
  xcrun simctl openurl "$udid" "$1"
  sleep "${2:-8}"
}

open_url "controlroom://demo" 12

mkdir -p "$out"
rm -f "$out"/*.png
shot() {
  open_url "$2"
  xcrun simctl io "$udid" screenshot --type=png "$out/$1.png"
  echo "Took $1"
}
shot 01-instances "controlroom://"
shot 02-instance "controlroom://instance/demo-1"
shot 03-serverless "controlroom://serverless"
shot 04-logs "controlroom://logs?kind=serverless&id=demo-c1"
shot 05-costs "controlroom://costs"
shot 06-instance-logs "controlroom://logs?kind=instance&id=demo-3"
