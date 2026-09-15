# Phase 3 trade flow (v1)

Simulated pay → optional timeout cancel → merchant redeem. No real payment gateway, no wallet, no refund/settlement, no Kafka.

## State machine

```
待支付(1) → 已支付(2) → 已核销(3)
     └──→ 已取消(4)
```

| Status | Meaning | Transitions in |
|--------|---------|----------------|
| 1 UNPAID | DB row exists after seckill consume | create (default) |
| 2 PAID | Simulated pay succeeded | pay (from 1 only) |
| 3 USED | Merchant redeemed | redeem (from 2 only) |
| 4 CANCELLED | Timeout or owner cancel | cancel (from 1 only) |

Phase 2 accept/`PROCESSING` is Redis-only until the MySQL row exists; trade statuses apply only to DB rows.

## Endpoints

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/voucher-order/{id}/pay` | login, **owner** | `SimulatedPayClient` — not WeChat/Alipay |
| POST | `/voucher-order/{id}/cancel` | login, owner | Unpaid only; releases stock via outbox |
| POST | `/voucher-order/{id}/redeem?shopId=` | login, **MERCHANT/ADMIN** | Voucher must belong to `shopId` |

## Pay timeout

- Constant: `TradeConstants.PAY_TIMEOUT_MINUTES = 15`
- Measured from `create_time` (order insert time)
- Job: `UnpaidOrderTimeoutJob` every 30s, batch scan `status=1 AND create_time < now-15m`
- Index: `idx_voucher_order_unpaid_ctime (status, create_time)` (Flyway V5)

## Concurrency: pay vs cancel

Both use conditional updates:

```sql
UPDATE tb_voucher_order SET status=2, pay_time=… WHERE id=? AND status=1
UPDATE tb_voucher_order SET status=4 WHERE id=? AND status=1
```

Only one wins. Never read-then-write for the status flip. Repeat pay / cancel / redeem are idempotent (already-done → success).

## Cancel stock release (outbox)

Same MySQL transaction:

1. Conditional cancel `1→4`
2. `UPDATE tb_seckill_voucher SET stock=stock+1 WHERE voucher_id=?`
3. `INSERT stock_release_outbox (event_key=cancel:{orderId}, PENDING)`

Background `StockReleaseOutboxWorker`:

1. Atomic Lua on `outbox:stock:done:{eventKey}` + `seckill:stock:{voucherId}`: if `SETNX` wins then `INCR` in the same script (return 1); else return 0
2. Mark outbox `DONE` only after Lua succeeds

A crash cannot leave the done key set without having incremented. Replaying the same event does not double-INCR. Redis is **not** incremented inside the HTTP/cancel request TX.

## No rebuy after cancel (v1)

`uk_user_voucher (user_id, voucher_id)` stays. Cancel does **not** `SREM` the Redis seckill order set. A cancelled user cannot buy the same voucher again in v1.

## Redeem

- Only `status=已支付(2)`
- `voucher.shopId` must equal request `shopId`
- Writes `use_time`, status=`已核销(3)`, and one `voucher_order_redeem_audit` row (`uk_order_id`)
- **No expiry beyond seckill activity** in v1 (no separate use-window column)

## Out of scope (v1)

- Real WeChat/Alipay, wallet balance, refunds, settlement
- Delay queue / Kafka
- Rebuy after cancel
