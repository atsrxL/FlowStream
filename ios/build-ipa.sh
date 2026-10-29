#!/usr/bin/env bash
# Builds an UNSIGNED FlowStream.ipa for iPhone / iPad (re-sign with AltStore, Sideloadly, TrollStore, or your own profile).
# Requires: Xcode (iOS SDK), xcodegen (brew install xcodegen). Usage: [BUILD_NUMBER=n] bash ios/build-ipa.sh [out-dir]
set -euo pipefail
cd "$(dirname "$0")"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
OUT="${1:-$PWD/../dist}"
BUILD="$(mktemp -d)"
xcodegen generate
xcodebuild -project FlowStream.xcodeproj -target FlowStream -sdk iphoneos -configuration Release \
  SYMROOT="$BUILD/Products" OBJROOT="$BUILD/Obj" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  ${BUILD_NUMBER:+CURRENT_PROJECT_VERSION=$BUILD_NUMBER} build
APP="$BUILD/Products/Release-iphoneos/FlowStream.app"
VERSION="$(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' "$APP/Info.plist")"
mkdir -p "$OUT" "$BUILD/ipa/Payload"
cp -R "$APP" "$BUILD/ipa/Payload/"
(cd "$BUILD/ipa" && zip -qry "$OUT/FlowStream-$VERSION-ios-unsigned.ipa" Payload)
echo "IPA: $OUT/FlowStream-$VERSION-ios-unsigned.ipa"
