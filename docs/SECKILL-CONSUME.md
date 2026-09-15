# Seckill reliable consume (Phase 2)

Async path: Lua accept → Redis Stream `stream.orders` → Spring-managed consumer → MySQL.

## Consumer lifecycle

- `SeckillOrderStreamConsumer` implements `SmartLifecycle` (not a raw `@PostConstruct` infinite executor).
- Graceful stop sets `running=false` and interrupts the worker so the blocking `XREADGROUP` (2s) can exit.
- **Consumer name** is unique per JVM: `{pid}-{host}-{uuid8}` (from `RuntimeMXBean` name). Group stays **`g1`**.

## Group init gate

On start the consumer runs idempotent:

```text
XGROUP CREATE stream.orders g1 0-0 MKSTREAM
```

| Offset | Meaning |
|--------|---------|
| `0-0` / `0` | First create reads **existing** stream entries (needed after deploy with backlog). |
| `$` | **Do not use** for first create — skips all history already in the stream. |

`BUSYGROUP` (group already exists) is treated as success.

If create fails for any other reason, `SeckillConsumeGate` stays closed and **`POST /voucher-order/seckill/{id}` refuses** new accepts (`秒杀服务未就绪`).

If an old ops run created the group with `$` and you still have unread history, fix with:

```bash
redis-cli XGROUP SETID stream.orders g1 0-0
```

## Pending recover + claim

On startup and every ~15s:

1. `XREADGROUP` with id `0` — process **this** consumer’s pending list.
2. `XPENDING` + `XCLAIM` — take idle messages from **dead** consumers (Spring Data Redis 2.7 has no `XAUTOCLAIM` wrapper; same effect).

**Min-idle:** `30s` (`RedisConstants.SECKILL_CLAIM_MIN_IDLE_MS`). Raise toward 60s if consumers pause longer than a brief GC.

## Consume outcomes

| Case | Action |
|------|--------|
| Same order id already in DB | Success, ACK, no second stock deduct |
| Same user+voucher row exists | Success, ACK, no deduct |
| Transient (DB timeout, lock busy, stock race) | No ACK, retry |
| Poison (bad payload, missing voucher, max deliveries ≥ 5) | Insert `seckill_dead_letter`, ACK |

### Dead letter / replay

Table: `seckill_dead_letter` (Flyway V4).

Replay entry point (admin / ops):

```java
seckillDeadLetterService.replay(deadLetterRowId);
```

Re-`XADD`s stored fields to `stream.orders`.

## Result API

| Endpoint | Behavior |
|----------|----------|
| `GET /voucher-order/{id}` | Login + **owner-only**. States: `PROCESSING` (Redis accept, no DB row) / `SUCCESS` (row) / `FAILED` (dead-letter). Non-owner → `Result.fail("无权查看该订单")`. |
| `GET /voucher-order/mine` | Current user’s DB orders + Redis processing set + own dead-letter fails. |

Frontend: poll `GET /voucher-order/{id}` after seckill accept until `SUCCESS` or `FAILED` (UI out of scope).

## Retry returns original order id

Lua keeps `seckill:order:{voucherId}` (set) and also:

- `HSET seckill:order:id:{voucherId} {userId} {orderId}`
- `SETEX seckill:accept:{orderId} 86400 {userId}`
- `SADD seckill:processing:{userId} {orderId}`

On Lua return `2`, HTTP returns the mapped order id (or DB row), not only「禁止重复下单」.

## Known limits

- Accept is recorded in Redis before MySQL. If Redis dies after Lua success and before consume, the buyer has an order id / accept key that may never become a MySQL row (and stock was already decremented in Redis).
- Accept TTL is 24h; after expiry, `PROCESSING` query may look like “not found” even if Stream pending still exists.
- Group created historically with `$` needs `XGROUP SETID` (above) to re-read backlog.
- Default `mvn test` does **not** require a live Redis Stream.
