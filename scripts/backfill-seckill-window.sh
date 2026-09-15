#!/usr/bin/env bash
# Backfill Redis seckill stock + activity window from tb_seckill_voucher.
#
# Why: after Phase 1 deploy, existing seckill:stock:* keys have no sibling
# seckill:begin:* / seckill:end:* → Lua fail-closes (return 3). Run once post-deploy.
#
# Key prefixes match RedisConstants:
#   SECKILL_STOCK_KEY      = seckill:stock:
#   SECKILL_BEGIN_TIME_KEY = seckill:begin:
#   SECKILL_END_TIME_KEY   = seckill:end:
#
# Usage (from repo root or any cwd):
#   ./scripts/backfill-seckill-window.sh
#   DRY_RUN=1 ./scripts/backfill-seckill-window.sh
#
# Env (defaults match local application.yaml):
#   MYSQL_HOST MYSQL_PORT MYSQL_USER MYSQL_PASSWORD MYSQL_DB
#   REDIS_HOST REDIS_PORT REDIS_PASSWORD
#   DRY_RUN=1  — print SETs only, do not write Redis
#   SKIP_STOCK=1 — only SET begin/end (preserve existing Redis stock counters)
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-001020}"
MYSQL_DB="${MYSQL_DB:-hmdp}"

REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_PASSWORD="${REDIS_PASSWORD:-001020}"

DRY_RUN="${DRY_RUN:-0}"
SKIP_STOCK="${SKIP_STOCK:-0}"

STOCK_PREFIX="seckill:stock:"
BEGIN_PREFIX="seckill:begin:"
END_PREFIX="seckill:end:"

if ! command -v mysql >/dev/null 2>&1; then
  echo "mysql client not found" >&2
  exit 1
fi
if ! command -v redis-cli >/dev/null 2>&1; then
  echo "redis-cli not found" >&2
  exit 1
fi

redis_cli() {
  if [ -n "${REDIS_PASSWORD}" ]; then
    redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -a "$REDIS_PASSWORD" --no-auth-warning "$@"
  else
    redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" "$@"
  fi
}

echo "Reading ${MYSQL_DB}.tb_seckill_voucher from ${MYSQL_HOST}:${MYSQL_PORT} ..."
# UNIX_TIMESTAMP → epoch seconds (same unit Lua/Java compare against)
ROWS="$(
  MYSQL_PWD="${MYSQL_PASSWORD}" mysql \
    -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" "$MYSQL_DB" \
    -N -B -e \
    "SELECT voucher_id, stock, UNIX_TIMESTAMP(begin_time), UNIX_TIMESTAMP(end_time)
     FROM tb_seckill_voucher
     WHERE begin_time IS NOT NULL AND end_time IS NOT NULL"
)"

if [ -z "${ROWS}" ]; then
  echo "No seckill voucher rows to backfill."
  exit 0
fi

count=0
while IFS=$'\t' read -r voucher_id stock begin_epoch end_epoch; do
  [ -z "${voucher_id}" ] && continue
  stock_key="${STOCK_PREFIX}${voucher_id}"
  begin_key="${BEGIN_PREFIX}${voucher_id}"
  end_key="${END_PREFIX}${voucher_id}"

  if [ "$DRY_RUN" = "1" ]; then
    if [ "$SKIP_STOCK" != "1" ]; then
      echo "DRY_RUN SET ${stock_key} ${stock}"
    fi
    echo "DRY_RUN SET ${begin_key} ${begin_epoch}"
    echo "DRY_RUN SET ${end_key} ${end_epoch}"
  else
    if [ "$SKIP_STOCK" != "1" ]; then
      redis_cli SET "$stock_key" "$stock" >/dev/null
    fi
    redis_cli SET "$begin_key" "$begin_epoch" >/dev/null
    redis_cli SET "$end_key" "$end_epoch" >/dev/null
    echo "OK voucher_id=${voucher_id} stock=${stock} begin=${begin_epoch} end=${end_epoch}"
  fi
  count=$((count + 1))
done <<< "$ROWS"

echo "Backfilled ${count} voucher(s). DRY_RUN=${DRY_RUN} SKIP_STOCK=${SKIP_STOCK}"
if [ "$SKIP_STOCK" != "1" ] && [ "$DRY_RUN" != "1" ]; then
  echo "Note: Redis stock was reset from DB. If Lua had already decremented ahead of DB, prefer re-run with SKIP_STOCK=1 next time for window-only repair."
fi
