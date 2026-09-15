#!/usr/bin/env bash
# Print actuator + ops snapshot pointers for a running local app.
set -euo pipefail
BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
echo "health:"; curl -sS "$BASE_URL/actuator/health" | python3 -m json.tool 2>/dev/null || curl -sS "$BASE_URL/actuator/health"
echo
echo "seckill meters (sample):"
for m in hmdp.seckill.request hmdp.seckill.accept hmdp.seckill.reject hmdp.seckill.final_success; do
  echo -n "  $m = "
  curl -sS "$BASE_URL/actuator/metrics/$m" 2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('measurements',[{}])[0].get('value','?'))" 2>/dev/null || echo "?"
done
if [[ -n "${ADMIN_TOKEN:-}" ]]; then
  echo "ops snapshot:"
  curl -sS -H "authorization: $ADMIN_TOKEN" "$BASE_URL/ops/snapshot" | python3 -m json.tool
fi
