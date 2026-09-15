> **课程演进笔记** — 记录 B 站课当时教什么。秒杀现行契约见 [`SECKILL.md`](SECKILL.md)；支付/取消/核销见 [`TRADE-FLOW.md`](TRADE-FLOW.md)。**勿**再把 `@PostConstruct` / `$` 消费组当成线上路径。

# Phase 3：优惠券秒杀（一人一单 / 锁 / Lua / Stream）

> 课上目标：超卖与一人一单；锁 → Lua 原子扣减 → Stream 异步落库的演进。

## 课程路径（当时在学什么）

1. 同步：查库 + `synchronized` / 分布式锁 + 乐观扣库存  
2. Lua：库存 + 一人一单原子判定，立刻返回 `orderId`  
3. 异步：本地队列 → 再换成 Redis Stream + 后台消费者写 MySQL  
4. 对照：`SimpleRedisLock` / `unlock.lua` vs Redisson；`RedisIdWorker`  

课上常强调：**接口返回 orderId ≠ 订单已在 MySQL**（Accept ≠ final success）。

## 课程当时 vs 本仓库现在

| 点 | 课程当时（常见笔记） | 本仓库现在 |
|----|----------------------|------------|
| 消费者 | `VoucherOrderServiceImpl` 里 **`@PostConstruct` 死循环** | **`SmartLifecycle`** 消费者；优雅停机 |
| 消费组 | 笔记里常见 `XGROUP … **$**` | 启动幂等 **`0-0`**；历史 `$` 用 `SETID` 修好 |
| 活动窗 | 多在 Java 查券时间 | **Lua + Java** 双检；缺 begin/end **拒绝** |
| 一人一单 | Redis Set；DB 侧较弱 | Set + MySQL **`UNIQUE (user,voucher)`** |
| 重复下单 | 常只返回「禁止重复」 | 幂等返回**原 orderId**（map / DB） |
| 可靠消费 | pending 死循环；无毒消息出口 | PEL 恢复、XCLAIM、**死信**、再 ACK |
| Accept | 返回 orderId 即「成功感」 | **Accept ≠ MySQL 行**；见 meters / 查询 API |
| 交易 | 课上多停在「下单」 | 模拟支付 / 超时取消+出箱 / 核销 → [`TRADE-FLOW.md`](TRADE-FLOW.md) |
| 详情 | — | [`SECKILL.md`](SECKILL.md) |

## 建议打开

- [`SECKILL.md`](SECKILL.md) — Lua / 窗口 / 唯一键 + Stream / PEL / DL / 查询  
- [`TRADE-FLOW.md`](TRADE-FLOW.md) — 支付后链路  
- [`RECOVERY.md`](RECOVERY.md) — 故障与演练  
- [`CURRENT.md`](CURRENT.md)  
- 下一步（课程序列）：[`PHASE4-BLOG-FOLLOW.md`](PHASE4-BLOG-FOLLOW.md)（探店关注，**不是**工程「证据 Phase 4」）
