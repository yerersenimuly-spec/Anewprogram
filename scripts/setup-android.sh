#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if ! command -v java >/dev/null; then
  echo 'Java 21 required. Install a JDK 21 or set JAVA_HOME.' >&2
  exit 1
fi
java_major="$(java -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')"
if [[ "$java_major" != 21 ]]; then
  echo 'Java 21 required. Select a JDK 21 with JAVA_HOME.' >&2
  exit 1
fi
export ANDROID_HOME="${ANDROID_HOME:-$PWD/.android-sdk}"
mkdir -p "$ANDROID_HOME/cmdline-tools" .tools
if [[ ! -x "$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" ]]; then
  curl -fL https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o .tools/android-tools.zip
  unzip -q .tools/android-tools.zip -d .tools/android-tools
  mv .tools/android-tools/cmdline-tools "$ANDROID_HOME/cmdline-tools/19.0"
  rm -rf .tools/android-tools .tools/android-tools.zip
fi
yes | "$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" --licenses >/dev/null || true
"$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358'
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
if [[ ! -f gradlew && ! -x .tools/gradle-8.13/bin/gradle ]]; then
  curl -fL https://services.gradle.org/distributions/gradle-8.13-bin.zip -o .tools/gradle.zip
  unzip -q .tools/gradle.zip -d .tools
  rm .tools/gradle.zip
fi
if [[ ! -f gradlew ]]; then
  .tools/gradle-8.13/bin/gradle wrapper --gradle-version 8.13 --distribution-type bin
fi
