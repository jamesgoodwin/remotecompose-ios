#!/usr/bin/env bash
# Captures one demo page from the Android emulator and the iOS Simulator, then compares both
# against a headless render of the same document (see HarnessMain).
#
#   tools/capture-screens.sh <fixture> [more fixtures...]
#
# A fixture is the name of a file in tools/rc-writer, without the .rc: sample, showcase, paint,
# actions, textpath, advanced, list, pattern. The demo app is asked for it by that name, so
# reordering the list in DemoScreen does not move anything here.
#
# Not every fixture can be compared this way, and the ones that cannot are refused rather than
# silently mismatched: anim moves with the clock, coffee wraps its text and follows the clock, and
# material is a document meant to be scaled to the screen.
#
# Environment:
#   ANDROID_SERIAL  adb device to use (default: the only attached one)
#   IOS_UDID        simulator to use (default: the booted one)
#   SKIP_ANDROID=1  compare iOS only
#   SKIP_IOS=1      compare Android only
set -euo pipefail

cd "$(dirname "$0")/.."
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ANDROID_PACKAGE=io.github.jamesgoodwin.remotecompose.androidapp
IOS_BUNDLE=io.github.jamesgoodwin.remotecompose.demo
OUT=build/pixel-harness
mkdir -p "$OUT"

fixture_for_name() {
  case "$1" in
    sample|showcase|paint|actions|textpath|advanced|list|pattern|shadow|shader|font)
      echo "tools/rc-writer/$1.rc" ;;
    anim) echo "anim changes with the clock, so it has no fixed reference" >&2; return 1 ;;
    coffee) echo "coffee wraps its text and follows the clock, so neither its layout nor its content is the same twice" >&2; return 1 ;;
    material) echo "material is meant to be scaled to the screen, so it is not pixel-comparable" >&2; return 1 ;;
    *) echo "no comparison for $1" >&2; return 1 ;;
  esac
}

capture_android() {
  local name=$1 out=$2
  "$ADB" shell am force-stop "$ANDROID_PACKAGE" >/dev/null
  # Unscaled, because the comparison is pixel-for-pixel: the app itself draws a page scaled up
  # to the screen, and comparing a resampled render to a resampled screenshot would compare the
  # resampling.
  "$ADB" shell am start -n "$ANDROID_PACKAGE/.MainActivity" --es demo "$name" --ez oneToOne true >/dev/null
  sleep 3
  "$ADB" exec-out screencap -p > "$out"
}

capture_ios() {
  local name=$1 out=$2 udid=${IOS_UDID:-booted}
  xcrun simctl terminate "$udid" "$IOS_BUNDLE" >/dev/null 2>&1 || true
  SIMCTL_CHILD_RC_DEMO="$name" SIMCTL_CHILD_RC_ONE_TO_ONE=1 xcrun simctl launch "$udid" "$IOS_BUNDLE" >/dev/null
  sleep 4
  xcrun simctl io "$udid" screenshot --type=png "$out" >/dev/null 2>&1
}

# Differences that are real, understood, and not this renderer's to fix. textpath's orange bar is
# sized by TEXT_MEASURE, so it is as wide as the platform measures "measure me": 70 pixels here
# and 67 on Android, which is 8 pixels of edge. A document that asks for the width of a string
# gets a different answer where the fonts are different, and that is the document working.
allowance_for_name() {
  case "$1" in
    textpath) echo 8 ;;
    *) echo 0 ;;
  esac
}

status=0
for name in "$@"; do
  fixture=$(fixture_for_name "$name") || { status=1; continue; }
  allow=$(allowance_for_name "$name")
  echo "=== $name"
  candidates=()
  if [ "${SKIP_ANDROID:-0}" != "1" ]; then
    capture_android "$name" "$OUT/$name-android-screen.png"
    candidates+=("android=$OUT/$name-android-screen.png")
  fi
  if [ "${SKIP_IOS:-0}" != "1" ]; then
    capture_ios "$name" "$OUT/$name-ios-screen.png"
    candidates+=("ios=$OUT/$name-ios-screen.png")
  fi
  ./gradlew -q pixelHarness --args="$fixture ${candidates[*]} --allow-shape-pixels $allow" || status=1
done
exit $status
