# Recovery handbook

Ops playbook for seckill Stream, outbox, cache, and Redis outages.

## Consumer crash / restart

1. App stop → `SeckillConsumeGate` closes → new accepts refused.
2. Restart → `XGROUP CREATE … 0-0 MKSTREAM` (BUSYGROUP OK) → gate opens → worker drains **own PEL**, then claims idle from others (~30s min-idle).
3. Idempotent `createVoucherOrder` → safe to re-deliver.

## PEL claim

- Own pending: `XPENDING` + `XRANGE` (not `XREADGROUP 0`) so RETRY cannot tight-loop into poison.
- Foreign idle: `XCLAIM` after `SECKILL_CLAIM_MIN_IDLE_MS` (30s).
- Detail: [`SECKILL-CONSUME.md`](SECKILL-CONSUME.md).

## Dead-letter replay

Poison → row in `seckill_dead_letter` + ACK so the group advances. Replay via `SeckillDeadLetterService.replay(id)` (admin/shell; no public HTTP by default). Fix root cause before replay (bad payload vs missing voucher).

## Outbox stuck PENDING

Table `stock_release_outbox` status `0`. Worker every 5s applies `stock_release.lua` (SETNX done-key + INCR). If stuck:

1. Check Redis connectivity / password.
2. Inspect row `event_key`, voucher id.
3. Confirm done-key not blocking incorrectly; worker marks DONE after successful Lua.
4. Gauge / snapshot: `outboxPending` on `/ops/snapshot`.

## Redis down

| Path | Behavior |
|------|----------|
| Shop browse | Fall back to MySQL or `服务暂时不可用` — **not** false “店铺不存在” |
| Seckill accept | Lua/group fail → gate/errors refuse accepts |
| Session | Login/refresh fails |

## Cache rebuild

Logical expire returns stale + async rebuild under `lock:shop:{id}`. After shop update, cache key deleted **afterCommit**. Cold start: miss → mutex → DB → write logical expire or empty marker.

## Historical `$` consumer group

**Never** create with `$` (skips backlog). If an old group used `$`:

```bash
redis-cli -a "$REDIS_PASSWORD" XGROUP SETID stream.orders g1 0-0
```

See [`SECKILL-CONSUME.md`](SECKILL-CONSUME.md).

## Quick signals

```bash
curl -s http://127.0.0.1:8081/actuator/health
# ADMIN token:
curl -s -H "authorization: $ADMIN_TOKEN" http://127.0.0.1:8081/ops/snapshot
```
