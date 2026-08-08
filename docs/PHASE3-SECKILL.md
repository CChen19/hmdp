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

## 4. 端到端串一遍（当前路径）

```
① 浏览器（已登录）
   POST /voucher-order/seckill/{voucherId}
        │
② VoucherOrderController
        │
③ VoucherOrderServiceImpl.seckillVoucher
   · UserHolder 取 userId
   · RedisIdWorker 生成 orderId
   · 执行 seckill.lua
        │
④ Redis（Lua，原子）
   · 库存够？一人一单？
   · 够 → 扣 seckill:stock → SADD seckill:order → XADD stream.orders
   · 立刻返回 orderId 给前端     ← 这时 MySQL 可能还没有订单
        │
⑤ @PostConstruct 起的后台线程（Bean 装好后一直跑）
   XREADGROUP stream.orders（消费组 g1 / 消费者 c1）
        │
⑥ handleVoucherOrder
   Redisson 锁 lock:order:{userId}
        │
⑦ createVoucherOrder（@Transactional）
   seckillVoucherService.update(...)  → 扣 DB 库存
   this.save(voucherOrder)            → 插订单
        │
⑧ MyBatis-Plus → Mapper → MySQL
   SeckillVoucherMapper / VoucherOrderMapper
        │
⑨ 落库成功 → XACK
   异常 → handlePendingList（读 pending 重试）
```

再压成三句：

1. **接口 + Lua**：在 Redis 里判定并占坑，马上给 `orderId`  
2. **Stream + `@PostConstruct`**：把「待写库」的消息异步取出来  
3. **Service → Mapper → MySQL**：真正扣库存、写订单  

建券 / 消费组（一次性前置）：

```
POST /voucher/seckill
  → 写 tb_voucher + tb_seckill_voucher
  → SET seckill:stock:{voucherId} = stock

XGROUP CREATE stream.orders g1 $ MKSTREAM
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

## 5. Lua / Stream / 落库：怎么读

### 5.1 为什么要外挂 Lua（不是「Lua 就是下单」）

外挂 Lua 的目的：把「读库存 → 判一人一单 → 扣库存 → 入队」收成**一次原子执行**。若用 Java 连发多条 Redis 命令，中间会被别的客户端插队，仍可能超卖 / 重复下单。

但 Lua **不等于完整下单**：

| 步骤 | 谁做 | 做什么 |
|------|------|--------|
| 秒杀瞬间 | **Lua** | 判资格、扣 Redis 库存、`SADD`、`XADD` → 立刻返回 `orderId` |
| 稍后 | **Stream 消费者** | `createVoucherOrder`：扣 DB 库存 + `INSERT tb_voucher_order` |

记成：**Lua = Redis 侧占坑；DB = 最终持久化。**  
`unlock.lua` 同理：比对锁标识再 `DEL`，避免拆成两步时误删别人的锁。

| 返回 | 含义 | Java 提示 |
|------|------|-----------|
| `0` | 有购买资格，已扣 Redis 库存并入队 | 返回 `orderId` |
| `1` | 库存不足 | 「库存不足」 |
| `2` | Set 里已有该用户 | 「禁止重复下单」 |

### 5.2 Stream 是什么；`@PostConstruct` / pending 干什么

**Stream** = Redis 自带的消息队列（本项目 key：`stream.orders`）。相对用 List 当队列，多了消息 ID、消费组、ACK、pending 重试。

| 能力 | 含义 |
|------|------|
| `XADD` | Lua 秒杀成功后往队列塞「待落库订单」 |
| 消费组 `g1` | 组内协作消费；本课单消费者 `c1` |
| `XACK` | 处理完才确认；没确认进 pending |
| `>` vs `0` | `>` 读新消息；`0` 读已投递未 ACK 的 pending |

**`@PostConstruct init()`**：Spring 装好 `VoucherOrderServiceImpl` 后自动跑一次，提交单线程死循环——应用一启动就有人在后台拆 Stream 消息写库（不是用户点秒杀才启动消费者）。

**`handlePendingList`**：读出来 ≠ 处理成功。落库抛错 / 进程崩溃导致没 ACK 时，消息留在 pending；若只读 `>` 会永远拿不到它。所以 `catch` 里用 offset `0` 重试再 ACK，避免「Redis 已扣、DB 没单」。

一直失败会怎样（课程版局限）：

- `handlePendingList` 死循环刷同一条 pending，成功 ACK 前不 `break`
- 消费者又是**单线程** → **后面新消息全部卡住落不了库**
- 接口层 Lua 仍可能继续成功、返回 `orderId`（Redis 库存继续减）
- 没有最大重试 / 死信 / 跳过毒消息——生产需另补

### 5.3 异步落库怎么写进 MySQL（Service → Mapper）

`createVoucherOrder` 核心两步：

```java
seckillVoucherService.update(
    new LambdaUpdateWrapper<SeckillVoucher>()
        .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
        .gt(SeckillVoucher::getStock, 0)
        .setSql("stock=stock-1"));
