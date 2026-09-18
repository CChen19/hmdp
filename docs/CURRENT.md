# Current production behavior

This file is the **index of what the running app does now**, versus course teaching notes and explicit out-of-scope ideas. It also records why this stack stays small.

## Production (this repo on `master` / evidence branches)

| Area | Behavior | Detail |
|------|----------|--------|
| Auth | SMS code login, Redis session, roles USER/MERCHANT/ADMIN | [`AUTH-BOUNDARIES.md`](AUTH-BOUNDARIES.md) |
| Shop browse | Logical-expire Redis cache; miss → MySQL; Redis/DB fail → unavailable (not “不存在”); geo list empty page → `[]` (never SQL error) | [`SHOP-CACHE.md`](SHOP-CACHE.md) |
| Seckill | Lua stock + one-order-per-user → Stream `stream.orders` → SmartLifecycle consumer (PEL, XCLAIM, dead-letter) → MySQL | [`SECKILL.md`](SECKILL.md) |
| Trade | Simulated pay, unpaid timeout cancel + stock-release outbox, merchant redeem | [`TRADE-FLOW.md`](TRADE-FLOW.md) |
| Ops | Actuator health/metrics; ADMIN `/ops/snapshot`; bench scripts; recovery + fault drills | [`BENCH.md`](BENCH.md), [`RECOVERY.md`](RECOVERY.md) |

**Accept ≠ final success.** HTTP `Result.ok(orderId)` means Redis accepted the attempt. **Final success** is a MySQL `tb_voucher_order` insert in the consumer (`hmdp.seckill.final_success`).

### Actuator & ops paths

| Path | Auth | Notes |
|------|------|-------|
| `GET /actuator/health` | Public | Aggregates `db`, `redis`, `seckillConsumeGate` (DOWN when gate closed). Local/dev convenience dashboard (`show-details: always`). **Do not use as Kubernetes liveness** — see below. |
| `GET /actuator/info` | Public | Build/app info |
| `GET /actuator/metrics/**` | Public (local) | Micrometer; **do not** expose 8081 on the internet |
| `GET /actuator/prometheus` | Public (local) | Optional scrape format; no Prometheus server shipped |
| `GET /ops/snapshot` | Login + **ADMIN** | `{gateReady, streamPending, oldestPendingIdleMs, outboxPending}` |

Other actuator endpoints (`env`, `beans`, `shutdown`, …) are **not** exposed.

### Health ≠ Kubernetes liveness

`GET /actuator/health` includes the **seckill consume gate**. If only the gate is closed, **aggregate health is DOWN** even though shop browse and other non-seckill paths still work. A kube **liveness** probe on that URL would restart a healthy process.

- Local/dev: treat `/actuator/health` as a convenience dashboard, public by design.
- **Do not** copy it as Kubernetes liveness.
- If you ever probe: liveness = process alive (do **not** require the seckill gate); readiness for **seckill traffic** may include the gate.
- Do **not** publish port 8081.

## Why this stack (design notes)

### Why Redis Stream (not Kafka)

- Course and ops stay on **one Redis** already required for stock/session/cache.
- Accept path already `XADD`s in Lua; a second broker would duplicate durability concerns without buying multi-DC fan-out we do not need.
- Consumer group + PEL + `XCLAIM` is enough for crash recovery at tutorial scale.
- Kafka remains valid for multi-service fan-out; **out of scope** here.

### Why no sharding / cluster / multi-level cache

- Single voucher hot key + Lua is the intentional bottleneck to study.
- Redis Cluster / Caffeine / Bloom add failure modes without changing the teaching story.
- Shop cache is **one Redis logical-expire layer** + MySQL origin ([`SHOP-CACHE.md`](SHOP-CACHE.md)).

### Transaction boundaries

| Step | Where | Atomicity |
|------|--------|-----------|
| Stock −−, order set, stream enqueue | Redis Lua (`seckill.lua`) | Single Lua script |
| DB stock −− + `tb_voucher_order` insert | MySQL `@Transactional` in `createVoucherOrder` | Single DB TX; idempotent on order id / `(user,voucher)` |
| ACK Stream message | Redis after SUCCESS/POISON | **After** DB commit; ACK loss → PEL retry (safe due to idempotent insert) |
| Cancel: order status + DB stock ++ + outbox row | MySQL same TX | Redis INCR deferred to outbox worker + Lua SETNX |
| Outbox Redis stock release | `stock_release.lua` | Idempotent done-key |

**Do not** treat Lua accept as paid inventory in MySQL until the consumer commits.

### Sync vs async seckill

Production HTTP path is **async only** (Lua → Stream → consumer). There is no second production sync seckill API. To compare latency:

- **Accept QPS / latency** — client → HTTP accept meter
- **E2E accept→DB** — `hmdp.seckill.accept_to_db` timer (same JVM) or `create_time - accept` via logs/ids

See [`BENCH.md`](BENCH.md).

## Teaching notes (historical evolution)

`docs/PHASE0-SKELETON.md` … `docs/PHASE5-GEO-SIGN.md` are **slim course-history notes** (what the lesson taught + a short 「课程当时 vs 本仓库现在」 table). They are **not** a second source of truth. Prefer this file + [`TRADE-FLOW.md`](TRADE-FLOW.md) / [`SECKILL.md`](SECKILL.md) / [`SHOP-CACHE.md`](SHOP-CACHE.md) / [`AUTH-BOUNDARIES.md`](AUTH-BOUNDARIES.md).

Course “Phase 4” in [`PHASE4-BLOG-FOLLOW.md`](PHASE4-BLOG-FOLLOW.md) is **blog / follow / feed**, not the engineering evidence / bench phase.

## Out of scope (intentionally)

- Spring Boot upgrade, Kafka / RabbitMQ, Redis Cluster, sharding, Caffeine, Bloom filters
- Real payment gateway / wallet
- Million-key Redis warmup or 1000-user inserts in default `mvn test`
- Treating HTTP accept QPS as order throughput

See [`RECOVERY.md`](RECOVERY.md), [`BENCH.md`](BENCH.md).
