#!/usr/bin/env bash
# Capture NavBeam dash debug logs from the phone into dash.log
set -e

# Find adb
if [ -z "$ADB" ]; then
  if [ -f "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
    ADB="$HOME/Library/Android/sdk/platform-tools/adb"
  elif [ -f "$HOME/Android/Sdk/platform-tools/adb" ]; then
    ADB="$HOME/Android/Sdk/platform-tools/adb"
  else
    ADB="adb"
  fi
fi

# Detect device
DEVICE_COUNT=$("$ADB" devices | grep -v "List of devices" | grep "device" | wc -l | tr -d ' ')
if [ "$DEVICE_COUNT" -eq 0 ]; then
  echo "Error: No Android devices found connected via ADB."
  echo "Make sure USB Debugging is enabled and the phone is connected."
  exit 1
elif [ "$DEVICE_COUNT" -eq 1 ]; then
  # Single device connected
  SERIAL=$("$ADB" devices | grep -v "List of devices" | grep "device" | awk '{print $1}')
  echo "Using connected device: $SERIAL"
  ADB_CMD=("$ADB" -s "$SERIAL")
else
  # Multiple devices connected
  echo "Multiple devices detected:"
  "$ADB" devices
  echo ""
  if [ -n "$PHONE" ]; then
    echo "Using specified PHONE=$PHONE"
    ADB_CMD=("$ADB" -s "$PHONE")
  else
    echo "PHONE env var not set. Defaulting to first device..."
    SERIAL=$("$ADB" devices | grep -v "List of devices" | grep "device" | head -n 1 | awk '{print $1}')
    ADB_CMD=("$ADB" -s "$SERIAL")
  fi
fi

OUT="$(dirname "$0")/dash.log"

"${ADB_CMD[@]}" logcat -c          # clear old buffer
echo "Capturing → $OUT   (Ctrl-C to stop)"
"${ADB_CMD[@]}" logcat -v time \
  DashSession:D DashSocket:D DashWifiManager:D TileProvider:I LocationTracker:D \
  NalProcessor:D DashEncoder:D \
  AndroidRuntime:E System.err:W "*:S" | tee "$OUT"
