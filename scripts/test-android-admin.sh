#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$PWD/.android-sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
mkdir -p .tools/admin-device app/src/debug/res/{xml,raw}
cleanup() {
  [[ -n "${server_pid:-}" ]] && kill "$server_pid" 2>/dev/null || true
  rm -f app/src/debug/AndroidManifest.xml app/src/debug/res/xml/local_test_security.xml app/src/debug/res/raw/local_test_ca.pem
}
trap cleanup EXIT
openssl req -x509 -newkey rsa:2048 -nodes -keyout .tools/admin-device/key.pem -out .tools/admin-device/cert.pem \
  -days 1 -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost' >/dev/null 2>&1
cp .tools/admin-device/cert.pem app/src/debug/res/raw/local_test_ca.pem
cat > app/src/debug/AndroidManifest.xml <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <application android:networkSecurityConfig="@xml/local_test_security" />
</manifest>
EOF
cat > app/src/debug/res/xml/local_test_security.xml <<'EOF'
<network-security-config><domain-config><domain>localhost</domain>
  <trust-anchors><certificates src="@raw/local_test_ca" /></trust-anchors>
</domain-config></network-security-config>
EOF
pin="sha256/$(openssl x509 -in .tools/admin-device/cert.pem -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | openssl base64 -A)"
node scripts/admin-device-server.mjs > .tools/admin-device/server.log 2>&1 &
server_pid=$!
for _ in $(seq 1 50); do
  if curl -fsS --cacert .tools/admin-device/cert.pem https://localhost:8443/ >/dev/null 2>&1; then break; fi
  sleep 0.2
done
adb reverse tcp:8443 tcp:8443
./gradlew assembleDebug assembleDebugAndroidTest --console=plain
adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm clear app.line
adb shell am instrument -w -r -e class app.line.AdminAccessTest -e pin "$pin" \
  app.line.test/androidx.test.runner.AndroidJUnitRunner | tee .tools/admin-device/result.log
grep -Eq '^OK \([0-9]+ tests?\)' .tools/admin-device/result.log
