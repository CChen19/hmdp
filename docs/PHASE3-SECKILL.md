# Phase 3：优惠券秒杀（一人一单 / 锁 / Lua / Stream）

> 前置：Phase 0 环境通 · Phase 1 登录懂 Token（秒杀接口要登录）  
> 目标：搞清超卖与重复下单怎么挡、分布式锁演进、Lua 原子扣减、Stream 异步落库；本项目**当前实际走哪条路径**

## 1. 先读这些文件（顺序）

| 顺序 | 文件 | 看什么 |
|------|------|--------|
| 1 | `VoucherOrderController` | `POST /voucher-order/seckill/{id}` |
| 2 | `VoucherOrderServiceImpl.seckillVoucher` | **当前启用**：Lua + Stream；下面大段注释是演进过程 |
| 3 | `seckill.lua` | 判库存 / 一人一单 / 扣库存 / `XADD` |
| 4 | `VoucherOrderServiceImpl` 的 `@PostConstruct` + `handlePendingList` | Stream 消费者、ACK、pending 重试 |
| 5 | `VoucherOrderServiceImpl.createVoucherOrder` | 异步落库：DB 扣库存 + 写订单 |
| 6 | `VoucherServiceImpl.addSeckillVoucher` | 建秒杀券时**预热** `seckill:stock:{id}` |
| 7 | `RedisIdWorker` | 全局订单 id（时间戳 + 日序列） |
| 8 | `SimpleRedisLock` + `unlock.lua`、`RedissonConfig` | 手写锁 vs Redisson（注释里曾用过） |

前端对照：`frontend/shop-detail.html`（「限时抢购」→ `POST /voucher-order/seckill/{id}`，**需登录**）。

## 2. 当前线上走哪条路

```164:183:src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        Long orderId = redisIdWorker.nextId("order");
        Long res = stringRedisTemplate.execute(
                SECKILL_SCRIPT
                , Collections.emptyList()
                , voucherId.toString()
                , user.getId().toString()
                , orderId.toString());
        int r = res.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "禁止重复下单");
        }
        return Result.ok(orderId);
    }
```

| 策略 | 代码位置 | 当前状态 |
|------|----------|----------|
| 同步查库 + `synchronized` | 注释版 `seckillVoucher` | **未启用**（仅单体） |
| 同步 + `SimpleRedisLock` / Redisson | 注释版 | **未启用** |
| Lua + 本地 `BlockingQueue` | 注释版 | **未启用** |
| Lua + Redis Stream 异步落库 | 当前 `seckillVoucher` + `@PostConstruct` | **线上启用** |
| 异步落库时 Redisson 锁 | `handleVoucherOrder` | 启用（兜底） |

重要后果：

1. **库存与一人一单先在 Redis 判完**，接口立刻返回 `orderId`；真正写 MySQL 订单是 Stream 消费者异步做的——短暂「有 orderId、库还没有 / Redis 已扣、MySQL 未扣」是设计代价，正常最终由消费者对齐。  
2. **必须先有** `seckill:stock:{id}`（建券时写入），且 Stream 消费组已创建，否则启动时报 `NOGROUP` 或秒杀直接失败。  
3. 注释里的同步路径仍保留 `getResult`，便于对照「一人一单 + 乐观扣库存」怎么写。

## 3. 要解决的两个问题一句话

| 问题 | 现象 | 本项目解法（当前） |
|------|------|-------------------|
| **超卖** | 库存 100，并发下出 >100 单 | Lua 里 `GET` 库存再 `INCRBY -1`，整段原子；落库再用 `stock > 0` 乐观更新 |
| **一人一单** | 同一用户对同一券下多单 | Lua 里 `SISMEMBER` / `SADD` 到 `seckill:order:{voucherId}`；异步侧再加 Redisson `lock:order:{userId}` |

```
不解：  查库存 → 扣库存 → 写订单   （并发间隙可超卖；无一人一单）
同步锁：分布式锁包住「查是否下过 + 扣库存 + 下单」（能正确，吞吐受 DB 限制）
当前：  Lua 在 Redis 原子判定 → 立刻返回 → Stream 慢慢写 DB
```

## 4. 端到端：Lua + Stream（当前路径）

