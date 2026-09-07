#!/usr/bin/env bash
# Captures one demo page from the Android emulator and the iOS Simulator, then compares both
# against a headless render of the same document (see HarnessMain).
#
#   tools/capture-screens.sh <page> [more pages...]
#
# A page is the index DemoScreen shows: 0 coverage, 1 showcase, 2 paint, 3 anim, 4 actions,
# 5 text paths, 6 generated, 8 list, 9 pattern. Page 3 (anim) moves with the clock and page 7 (material) is scaled
# to the screen by RemoteComposeCanvas, so neither is pixel-comparable this way and both are
# refused rather than silently compared.
#
# Environment:
#   ANDROID_SERIAL  adb device to use (default: the only attached one)
#   IOS_UDID        simulator to use (default: the booted one)
#   SKIP_ANDROID=1  compare iOS only
#   SKIP_IOS=1      compare Android only
set -euo pipefail

cd "$(dirname "$0")/.."
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ANDROID_PACKAGE=com.example.remotecompose.androidapp
IOS_BUNDLE=com.example.remotecompose.demo
OUT=build/pixel-harness
mkdir -p "$OUT"

fixture_for_page() {
  case "$1" in
    0) echo tools/rc-writer/sample.rc ;;
    1) echo tools/rc-writer/showcase.rc ;;
    2) echo tools/rc-writer/paint.rc ;;
    4) echo tools/rc-writer/actions.rc ;;
    5) echo tools/rc-writer/textpath.rc ;;
    6) echo tools/rc-writer/advanced.rc ;;
    8) echo tools/rc-writer/list.rc ;;
    9) echo tools/rc-writer/pattern.rc ;;
    10) echo "page 10 (coffee) wraps its text and follows the clock, so neither its layout nor its content is the same twice" >&2; return 1 ;;
    3) echo "page 3 (anim) changes with the clock, so it has no fixed reference" >&2; return 1 ;;
    7) echo "page 7 (material) is scaled to the screen, so it is not pixel-comparable" >&2; return 1 ;;
    *) echo "unknown page $1" >&2; return 1 ;;
  esac
}

capture_android() {
  local page=$1 out=$2
  "$ADB" shell am force-stop "$ANDROID_PACKAGE" >/dev/null
  "$ADB" shell am start -n "$ANDROID_PACKAGE/.MainActivity" --ei page "$page" >/dev/null
  sleep 3
  "$ADB" exec-out screencap -p > "$out"
}

capture_ios() {
  local page=$1 out=$2 udid=${IOS_UDID:-booted}
  xcrun simctl terminate "$udid" "$IOS_BUNDLE" >/dev/null 2>&1 || true
  SIMCTL_CHILD_RC_PAGE="$page" xcrun simctl launch "$udid" "$IOS_BUNDLE" >/dev/null
  sleep 4
  xcrun simctl io "$udid" screenshot --type=png "$out" >/dev/null 2>&1
}

# Differences that are real, understood, and not this renderer's to fix. Page 5's orange bar is
# sized by TEXT_MEASURE, so it is as wide as the platform measures "measure me": 70 pixels here
# and 67 on Android, which is 8 pixels of edge. A document that asks for the width of a string
# gets a different answer where the fonts are different, and that is the document working.
allowance_for_page() {
  case "$1" in
    5) echo 8 ;;
    *) echo 0 ;;
  esac
}

status=0
for page in "$@"; do
  fixture=$(fixture_for_page "$page") || { status=1; continue; }
  name=$(basename "$fixture" .rc)
  allow=$(allowance_for_page "$page")
  echo "=== page $page ($name)"
  candidates=()
  if [ "${SKIP_ANDROID:-0}" != "1" ]; then
    capture_android "$page" "$OUT/$name-android-screen.png"
    candidates+=("android=$OUT/$name-android-screen.png")
  fi
  if [ "${SKIP_IOS:-0}" != "1" ]; then
    capture_ios "$page" "$OUT/$name-ios-screen.png"
    candidates+=("ios=$OUT/$name-ios-screen.png")
  fi
  ./gradlew -q pixelHarness --args="$fixture ${candidates[*]} --allow-shape-pixels $allow" || status=1
done
exit $status
