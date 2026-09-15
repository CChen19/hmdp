# Seckill rules (Phase 1 correctness)

Short operational contract for stock, activity window, Redis publish, and one-user-one-voucher.

## Redis keys

| Key | Value | Written by |
|-----|--------|------------|
| `seckill:stock:{voucherId}` | remaining stock (string int) | `VoucherServiceImpl.addSeckillVoucher` after DB commit |
| `seckill:begin:{voucherId}` | activity begin (epoch seconds) | same |
| `seckill:end:{voucherId}` | activity end (epoch seconds) | same |
| `seckill:order:{voucherId}` | Redis set of userIds who passed Lua | `seckill.lua` |

Lua (`seckill.lua`) rejects when begin/end are missing, or when server-provided `now` is outside `[begin, end]`. Java also rejects outside the DB window before calling Lua. Missing time metadata = reject (do not open purchase).

## Publish after commit

Redis stock/window is written only in `TransactionSynchronization.afterCommit`. Never before the DB transaction commits.

If Redis write fails after commit: the seckill voucher row exists in MySQL but Redis may be empty. Logs include the exact `SET` rebuild commands. Retry/manual rebuild:

```bash
# Replace id / values from the error log or from tb_seckill_voucher
redis-cli SET seckill:stock:{id} {stock}
redis-cli SET seckill:begin:{id} {beginEpochSeconds}
redis-cli SET seckill:end:{id} {endEpochSeconds}
```

Do not treat “DB published, Redis empty” as success for buyers — purchase will fail until keys exist.

## DB insert gate

`createVoucherOrder`:

1. If order id already exists → return (idempotent), no second stock decrement.
2. Decrement with `stock > 0`; **insert only when that update succeeds**.
3. Stock update failure → throw (consumer must not ACK as success).

## Unique (user_id, voucher_id)

`tb_voucher_order` has `UNIQUE (user_id, voucher_id)`. Phase 1: one user, one voucher forever, even after a later cancel.

“Rebuy after cancel” is a Phase 3 product decision and would need a different unique key (e.g. unique only among non-cancelled rows).

## Lua return codes

| Code | Meaning |
|------|---------|
| 0 | ok (stock deducted, user in set, Stream XADD) |
| 1 | insufficient stock |
| 2 | duplicate user for voucher |
| 3 | outside activity window or missing begin/end |

## Lua smoke check (optional, isolated Redis)

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
