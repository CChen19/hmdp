# HMDP — reliable coupon trade path

English | [中文](README.zh.md)

Heima DianPing (黑马点评) started as a [Redis course project](https://www.bilibili.com/video/BV1cr4y1671t) ([upstream reference](https://github.com/cs001020/hmdp)). This fork turns the coupon path into a **reliable seckill → pay → timeout cancel → redeem** flow with a **bounded shop cache**, automated tests, and honest ops docs — without pretending to be a full marketplace.

**Accept ≠ final success.** HTTP `Result.ok(orderId)` means Redis accepted the attempt. Final success is a MySQL `tb_voucher_order` insert in the Stream consumer (`hmdp.seckill.final_success`). Do not treat accept QPS as order throughput.

## What works now

| Path | Behavior |
|------|----------|
| Seckill | Lua stock + one-order-per-user → Redis Stream → consumer → MySQL order |
| Trade | Simulated pay; unpaid timeout cancel + stock-release outbox; merchant redeem |
| Shop browse | Cache miss / empty marker → MySQL; Redis/DB fail → unavailable (not false “不存在”) |
| Auth | SMS code login, Redis session, roles USER / MERCHANT / ADMIN |

## Quick start

**Runtime:** JDK 17 (Maven compiler **source/target 8**), Maven, Docker (or local MySQL 8 + Redis 7).

```bash
docker compose up -d
# optional seed dump (or let Flyway V1–V5 build schema on empty DB)
mysql -h127.0.0.1 -uroot -p"${MYSQL_PASSWORD:-001020}" < hmdp.sql

mvn -B test
mvn -DskipTests package && ./scripts/start-backend.sh   # API :8081
./scripts/start-frontend.sh                             # optional UI :8080
```

| Service | URL |
|---------|-----|
| API | `http://127.0.0.1:8081` |
| Frontend | `http://127.0.0.1:8080/` (`/api` → 8081) |
| Health | `GET /actuator/health` |
| Metrics | `GET /actuator/metrics`, `/actuator/prometheus` |
| Ops snapshot | `GET /ops/snapshot` (login + **ADMIN**) |

Login captcha appears in backend logs (`发送验证码成功，验证码：…`). Consumer group is created at startup with `XGROUP CREATE stream.orders g1 0-0 MKSTREAM`. **Do not** create the group with `$` (skips backlog). If an old `$` group exists: `XGROUP SETID stream.orders g1 0-0`.

Defaults live in `application.yaml`; override with `MYSQL_*` / `REDIS_*` env vars. Do not commit new secrets.

### Flyway

V1 baseline → V2 `uk_user_voucher` → V3 `tb_user.role` → V4 seckill dead-letter → V5 trade outbox/audit. `hmdp.sql` is a full readable dump with seed data. `spring.flyway.baseline-on-migrate=true` for existing local DBs.

Default `mvn test` is safe (no million-key Redis warmup / mass user inserts). Demo tags: `./scripts/demo-warmup.sh` or `mvn -Dgroups=demo -Dsurefire.excludedGroups= test`. CI: `.github/workflows/ci.yml` runs `mvn -B test` only — **no load benches**.

## Health ≠ Kubernetes liveness

`GET /actuator/health` aggregates MySQL + Redis + the **seckill consume gate**. If only the gate is closed, aggregate health is **DOWN** while shop browse still works. Using that URL as a kube **liveness** probe would restart a healthy process.

- Local/dev: convenience dashboard, public, `show-details: always`.
- **Do not** copy it as Kubernetes liveness.
- If you probe: liveness = process alive (do not require the gate); readiness for **seckill traffic** may include the gate.
- **Do not** publish port 8081.

## Docs

| Doc | Role |
|-----|------|
| [`docs/CURRENT.md`](docs/CURRENT.md) | **Start here** — production index, design rationale, out of scope |
| [`docs/SECKILL.md`](docs/SECKILL.md) | Seckill contract: Lua/window/unique + Stream consume/PEL/DL/query |
| [`docs/TRADE-FLOW.md`](docs/TRADE-FLOW.md) | Pay / cancel / redeem / outbox |
| [`docs/SHOP-CACHE.md`](docs/SHOP-CACHE.md) | Shop cache miss → DB |
| [`docs/AUTH-BOUNDARIES.md`](docs/AUTH-BOUNDARIES.md) | Public vs login vs privilege |
| [`docs/RECOVERY.md`](docs/RECOVERY.md) | Ops recovery + 8-scene fault drills |
| [`docs/BENCH.md`](docs/BENCH.md) | Bench method; RESULTS stay N/A until you run locally |

Layout: `src/` backend · `frontend/` static UI · `scripts/` (incl. `scripts/bench/`) · `docker-compose.yml`.

## Teaching notes (course evolution)

These may lag the code. Prefer CURRENT / SECKILL / TRADE-FLOW for production behavior.

| Phase | Doc | Topic |
|-------|-----|--------|
| 0 | [`docs/PHASE0-SKELETON.md`](docs/PHASE0-SKELETON.md) | Environment & skeleton |
| 1 | [`docs/PHASE1-LOGIN.md`](docs/PHASE1-LOGIN.md) | SMS login & Redis session |
| 2 | [`docs/PHASE2-CACHE.md`](docs/PHASE2-CACHE.md) | Shop cache (穿透 / 击穿 / 雪崩) |
| 3 | [`docs/PHASE3-SECKILL.md`](docs/PHASE3-SECKILL.md) | Coupon seckill (lock / Lua / Stream) |
| 4 | [`docs/PHASE4-BLOG-FOLLOW.md`](docs/PHASE4-BLOG-FOLLOW.md) | Blog / follow / feed (course Phase 4) |
| 5 | [`docs/PHASE5-GEO-SIGN.md`](docs/PHASE5-GEO-SIGN.md) | GEO & sign-in bitmap |

## Bench

Scripts under `scripts/bench/` exist for local smoke. **Do not invent laptop QPS.** [`docs/BENCH.md`](docs/BENCH.md) RESULTS remain **N/A** until you run a real smoke on your machine.

## Out of scope

Spring Boot upgrade · Kafka / RabbitMQ · Redis Cluster / sharding · Caffeine / Bloom · real payment gateway / wallet · treating HTTP accept as final order throughput.
