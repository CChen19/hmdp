#!/usr/bin/env bash
# Seckill smoke / load helper — NEVER treat HTTP accept QPS as order throughput.
#
# Records: requests, HTTP accepts, HTTP business errors (rejects), and optional
# final MySQL success count (via mysql client if DATABASE_URL-ish env set).
#
# Usage (app already running on :8081, logged-in token required):
#   export AUTH_TOKEN='...'   # authorization header from login
#   export VOUCHER_ID=10
#   ./scripts/bench/seckill-smoke.sh          # tiny smoke (default 20 req)
#   ./scripts/bench/seckill-smoke.sh 200      # larger local run
#
# Empty-stock fast-reject mode:
#   export EXPECT_REJECT=1   # treat Result.success=false as expected reject path
#
# Optional final DB count (host MySQL):
#   export MYSQL_CMD="mysql -uroot -p001020 -N -e"
#   # script runs: SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=...

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
N="${1:-20}"
VOUCHER_ID="${VOUCHER_ID:-}"
AUTH_TOKEN="${AUTH_TOKEN:-}"
EXPECT_REJECT="${EXPECT_REJECT:-0}"

if [[ -z "$AUTH_TOKEN" ]]; then
  echo "Set AUTH_TOKEN (login authorization header value)." >&2
  exit 1
fi
if [[ -z "$VOUCHER_ID" ]]; then
  echo "Set VOUCHER_ID (seckill voucher id)." >&2
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "=== seckill bench smoke ==="
echo "base=$BASE_URL voucher=$VOUCHER_ID n=$N expect_reject=$EXPECT_REJECT"
echo "NOTE: accept count is HTTP accept only — not final MySQL throughput."

python3 - "$BASE_URL" "$VOUCHER_ID" "$AUTH_TOKEN" "$N" "$EXPECT_REJECT" "$TMP" <<'PY'
import json, os, sys, time, urllib.request

base, voucher, token, n_s, expect_reject, tmp = sys.argv[1:7]
n = int(n_s)
expect_reject = expect_reject == "1"
url = f"{base.rstrip('/')}/voucher-order/seckill/{voucher}"
lat = []
req_ok = accept = reject = http_err = 0
for i in range(n):
    req = urllib.request.Request(url, method="POST", data=b"", headers={
        "authorization": token,
        "Content-Type": "application/json",
    })
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = resp.read().decode("utf-8", "replace")
            code = resp.status
    except Exception as e:
        http_err += 1
        lat.append((time.perf_counter() - t0) * 1000)
        continue
    lat.append((time.perf_counter() - t0) * 1000)
    req_ok += 1
    try:
        data = json.loads(body)
    except Exception:
        http_err += 1
        continue
    if data.get("success") is True:
        accept += 1
    else:
        reject += 1

lat_sorted = sorted(lat)
def pct(p):
    if not lat_sorted:
        return float("nan")
    idx = min(len(lat_sorted) - 1, max(0, int(round(p * (len(lat_sorted) - 1)))))
    return lat_sorted[idx]

elapsed = sum(lat) / 1000.0 if lat else 0.0
out = {
    "requests": n,
    "http_ok_bodies": req_ok,
    "http_errors": http_err,
    "http_accepts": accept,
    "http_rejects": reject,
    "client_latency_ms": {
        "p50": round(pct(0.50), 3),
        "p95": round(pct(0.95), 3),
        "p99": round(pct(0.99), 3),
    },
    "wall_approx_sec": round(elapsed, 3),
    "note": "Do NOT use http_accepts/sec as order throughput. Query MySQL for final success.",
    "expect_reject_mode": expect_reject,
}
path = os.path.join(tmp, "result.json")
with open(path, "w") as f:
    json.dump(out, f, indent=2)
print(json.dumps(out, indent=2))
PY

if [[ -n "${MYSQL_CMD:-}" ]]; then
  echo "--- final MySQL success count (voucher_id=$VOUCHER_ID) ---"
  eval "$MYSQL_CMD" "SELECT COUNT(*) AS final_success FROM tb_voucher_order WHERE voucher_id=${VOUCHER_ID};" || true
  echo "(Compare final_success to http_accepts — they diverge under lag/backpressure.)"
fi

echo "--- ops snapshot (needs ADMIN token; set ADMIN_TOKEN to scrape) ---"
if [[ -n "${ADMIN_TOKEN:-}" ]]; then
  curl -sS -H "authorization: $ADMIN_TOKEN" "$BASE_URL/ops/snapshot" || true
  echo
fi

echo "Done. Paste numbers into docs/BENCH.md RESULTS; do not invent QPS."
