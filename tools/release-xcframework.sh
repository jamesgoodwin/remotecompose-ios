#!/usr/bin/env bash
#
# Builds the XCFramework the Swift package points at, and writes the two lines in Package.swift
# that point at it.
#
#     tools/release-xcframework.sh v0.1.0
#
# Leaves the zip in build/XCFrameworks/ and stops. Uploading it is a separate, deliberate step:
#
#     gh release create v0.1.0 build/XCFrameworks/RemoteComposeShared.xcframework.zip
#
# The checksum written into Package.swift is of the file this produced, so the release must carry
# that exact file. Rebuilding produces a different one — Kotlin/Native does not link
# reproducibly — so build once, commit, and upload what you built.
set -euo pipefail

TAG=${1:-}
if [ -z "$TAG" ]; then
  echo "usage: $0 <tag>   e.g. $0 v0.1.0" >&2
  exit 2
fi

REPO_URL=$(git config --get remote.origin.url | sed -e 's/\.git$//' -e 's|git@github.com:|https://github.com/|')
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

OUT=build/XCFrameworks
FRAMEWORK=$OUT/release/RemoteComposeShared.xcframework
ZIP=$OUT/RemoteComposeShared.xcframework.zip

echo "==> assembling (three Apple targets, release — this takes a while)"
./gradlew assembleRemoteComposeSharedReleaseXCFramework

[ -d "$FRAMEWORK" ] || { echo "no framework at $FRAMEWORK" >&2; exit 1; }

echo "==> zipping"
rm -f "$ZIP"
# ditto rather than zip: a framework is symlinks and bundle metadata, and zip flattens both.
ditto -c -k --sequesterRsrc --keepParent "$FRAMEWORK" "$ZIP"

CHECKSUM=$(swift package compute-checksum "$ZIP")
URL="$REPO_URL/releases/download/$TAG/RemoteComposeShared.xcframework.zip"

echo "==> pointing Package.swift at $TAG"
# The url and checksum lines are rewritten in place, so the committed manifest always describes a
# real artifact rather than a placeholder someone has to remember to fill in.
/usr/bin/sed -i '' \
  -e "s|url: \"https://github.com/.*/releases/download/.*/RemoteComposeShared.xcframework.zip\"|url: \"$URL\"|" \
  -e "s|checksum: \"[0-9a-f]*\"|checksum: \"$CHECKSUM\"|" \
  Package.swift

printf '\n  zip       %s (%s)\n  checksum  %s\n  url       %s\n\n' \
  "$ZIP" "$(du -h "$ZIP" | cut -f1)" "$CHECKSUM" "$URL"
echo "Commit Package.swift, tag $TAG, then:"
echo "  gh release create $TAG $ZIP --title $TAG"
