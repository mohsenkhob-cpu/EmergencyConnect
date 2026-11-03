#!/usr/bin/env bash
set -euo pipefail

BRIDGE="overlay/app/src/main/java/ir/emergencyconnect/engine/OpenVpn3Bridge.java"
SERVICE="overlay/app/src/main/java/ir/emergencyconnect/engine/RahYabVpnService.java"
JNI="overlay/native/android-jni/jni_bridge.cpp"

for file in "$BRIDGE" "$SERVICE" "$JNI"; do
  test -f "$file" || { echo "Missing required source: $file"; exit 1; }
done

# Do not advertise a usable VPN session until native start/stop lifecycle
# and real Android traffic tests have been completed.
grep -Eq 'SESSION_API_READY[[:space:]]*=[[:space:]]*false' "$BRIDGE"
grep -q 'رابط نشست و تونل اندروید هنوز کامل و آزمایش نشده' "$BRIDGE"
grep -Fq 'OpenVpn3Bridge.startSession(profile)' "$SERVICE"\ngrep -Fq 'OpenVpn3Bridge.stopSession()' "$SERVICE"\ngrep -Fq 'EXTRA_PROFILE' "$SERVICE"\ngrep -Fq 'VpnService' overlay/app/src/main/java/ir/emergencyconnect/MainActivity.java
if grep -Eq 'SESSION_API_READY[[:space:]]*=[[:space:]]*true' "$BRIDGE"; then
  echo "ERROR: session readiness was enabled without required verification."
  exit 1
fi

# Keep Java native declarations and exported JNI entry points in sync.
for method in nativeCryptoSelfTest nativeValidateProfile nativeBindTunService nativeUnbindTunService nativeStartSession nativeStopSession; do
  grep -Eq "native[[:space:]].*\\b${method}[[:space:]]*\\(" "$BRIDGE" || {
    echo "Missing Java native declaration: ${method}"; exit 1;
  }
  grep -q "Java_ir_emergencyconnect_engine_OpenVpn3Bridge_${method}" "$JNI" || {
    echo "Missing JNI implementation: ${method}"; exit 1;
  }
done

# Verify that the JNI bridge requests the exact full TUN callback overload
# currently implemented by RahYabVpnService.
grep -Fq 'establishTun' "$SERVICE"
grep -Fq '(Ljava/lang/String;[Ljava/lang/String;[I[Ljava/lang/String;[Ljava/lang/String;[I[Ljava/lang/String;[II)I' "$JNI"
grep -Fq 'protectTransportSocket' "$SERVICE"
grep -Fq 'protectTransportSocket' "$JNI"

# Session worker must be joinable, stoppable, and never detached.
grep -Fq 'client->connect()' "$JNI"
grep -Fq 'client->stop()' "$JNI"
grep -Fq 'session->worker.join()' "$JNI"
if grep -Fq 'detach()' "$JNI"; then
  echo "ERROR: detached native VPN worker is forbidden."
  exit 1
fi
grep -Fq 'evaluated.autologin' "$JNI"
grep -Fq 'nativeStopSession' "$BRIDGE"

echo "PASS: readiness safety and Java/JNI callback contracts verified."
