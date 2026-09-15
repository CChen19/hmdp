#!/usr/bin/env bash
# Shop cache cold vs hot smoke. Counts via /actuator/metrics when available.
#
# Usage:
#   export SHOP_ID=1
#   ./scripts/bench/shop-cache-smoke.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
SHOP_ID="${SHOP_ID:-1}"
N_HOT="${1:-10}"

echo "=== shop cache smoke ==="
echo "base=$BASE_URL shop=$SHOP_ID hot_repeats=$N_HOT"

metric() {
  local name="$1"
  curl -sS "$BASE_URL/actuator/metrics/$name" 2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('measurements',[{}])[0].get('value','?'))" \
    2>/dev/null || echo "n/a"
}

echo "before: hit=$(metric hmdp.shop.cache.hit) miss=$(metric hmdp.shop.cache.miss) origin=$(metric hmdp.shop.cache.origin)"

# Cold: delete Redis key if redis-cli available (optional)
if command -v redis-cli >/dev/null 2>&1; then
  redis-cli -a "${REDIS_PASSWORD:-001020}" DEL "cache:shop:${SHOP_ID}" >/dev/null 2>&1 || true
  echo "cleared cache:shop:${SHOP_ID} (if Redis reachable)"
fi

echo -n "cold GET: "
curl -sS -o /tmp/hmdp-shop-cold.json -w "http=%{http_code} time_ms=%{time_total}\n" \
  "$BASE_URL/shop/$SHOP_ID" || true

echo "after cold: hit=$(metric hmdp.shop.cache.hit) miss=$(metric hmdp.shop.cache.miss) origin=$(metric hmdp.shop.cache.origin)"

for i in $(seq 1 "$N_HOT"); do
  curl -sS -o /dev/null "$BASE_URL/shop/$SHOP_ID" || true
done

echo "after hot x$N_HOT: hit=$(metric hmdp.shop.cache.hit) miss=$(metric hmdp.shop.cache.miss) origin=$(metric hmdp.shop.cache.origin)"
echo "Expect: cold bumps miss+origin; hot bumps hit."
