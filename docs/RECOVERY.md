# Recovery handbook

Ops playbook for seckill Stream, outbox, cache, and Redis outages. Includes the fault-drill map to default-suite tests.

## Health ≠ Kubernetes liveness

`GET /actuator/health` aggregates MySQL + Redis + **seckill consume gate**. If only the gate is closed, **aggregate health is DOWN** even though shop browse still works. A kube **liveness** probe on that URL would restart the process.

- Local/dev: `/actuator/health` is a convenience dashboard (`show-details: always`), public by design.
- **Do not** copy it as Kubernetes liveness.
- If you ever probe: liveness = process alive (do not require the seckill gate); readiness for **seckill traffic** may include the gate.
- Do **not** publish port 8081.

## Consumer crash / restart

1. App stop → `SeckillConsumeGate` closes → new accepts refused.
2. Restart → `XGROUP CREATE … 0-0 MKSTREAM` (BUSYGROUP OK) → gate opens → worker drains **own PEL**, then claims idle from others (~30s min-idle).
3. Idempotent `createVoucherOrder` → safe to re-deliver.

## PEL claim

- Own pending: `XPENDING` + `XRANGE` (not `XREADGROUP 0`) so RETRY cannot tight-loop into poison.
- Foreign idle: `XCLAIM` after `SECKILL_CLAIM_MIN_IDLE_MS` (30s).
- Detail: [`SECKILL.md`](SECKILL.md).

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

See [`SECKILL.md`](SECKILL.md).

## Quick signals

```bash
curl -s http://127.0.0.1:8081/actuator/health
# ADMIN token:
curl -s -H "authorization: $ADMIN_TOKEN" http://127.0.0.1:8081/ops/snapshot
```

## Fault drills → tests

Map of reliability scenes to **existing default-suite tests**. Run: `mvn -B test` (no live course DB required for these).

| # | Scene | Expected | How to verify |
|---|--------|----------|---------------|
| 1 | Limited stock, many users | Final DB orders ≤ stock | `CreateVoucherOrderTest.stockUpdateFalse_doesNotSave` (DB deduct fails → no insert). Lua stock gate in `SeckillLuaScriptTest`. Live: compare `COUNT(*)` vs voucher stock after bench. |
| 2 | Same user concurrent same voucher | No double buy | Lua duplicate + `uk_user_voucher`; `CreateVoucherOrderTest` (`existingOrderId_*`, `existingUserVoucher_*`, `duplicateLua_returnsOriginalOrderIdFromRedisMap`) |
| 3 | DB commit then ACK fail | Deduct once; retry confirms | Idempotent `createVoucherOrder` + `PendingRecoverRetryTest` (RETRY left in PEL, no poison burn) |
| 4 | Consumer dies after read | Recover/claim finishes | Own PEL: `PendingRecoverRetryTest` + `SeckillOrderStreamConsumer.recoverOwnPending`. Foreign idle: `claimIdleFromOthers` (XCLAIM 30s) — exercised in production path; see [`SECKILL.md`](SECKILL.md) |
| 5 | Poison message | Later orders not blocked | `SeckillPoisonHandlerTest` (bad payload / max deliveries → DL + ACK path) |
| 6 | Pay vs timeout cancel | One winner; stock released ≤ once | `TradeOrderServiceTest` (pay wins / cancel wins / outbox once) |
| 7 | Concurrent redeem | One audit / one status flip | `TradeOrderServiceTest` redeem cases |
| 8 | Redis cold start / shop update | Existing shop not “不存在” | `CacheClientTest`, `ShopServiceCacheTest` (miss→DB, empty marker, Redis down → unavailable vs not-found) |

Demo/warmup tests (`@Tag("demo")`) are **excluded** from default CI and are not required for these drills.
