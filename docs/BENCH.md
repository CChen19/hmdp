# Bench method

Honest load notes. **Never treat HTTP accept QPS as order throughput.**

## Hardware / software (fill when you run)

| Item | Value |
|------|-------|
| Machine | _fill locally_ |
| JDK | 17 runtime (bytecode Java 8) |
| MySQL | 8.x (compose or local) |
| Redis | 7.x |
| App | `mvn -DskipTests package && ./scripts/start-backend.sh` → `:8081` |
| Voucher stock | _fill_ |
| Users / tokens | _fill_ |
| Duration / warmup | smoke: tens of requests; full: _fill_ |

## Meters (accept ≠ final)

| Meter | Meaning |
|-------|---------|
| `hmdp.seckill.request` | HTTP seckill attempts |
| `hmdp.seckill.accept` | HTTP `Result.ok` (includes idempotent duplicate returning order id) |
| `hmdp.seckill.reject` | HTTP fail (sold-out, gate, window, hard duplicate, …) |
| `hmdp.seckill.final_success` | MySQL order **insert** in consumer |
| `hmdp.seckill.accept_to_db` | Timer accept → insert (same JVM) |
| `hmdp.shop.cache.*` | hit / miss / origin / unavailable |
| Gauges | stream pending, oldest idle ms, outbox pending, gate ready |

Scrape: `./scripts/bench/scrape-meters.sh` or `/actuator/metrics/...`. Ops JSON: `GET /ops/snapshot` (ADMIN).

## Scripts

```bash
# Tiny seckill smoke (requires AUTH_TOKEN + VOUCHER_ID)
export AUTH_TOKEN=... VOUCHER_ID=...
./scripts/bench/seckill-smoke.sh 20

# Empty-stock fast reject
export EXPECT_REJECT=1
./scripts/bench/seckill-smoke.sh 50

# Optional MySQL final count
export MYSQL_CMD='mysql -uroot -p001020 hmdp -N -e'
./scripts/bench/seckill-smoke.sh 20

# Shop cold vs hot
export SHOP_ID=1
./scripts/bench/shop-cache-smoke.sh 10
```

Empty-stock rejects should show high `http_rejects` and **near-zero** new `final_success`. Successful accepts still lag final MySQL rows under consumer backpressure.

## Sync comparison

No second production seckill path. Compare **accept latency/QPS** vs **accept-to-DB** timer / MySQL counts on this async path only ([`DESIGN-NOTES.md`](DESIGN-NOTES.md)).

## Smoke numbers (agent environment)

Captured only if a local app was reachable; otherwise left blank. **Do not invent QPS.**

| Run | requests | http_accepts | http_rejects | final_mysql | notes |
|-----|----------|--------------|--------------|-------------|-------|
| smoke (tens) | N/A — no local app in agent env | N/A | N/A | N/A | Scripts shipped; run on your machine |

## RESULTS (large / sustained)

| Scenario | Duration | Accept QPS | Final success /s | p50/p95/p99 ms | Hardware |
|----------|----------|------------|------------------|----------------|----------|
| Stock available | N/A — run locally | N/A — run locally | N/A — run locally | N/A — run locally | |
| Empty stock reject | N/A — run locally | N/A — run locally | ~0 | N/A — run locally | |
| Shop hot cache | N/A — run locally | n/a | n/a | N/A — run locally | |

Paste real numbers after local runs. CI does **not** run these benches (`mvn -B test` only).
