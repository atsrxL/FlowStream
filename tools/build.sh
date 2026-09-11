#!/usr/bin/env bash
# Bootstraps the official Gradle distribution. Requires installed Android SDK packages.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v java >/dev/null || { echo '缺少 JDK；请安装 JDK 17。' >&2; exit 2; }
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" && -f local.properties ]]; then SDK="$(sed -n 's/^sdk.dir=//p' local.properties | head -1)"; fi
if [[ -z "$SDK" ]]; then
  for candidate in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [[ -d "$candidate" ]]; then SDK="$candidate"; break; fi
  done
fi
if [[ -z "$SDK" || ! -f "$SDK/platforms/android-36/android.jar" ]]; then
  echo '未找到 Android SDK 36。请先用 Android Studio SDK Manager 安装 Android SDK Platform 36 和 Build-Tools 35.0.0。' >&2
  echo '然后设置 ANDROID_HOME / ANDROID_SDK_ROOT，或在项目根目录的 local.properties 写入 sdk.dir=/你的/SDK/路径。' >&2
  exit 2
fi
export ANDROID_HOME="$SDK"
VERSION=8.13
CACHE="${GRADLE_BOOTSTRAP_HOME:-$HOME/.cache/flowstream-gradle}"
GRADLE="$CACHE/gradle-$VERSION/bin/gradle"
if [[ ! -x "$GRADLE" ]]; then
  command -v curl >/dev/null || { echo '缺少 curl。' >&2; exit 2; }
  command -v unzip >/dev/null || { echo '缺少 unzip。' >&2; exit 2; }
  mkdir -p "$CACHE"
  URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
  curl --fail --location --retry 2 "$URL" -o "$CACHE/gradle.zip"
  curl --fail --location --retry 2 "$URL.sha256" -o "$CACHE/gradle.zip.sha256"
  expected="$(tr -d '\r\n ' < "$CACHE/gradle.zip.sha256")"
  if command -v sha256sum >/dev/null; then actual="$(sha256sum "$CACHE/gradle.zip" | cut -d' ' -f1)";
  else actual="$(shasum -a 256 "$CACHE/gradle.zip" | cut -d' ' -f1)"; fi
  [[ "$expected" =~ ^[a-fA-F0-9]{64}$ && "$actual" == "$expected" ]] || { echo 'Gradle SHA-256 校验失败，停止执行。' >&2; exit 3; }
  unzip -q -o "$CACHE/gradle.zip" -d "$CACHE"
  rm "$CACHE/gradle.zip"
fi
# This creates the real Gradle Wrapper JAR/scripts, never a handwritten imitation.
if [[ ! -f gradle/wrapper/gradle-wrapper.jar ]]; then
  "$GRADLE" --no-daemon wrapper --gradle-version "$VERSION" --distribution-type bin
  if [[ -f "$CACHE/gradle.zip.sha256" ]]; then
    printf '\ndistributionSha256Sum=%s\n' "$(tr -d '\r\n ' < "$CACHE/gradle.zip.sha256")" >> gradle/wrapper/gradle-wrapper.properties
  fi
fi
if [[ $# -eq 0 ]]; then set -- :core:selfTest :app:lintDebug :app:assembleDebug; fi
"$GRADLE" --no-daemon "$@"
printf '\n构建成功。默认 Debug APK: app/build/outputs/apk/debug/app-debug.apk\n'