```
建秒杀券（一次性）：
  POST /voucher/seckill
  → 写 tb_voucher + tb_seckill_voucher
  → SET seckill:stock:{voucherId} = stock

启动前（一次性）：
  XGROUP CREATE stream.orders g1 $ MKSTREAM

浏览器「限时抢购」（已登录）：
  POST /api/voucher-order/seckill/{voucherId}
  → VoucherOrderController
  → RedisIdWorker.nextId("order")          // 先生成订单 id
  → EVAL seckill.lua ARGV=[voucherId, userId, orderId]
       1) GET seckill:stock:{id} ≤ 0  → return 1（库存不足）
       2) SISMEMBER seckill:order:{id} userId → return 2（重复下单）
       3) INCRBY stock -1
          SADD order set userId
          XADD stream.orders * userId / voucherId / id
          → return 0
  → 立刻 Result.ok(orderId)                 // 此时 DB 可能还没有订单

后台单线程（@PostConstruct 启动时拉起死循环）：
  // Redis Stream = Redis 自带消息队列；本项目用 stream.orders
  XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2s STREAMS stream.orders >
  → 解析成 VoucherOrder
  → handleVoucherOrder：
       Redisson tryLock(lock:order:{userId})
       → createVoucherOrder：DB stock>0 扣减 + INSERT tb_voucher_order
            // MyBatis-Plus：ISeckillVoucherService → SeckillVoucherMapper(BaseMapper)
            // update(wrapper) 自动生成 SQL，对应 XML 可为空
  → XACK stream.orders g1 {msgId}
  异常 → handlePendingList：读已读未 ACK 的 pending（offset 0）重试再 ACK
       // 避免崩溃丢单；但本实现是单线程：某条一直失败会卡在 pending，
       // 后面新消息暂时落不了库（接口层 Lua 仍可能扣 Redis 并返回 orderId）
```

对比注释里的同步 Redisson 版：

```
查券时间窗 → 查库存
→ tryLock(lock:order:{userId})
   成功 → getResult：DB 查是否下过 → 乐观扣库存 → 写订单 → 返回
   失败 → 「一人一单哦！」
```

| | 同步锁落库 | Lua + Stream |
|--|------------|--------------|
| 接口何时返回 | DB 事务完成后 | **Lua 成功即返回** |
| 抗并发瓶颈 | 锁 + DB | 瓶颈主要在 Redis |
| 一人一单主防线 | DB `count` + 锁 | Redis Set（Lua） |
| 一致性 | 较强（同步写完） | 允许短暂「有 orderId、库还没有」 |
| 运维前提 | 无 Stream | **必须建消费组**；库存须预热 |

## 5. Lua / 锁 / 订单号：怎么读

### 5.1 `seckill.lua` 返回值

| 返回 | 含义 | Java 提示 |
|------|------|-----------|
| `0` | 有购买资格，已扣 Redis 库存并入队 | 返回 `orderId` |
| `1` | 库存不足 | 「库存不足」 |
| `2` | Set 里已有该用户 | 「禁止重复下单」 |

脚本内 `KEYS` 为空，业务 key 全用 `ARGV` 拼接——和课程写法一致。外挂 Lua 的目的，就是把「读库存 → 判一人一单 → 扣库存 → 入队」收成**一次原子执行**，避免多条 Redis 命令被插队导致超卖/重复下单。拆成多次命令仍有竞态；Lua 成功也不等于 MySQL 订单已写完（见 §2 / §4）。

### 5.2 `SimpleRedisLock` vs Redisson

| | `SimpleRedisLock` | Redisson `RLock` |
|--|-------------------|------------------|
| 加锁 | `SET lock:{name} {uuid-threadId} NX EX` | `tryLock()`（可重入、看门狗续期等） |
| 解锁 | `unlock.lua`：值相等才 `DEL`（防误删别人的锁） | `unlock()` |
| 本项目 | 注释演进用；缓存重建也曾用同类思路 | **当前**异步落库兜底 |

手写锁要自己处理：锁超时、误删、可重入、续期。课程用 Redisson 收尾是合理取舍。

### 5.3 `RedisIdWorker`

```
id = (nowSeconds - 2022-01-01) << 32 | INCR icr:order:yyyyMMdd
```

- 高位时间：大致有序、可按时间趋势  
- 低 32 位：当天自增，多实例共享 Redis 不撞号  
- 订单主键 `IdType.INPUT`，用这个 id，不靠 MySQL 自增

## 6. Redis Key 速查

