# Seckill contract

One operational contract for Lua accept rules, activity window, unique purchase, and reliable Stream consume (PEL / dead-letter / query).

**Accept ≠ final success.** HTTP `Result.ok(orderId)` means Redis accepted the attempt. Final success is a MySQL `tb_voucher_order` insert in the consumer.

---

## Part A — Accept rules (stock, window, unique)

### Redis keys

| Key | Value | Written by |
|-----|--------|------------|
| `seckill:stock:{voucherId}` | remaining stock (string int) | `VoucherServiceImpl.addSeckillVoucher` after DB commit |
| `seckill:begin:{voucherId}` | activity begin (epoch seconds) | same |
| `seckill:end:{voucherId}` | activity end (epoch seconds) | same |
| `seckill:order:{voucherId}` | Redis set of userIds who passed Lua | `seckill.lua` |

Lua (`seckill.lua`) rejects when begin/end are missing, or when server-provided `now` is outside `[begin, end]`. Java also rejects outside the DB window before calling Lua. Missing time metadata = reject (do not open purchase).

### Publish after commit

Redis stock/window is written only in `TransactionSynchronization.afterCommit`. Never before the DB transaction commits.

If Redis write fails after commit: the seckill voucher row exists in MySQL but Redis may be empty. Logs include the exact `SET` rebuild commands. Retry/manual rebuild:

```bash
# Replace id / values from the error log or from tb_seckill_voucher
redis-cli SET seckill:stock:{id} {stock}
redis-cli SET seckill:begin:{id} {beginEpochSeconds}
redis-cli SET seckill:end:{id} {endEpochSeconds}
```

Do not treat “DB published, Redis empty” as success for buyers — purchase will fail until keys exist.

### Post-deploy backfill (existing stock keys)

After deploying Phase 1, vouchers that already had only `seckill:stock:{id}` will **fail-closed** until begin/end exist. Backfill from MySQL:

```bash
# dry-run first
DRY_RUN=1 ./scripts/backfill-seckill-window.sh

# write begin/end (and reset stock from DB) — see script header for SKIP_STOCK=1
./scripts/backfill-seckill-window.sh

# window-only: keep live Redis stock counters, only SET begin/end
SKIP_STOCK=1 ./scripts/backfill-seckill-window.sh
```

Script: `scripts/backfill-seckill-window.sh` (prefixes match `RedisConstants`: `seckill:stock:` / `seckill:begin:` / `seckill:end:`).

### DB insert gate

`createVoucherOrder`:

1. If order id already exists → return (idempotent), no second stock decrement.
2. Decrement with `stock > 0`; **insert only when that update succeeds**.
3. Stock update failure → throw (consumer must not ACK as success).

### Unique (user_id, voucher_id)

`tb_voucher_order` has `UNIQUE (user_id, voucher_id)`. Phase 1: one user, one voucher forever, even after a later cancel.

“Rebuy after cancel” is a Phase 3 product decision and would need a different unique key (e.g. unique only among non-cancelled rows).

### Lua return codes

| Code | Meaning |
|------|---------|
| 0 | ok (stock deducted, user in set, Stream XADD) |
| 1 | insufficient stock |
| 2 | duplicate user for voucher |
| 3 | outside activity window or missing begin/end |

### Lua smoke check (optional, isolated Redis)

Do not point at the developer’s live shared Redis with production keys. Against a throwaway Redis:

```bash
redis-cli SET seckill:stock:9001 1
redis-cli SET seckill:begin:9001 0
redis-cli SET seckill:end:9001 4102444800
redis-cli --eval src/main/resources/seckill.lua , 9001 42 10001 $(date +%s)
# expect 0

redis-cli --eval src/main/resources/seckill.lua , 9001 42 10002 $(date +%s)
# expect 2 (duplicate)

redis-cli DEL seckill:begin:9001
redis-cli --eval src/main/resources/seckill.lua , 9001 43 10003 $(date +%s)
# expect 3 (missing metadata)
```

Unit tests under `src/test/java/com/hmdp/order/` cover `createVoucherOrder` with mocks (no live Redis/DB).

---

## Part B — Reliable consume (Stream → MySQL)

Async path: Lua accept → Redis Stream `stream.orders` → Spring-managed consumer → MySQL.

### Consumer lifecycle

- `SeckillOrderStreamConsumer` implements `SmartLifecycle` (not a raw `@PostConstruct` infinite executor).
- Graceful stop sets `running=false` and interrupts the worker so the blocking `XREADGROUP` (2s) can exit.
- **Consumer name** is unique per JVM: `{pid}-{host}-{uuid8}` (from `RuntimeMXBean` name). Group stays **`g1`**.

### Group init gate

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

### Pending recover + claim

On startup and every ~15s:

1. **Own pending** — `XPENDING` for this consumer, load body via `XRANGE`, process **each id at most once per scan**. Does **not** `XREADGROUP` id `0` (that would re-bump delivery and tight-loop `RETRY` into poison+ACK).
2. `XPENDING` (scan window 100) + `XCLAIM` — take up to 10 idle messages from **other** consumers (own PEL ids are skipped so they cannot hide foreign idle work).

**Min-idle:** `30s` (`RedisConstants.SECKILL_CLAIM_MIN_IDLE_MS`). Raise toward 60s if consumers pause longer than a brief GC.

### Consume outcomes

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

### Result API

| Endpoint | Behavior |
|----------|----------|
| `GET /voucher-order/{id}` | Login + **owner-only**. States: `PROCESSING` (Redis accept, no DB row) / `SUCCESS` (row) / `FAILED` (dead-letter). Non-owner → `Result.fail("无权查看该订单")`. |
| `GET /voucher-order/mine` | Current user’s DB orders + Redis processing set + own dead-letter fails. |

Frontend: poll `GET /voucher-order/{id}` after seckill accept until `SUCCESS` or `FAILED` (UI out of scope).

### Retry returns original order id

Lua keeps `seckill:order:{voucherId}` (set) and also:

- `HSET seckill:order:id:{voucherId} {userId} {orderId}`
- `SETEX seckill:accept:{orderId} 86400 {userId}`
- `SADD seckill:processing:{userId} {orderId}`

On Lua return `2`, HTTP returns the mapped order id (or DB row), not only「禁止重复下单」。Idempotent duplicate accepts **count** as accept but do **not** re-track accept→DB lag for the same order id.

**Sellout caveat:** the Lua script checks stock **before** the duplicate set. Once `seckill:stock:{id}` reaches 0 (and stays 0, e.g. no cancel release), a repeat request from a user who already bought answers `库存不足` (code 1) instead of the original order id (code 2 is never reached). The order itself is safe — `GET /voucher-order/{id}` and `/mine` still return it. Reorder the Lua checks only if the original-id answer must also hold at sellout.

### Known limits

- Accept is recorded in Redis before MySQL. If Redis dies after Lua success and before consume, the buyer has an order id / accept key that may never become a MySQL row (and stock was already decremented in Redis).
- Accept TTL is 24h; after expiry, `PROCESSING` query may look like “not found” even if Stream pending still exists.
- Group created historically with `$` needs `XGROUP SETID` (above) to re-read backlog.
- Default `mvn test` does **not** require a live Redis Stream.