this.save(voucherOrder);
```

大致 SQL：

```sql
UPDATE tb_seckill_voucher SET stock = stock - 1
WHERE voucher_id = ? AND stock > 0;

INSERT INTO tb_voucher_order (id, user_id, voucher_id, ...) VALUES (...);
```

`gt(stock, 0)` 是乐观条件，防库存减到负数；订单 `id` 来自 Stream 消息（`RedisIdWorker`），主键 `IdType.INPUT`。  
走代理调用故 `@Transactional` 生效：中途抛异常会回滚。但当前实现若 `update` 影响 0 行却不抛异常，仍可能继续 `save`——偏粗糙。

`seckillVoucherService` 如何接到 MyBatis：

```
ISeckillVoucherService extends IService<SeckillVoucher>
  → SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher>
  → SeckillVoucherMapper extends BaseMapper<SeckillVoucher>
  → 实体 @TableName("tb_seckill_voucher")
```

- 接口：`src/main/java/com/hmdp/mapper/SeckillVoucherMapper.java`
- XML：`src/main/resources/mapper/SeckillVoucherMapper.xml`（可为空；`update(wrapper)` 不依赖手写 XML）
- 启动类 `@MapperScan("com.hmdp.mapper")` 注册 Mapper；通用 CRUD 在 MyBatis-Plus 父类里

### 5.4 Redis 与 MySQL 库存会不会不同步

会。**短时间不同步是异步设计故意接受的**（最终一致，不是强一致）：

```
建券时：Redis stock = MySQL stock
秒杀瞬间：Lua 先扣 Redis
稍后：    消费者再扣 MySQL
```

| 情况 | 结果 |
|------|------|
| 正常延迟 | Redis 已少、MySQL 尚未少（预期） |
| 消费者卡住 / 一直失败 | Redis 已扣，MySQL 可能没扣，订单也可能没有 |
| DB 扣失败却未回补 Redis | 两边基准漂掉（本课补偿不完善） |

秒杀瞬间以 **Redis 为准**拦超卖；MySQL 是最终账本。生产常补：重试上限、死信、失败回补 Redis、对账校准。

### 5.5 `SimpleRedisLock` vs Redisson

| | `SimpleRedisLock` | Redisson `RLock` |
|--|-------------------|------------------|
| 加锁 | `SET lock:{name} {uuid-threadId} NX EX` | `tryLock()`（可重入、看门狗续期等） |
| 解锁 | `unlock.lua`：值相等才 `DEL` | `unlock()` |
| 本项目 | 注释演进用 | **当前**异步落库兜底 |

### 5.6 `RedisIdWorker`

```
id = (nowSeconds - 2022-01-01) << 32 | INCR icr:order:yyyyMMdd
```

- 高位时间：大致有序  
- 低 32 位：当天自增，多实例共享 Redis 不撞号  

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
- [ ] 说出当前路径是 **Lua + Stream**；Lua 是 Redis 占坑，不是 MySQL 落库完成
- [ ] 能串：`Controller → Lua → Stream → @PostConstruct 消费者 → createVoucherOrder → Mapper → DB → XACK`
- [ ] 知道 Stream / ACK / pending；一直失败时单线程会堵住后续落库
- [ ] 知道 Redis/MySQL 库存短暂不同步是预期，永久漂是补偿缺失
- [ ] 能说出 `seckillVoucherService.update` 如何经 MyBatis-Plus / `SeckillVoucherMapper` 落到表
- [ ] 知道 `SimpleRedisLock` 为何用 `unlock.lua`；`RedisIdWorker` 如何拼订单 id
- [ ] 演示：建消费组 → 建秒杀券 → 登录秒杀成功 → Redis 库存/Set 变化 → DB 出现订单；同用户再抢失败
- [ ] （加分）读注释掉的 `BlockingQueue` 版，说明为何改成 Redis Stream（多实例、重启不丢队列）

## 9. 下一步：Phase 4（探店与关注）

精读：`BlogServiceImpl`（点赞 ZSet）、`FollowServiceImpl`、Feed 推/拉  
主题：点赞排行、关注、粉丝时间线（Feed）。
