#!/usr/bin/env bash
set -Eeuo pipefail
# Build Espelho Remoto on Ubuntu (EC2 or other host). Run from any directory.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export DEBIAN_FRONTEND=noninteractive
echo "[1/6] Installing prerequisites"
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk curl unzip git ca-certificates
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
mkdir -p "$ANDROID_HOME/cmdline-tools" "$HOME/.local/gradle"
echo "[2/6] Android command line tools"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fL --retry 3 -o /tmp/android-tools.zip 'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip'
  mkdir -p /tmp/espelho-android-tools
  unzip -oq /tmp/android-tools.zip -d /tmp/espelho-android-tools
  mv /tmp/espelho-android-tools/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
fi
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$HOME/.local/gradle/gradle-8.9/bin:$PATH"
echo "[3/6] Android SDK"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
sdkmanager --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
echo "[4/6] Gradle 8.9"
if [ ! -x "$HOME/.local/gradle/gradle-8.9/bin/gradle" ]; then
  curl -fL --retry 3 -o /tmp/gradle-8.9-bin.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip
  unzip -oq /tmp/gradle-8.9-bin.zip -d "$HOME/.local/gradle"
fi
echo "[5/6] Build APK"
cd "$ROOT"
gradle --no-daemon --stacktrace --console=plain assembleDebug
echo "[6/6] APK ready: $ROOT/app/build/outputs/apk/debug/app-debug.apk"
ls -lh "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
