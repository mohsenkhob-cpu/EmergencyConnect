#!/usr/bin/env bash
set -euo pipefail

BRIDGE="overlay/app/src/main/java/ir/emergencyconnect/engine/OpenVpn3Bridge.java"
SERVICE="overlay/app/src/main/java/ir/emergencyconnect/engine/RahYabVpnService.java"

test -f "$BRIDGE"
test -f "$SERVICE"

# Do not advertise a usable VPN session until native start/stop lifecycle
# and real Android traffic tests have been completed.
grep -Eq 'SESSION_API_READY[[:space:]]*=[[:space:]]*false' "$BRIDGE"
grep -q 'رابط نشست و تونل اندروید هنوز کامل و آزمایش نشده' "$BRIDGE"
grep -q 'سرویس آماده است؛ اتصال داخلی هنوز فعال نشده' "$SERVICE"

if grep -Eq 'SESSION_API_READY[[:space:]]*=[[:space:]]*true' "$BRIDGE"; then
  echo "ERROR: session readiness was enabled without the required verification."
  exit 1
fi

echo "PASS: VPN session readiness remains safely disabled."
