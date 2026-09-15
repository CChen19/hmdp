> **课程学习路径**；秒杀现行契约见 [`SECKILL.md`](SECKILL.md)；支付/取消/核销见 [`TRADE-FLOW.md`](TRADE-FLOW.md)；索引见 [`CURRENT.md`](CURRENT.md)。

# Phase 3：优惠券秒杀（一人一单 / 锁 / Lua / Stream）

## 课程当时讲什么

- 超卖与一人一单：从同步锁演进到 Lua 原子判定
- Lua：扣 Redis 库存、记用户、立刻返回 `orderId`
- 异步落库：消息队列 → 写 MySQL 订单
- 对照手写锁 / Redisson、`RedisIdWorker`
- 课上要点：**返回 orderId ≠ MySQL 已有订单**（Accept ≠ final success）

## 本仓库现在

| 主题 | 现行约定 | 文档 |
|------|----------|------|
| HTTP 路径 | Lua → Redis Stream `stream.orders` → 消费者 → MySQL | [`SECKILL.md`](SECKILL.md) |
| 消费者 | **`SmartLifecycle`**（PEL 恢复、XCLAIM、死信） | 同上 |
| 消费组 | 启动幂等创建，offset **`0-0`**（跳过历史 backlog 的 `$` 不可用） | 同上 |
| 活动窗口 | Lua + Java 双检；缺 begin/end 则拒绝 | 同上 |
| 唯一购买 | Redis Set + MySQL **`UNIQUE (user_id, voucher_id)`** | 同上 |
| 重复请求 | 幂等返回**原 orderId** | 同上 |
| Accept | HTTP ok **≠** `tb_voucher_order` 行 | [`CURRENT.md`](CURRENT.md) |
| 下单之后 | 模拟支付、超时取消+出箱、核销 | [`TRADE-FLOW.md`](TRADE-FLOW.md) |
| 故障演练 | 8 场景 → 默认测试 | [`RECOVERY.md`](RECOVERY.md) |

## 还想对照代码就打开

- `VoucherOrderServiceImpl.seckillVoucher`、`seckill.lua`
- `SeckillOrderStreamConsumer`、`SeckillStreamMessageHandler`
- `createVoucherOrder`、Flyway V2/V4
- 课程序列下一步：[`PHASE4-BLOG-FOLLOW.md`](PHASE4-BLOG-FOLLOW.md)（探店关注；**不是**工程证据 Phase 4）