| Key | 类型 | TTL / 说明 | 内容 |
|-----|------|------------|------|
| `seckill:stock:{voucherId}` | String | 本实现未设 TTL | 剩余库存数字 |
| `seckill:order:{voucherId}` | Set | 本实现未设 TTL | 已下单 userId（一人一单） |
| `stream.orders` | Stream | 消息消费后 ACK | 字段：`userId` / `voucherId` / `id` |
| `lock:order:{userId}` | String（Redisson） | Redisson 管理 | 异步落库兜底锁 |
| `icr:order:yyyyMMdd` | String | 建议长期保留 | 当日订单序列 |
| `lock:{name}`（手写） | String | `tryLock` 传入秒数 | `SimpleRedisLock` 的 value = uuid-threadId |

建券入口：`POST /voucher/seckill`（白名单，可不登录）会同时写 MySQL 与 `seckill:stock:*`。  
秒杀下单：`/voucher-order/**` **不在白名单**，必须带 Token。

## 7. 动手验收

### 7.1 创建 Stream 消费组（首次必做）

后端若刷屏：

```text
NOGROUP No such key 'stream.orders' or consumer group 'g1'
```

先执行：

```bash
redis-cli -a '<redis-password>' --no-auth-warning XGROUP CREATE stream.orders g1 '$' MKSTREAM
```

再启动后端（或重启）。

### 7.2 登录拿 Token

与 Phase 1 相同：发码 → 登录 → 记下 `TOKEN`。

### 7.3 创建一张秒杀券（把时间改到「此刻前后」）

```bash
curl -s http://127.0.0.1:8081/voucher/seckill \
  -H 'Content-Type: application/json' \
  -d '{
    "shopId": 1,
    "title": "100元代金券",
    "subTitle": "周一至周日均可使用",
    "rules": "全场通用",
    "payValue": 8000,
    "actualValue": 10000,
    "type": 1,
    "stock": 100,
    "beginTime": "2026-01-01T00:00:00",
    "endTime": "2027-12-31T23:59:59"
  }'
# 响应 data 即 voucherId，记下为 VID
```

```bash
redis-cli -a '<redis-password>' --no-auth-warning GET seckill:stock:VID
# 应为 100
```

### 7.4 秒杀一次

```bash
curl -s http://127.0.0.1:8081/voucher-order/seckill/VID \
  -H 'authorization: TOKEN'
# → success:true，data 为 orderId
```

```bash
redis-cli -a '<redis-password>' --no-auth-warning GET seckill:stock:VID          # 99
redis-cli -a '<redis-password>' --no-auth-warning SMEMBERS seckill:order:VID     # 有你的 userId
# 稍等异步消费者后查库：
# SELECT * FROM tb_voucher_order WHERE id = 上一步 orderId;
```

### 7.5 体会一人一单 / 库存不足

```bash
# 同一用户再抢一次
curl -s http://127.0.0.1:8081/voucher-order/seckill/VID \
  -H 'authorization: TOKEN'
# → 禁止重复下单

# 把库存打成 0 后再抢（换新用户或先改 Redis）
redis-cli -a '<redis-password>' --no-auth-warning SET seckill:stock:VID 0
# 新用户抢 → 库存不足
```

浏览器：http://127.0.0.1:8080/ → 登录 → 进商户详情 →「限时抢购」（券需在有效期内且 `type` 为秒杀）。

## 8. 验收清单

- [ ] 能说明超卖、一人一单分别在哪一层挡住（Lua vs DB）
- [ ] 说出当前路径是 **Lua + Stream**，接口返回时订单可能尚未落库
- [ ] 看懂 `seckill.lua` 三个返回值，以及为何必须脚本原子执行
- [ ] 能对比同步锁版 vs Stream 版：延迟、吞吐、运维前提
- [ ] 知道 `SimpleRedisLock` 为何用 `unlock.lua`（比对标识再删）
- [ ] 知道 `RedisIdWorker` 如何拼出全局唯一订单 id
- [ ] 演示：建消费组 → 建秒杀券 → 登录秒杀成功 → Redis 库存/Set 变化 → DB 出现订单；同用户再抢失败
- [ ] （加分）读注释掉的 `BlockingQueue` 版，说明为何改成 Redis Stream（多实例、重启不丢队列）

## 9. 下一步：Phase 4（探店与关注）

精读：`BlogServiceImpl`（点赞 ZSet）、`FollowServiceImpl`、Feed 推/拉  
主题：点赞排行、关注、粉丝时间线（Feed）。
