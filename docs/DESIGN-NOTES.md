# Design notes (engineering)

Why this stack stays small — not a Redis-API lecture.

## Why Redis Stream (not Kafka)

- Course and ops stay on **one Redis** already required for stock/session/cache.
- Accept path already `XADD`s in Lua; a second broker would duplicate durability concerns without buying multi-DC fan-out we do not need.
- Consumer group + PEL + `XCLAIM` is enough for crash recovery at tutorial scale.
- Kafka remains valid for multi-service fan-out; **out of scope** here.

## Why no sharding / cluster / multi-level cache

- Single voucher hot key + Lua is the intentional bottleneck to study.
- Redis Cluster / Caffeine / Bloom add failure modes without changing the teaching story.
- Shop cache is **one Redis logical-expire layer** + MySQL origin ([`SHOP-CACHE.md`](SHOP-CACHE.md)).

## Transaction boundaries

| Step | Where | Atomicity |
|------|--------|-----------|
| Stock −−, order set, stream enqueue | Redis Lua (`seckill.lua`) | Single Lua script |
| DB stock −− + `tb_voucher_order` insert | MySQL `@Transactional` in `createVoucherOrder` | Single DB TX; idempotent on order id / `(user,voucher)` |
| ACK Stream message | Redis after SUCCESS/POISON | **After** DB commit; ACK loss → PEL retry (safe due to idempotent insert) |
| Cancel: order status + DB stock ++ + outbox row | MySQL same TX | Redis INCR deferred to outbox worker + Lua SETNX |
| Outbox Redis stock release | `stock_release.lua` | Idempotent done-key |

**Do not** treat Lua accept as paid inventory in MySQL until the consumer commits.

## Sync vs async seckill

Production HTTP path is **async only** (Lua → Stream → consumer). There is no second production sync seckill API. To compare latency:

- **Accept QPS / latency** — client → HTTP accept meter
- **E2E accept→DB** — `hmdp.seckill.accept_to_db` timer (same JVM) or `create_time - accept` via logs/ids

See [`BENCH.md`](BENCH.md).
