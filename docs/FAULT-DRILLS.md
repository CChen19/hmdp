# Fault drills → tests

Map of reliability scenes to **existing default-suite tests**. Run: `mvn -B test` (no live course DB required for these).

| # | Scene | Expected | How to verify |
|---|--------|----------|---------------|
| 1 | Limited stock, many users | Final DB orders ≤ stock | `CreateVoucherOrderTest.stockUpdateFalse_doesNotSave` (DB deduct fails → no insert). Lua stock gate in `SeckillLuaScriptTest`. Live: compare `COUNT(*)` vs voucher stock after bench. |
| 2 | Same user concurrent same voucher | No double buy | Lua duplicate + `uk_user_voucher`; `CreateVoucherOrderTest` (`existingOrderId_*`, `existingUserVoucher_*`, `duplicateLua_returnsOriginalOrderIdFromRedisMap`) |
| 3 | DB commit then ACK fail | Deduct once; retry confirms | Idempotent `createVoucherOrder` + `PendingRecoverRetryTest` (RETRY left in PEL, no poison burn) |
| 4 | Consumer dies after read | Recover/claim finishes | Own PEL: `PendingRecoverRetryTest` + `SeckillOrderStreamConsumer.recoverOwnPending`. Foreign idle: `claimIdleFromOthers` (XCLAIM 30s) — exercised in production path; see [`SECKILL-CONSUME.md`](SECKILL-CONSUME.md) |
| 5 | Poison message | Later orders not blocked | `SeckillPoisonHandlerTest` (bad payload / max deliveries → DL + ACK path) |
| 6 | Pay vs timeout cancel | One winner; stock released ≤ once | `TradeOrderServiceTest` (pay wins / cancel wins / outbox once) |
| 7 | Concurrent redeem | One audit / one status flip | `TradeOrderServiceTest` redeem cases |
| 8 | Redis cold start / shop update | Existing shop not “不存在” | `CacheClientTest`, `ShopServiceCacheTest` (miss→DB, empty marker, Redis down → unavailable vs not-found) |

Demo/warmup tests (`@Tag("demo")`) are **excluded** from default CI and are not required for these drills.
