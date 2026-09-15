# Current production behavior

This file is the **index of what the running app does now**, versus course teaching notes and explicit out-of-scope ideas.

## Production (this repo on `master` / evidence branches)

| Area | Behavior | Detail |
|------|----------|--------|
| Auth | SMS code login, Redis session, roles USER/MERCHANT/ADMIN | [`AUTH-BOUNDARIES.md`](AUTH-BOUNDARIES.md) |
| Shop browse | Logical-expire Redis cache; miss → MySQL; Redis/DB fail → unavailable (not “不存在”) | [`SHOP-CACHE.md`](SHOP-CACHE.md) |
| Seckill accept | Lua stock + one-order-per-user → Redis Stream `stream.orders`; gate refuses if consumer group not ready | [`SECKILL-RULES.md`](SECKILL-RULES.md), [`SECKILL-CONSUME.md`](SECKILL-CONSUME.md) |
| Seckill consume | SmartLifecycle consumer, PEL recover, XCLAIM, dead-letter, then MySQL order | [`SECKILL-CONSUME.md`](SECKILL-CONSUME.md) |
| Trade | Simulated pay, unpaid timeout cancel + stock-release outbox, merchant redeem | [`TRADE-FLOW.md`](TRADE-FLOW.md) |
| Ops | Actuator health/metrics; ADMIN `/ops/snapshot`; bench scripts | [`BENCH.md`](BENCH.md), [`RECOVERY.md`](RECOVERY.md) |

**Accept ≠ final success.** HTTP `Result.ok(orderId)` means Redis accepted the attempt. **Final success** is a MySQL `tb_voucher_order` insert in the consumer (`hmdp.seckill.final_success`).

### Actuator & ops paths

| Path | Auth | Notes |
|------|------|-------|
| `GET /actuator/health` | Public | Includes `db`, `redis`, `seckillConsumeGate` (DOWN when gate closed) |
| `GET /actuator/info` | Public | Build/app info |
| `GET /actuator/metrics/**` | Public (local) | Micrometer; **do not** expose 8081 on the internet |
| `GET /actuator/prometheus` | Public (local) | Optional scrape format; no Prometheus server shipped |
| `GET /ops/snapshot` | Login + **ADMIN** | `{gateReady, streamPending, oldestPendingIdleMs, outboxPending}` |

Other actuator endpoints (`env`, `beans`, `shutdown`, …) are **not** exposed.

## Teaching notes (historical evolution)

`docs/PHASE0-SKELETON.md` … `docs/PHASE5-GEO-SIGN.md` are **course evolution notes**. They may lag the code. Prefer this file + TRADE-FLOW / SECKILL-CONSUME / SHOP-CACHE for current behavior.

Course “Phase 4” in [`PHASE4-BLOG-FOLLOW.md`](PHASE4-BLOG-FOLLOW.md) is **blog / follow / feed**, not this engineering evidence phase.

## Out of scope (intentionally)

- Spring Boot upgrade, Kafka / RabbitMQ, Redis Cluster, sharding, Caffeine, Bloom filters
- Real payment gateway / wallet
- Million-key Redis warmup or 1000-user inserts in default `mvn test`
- Treating HTTP accept QPS as order throughput

See [`DESIGN-NOTES.md`](DESIGN-NOTES.md), [`FAULT-DRILLS.md`](FAULT-DRILLS.md), [`RECOVERY.md`](RECOVERY.md), [`BENCH.md`](BENCH.md).
