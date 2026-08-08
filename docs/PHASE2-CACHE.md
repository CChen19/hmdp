# Phase 2：商户缓存（穿透 / 击穿 / 雪崩）

> 前置：Phase 0 环境通 · Phase 1 登录懂 Token  
> 目标：搞清三大缓存问题各自是什么、本项目怎么解、线上实际走哪条路径

## 1. 先读这些文件（顺序）

| 顺序 | 文件 | 看什么 |
|------|------|--------|
| 1 | `ShopController` | `GET /shop/{id}` → `queryById` |
| 2 | `ShopServiceImpl.queryById` | **当前启用哪条策略**（注释掉的是演进过程） |
| 3 | `CacheClient` | 通用封装：穿透 `queryWithPassThrough`；逻辑过期读 `queryWithLogicalExpire`、写 `setWithLogicalExpire` |
| 4 | `RedisData` | 逻辑过期包装：`{ data, expireTime }` |
| 5 | `ShopServiceImpl` 里注释掉的三个私有方法 | 穿透 / 互斥锁击穿 / 逻辑过期击穿的「手写版」 |
| 6 | `ShopTypeServiceImpl` | **分类列表** `cache:type`（List），不是「某 type 下的店」 |
| 7 | `RedisConstants`（`CACHE_*` / `LOCK_*`） | Key 与 TTL |
| 8 | `RedisTest.testSaveShop` | 课程/仓库自带测试，给逻辑过期 **预热** `cache:shop:{id}` |

前端对照：`frontend/shop-detail.html`（调 `/api/shop/{id}`，白名单可不登录）。

## 2. 当前线上走哪条路

```45:58:src/main/java/com/hmdp/service/impl/ShopServiceImpl.java
    public Result queryById(Long id) {
        //缓存穿透
//        Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient.queryWithPassThrough(...);
        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        ...
    }
```

| 策略 | 代码位置 | 当前状态 |
|------|----------|----------|
| 空值防穿透 | `CacheClient.queryWithPassThrough` + 注释版 | **未启用** |
| 互斥锁防击穿 | `queryWithMutex`（注释） | **未启用** |
| 逻辑过期防击穿 | `CacheClient.queryWithLogicalExpire` | **线上启用** |
| 更新删缓存 | `ShopServiceImpl.update` | 启用（Cache Aside） |

重要后果：**逻辑过期要求缓存里事先有数据**。Key 不存在直接返回「店铺不存在」，**不会查库**。学习时先跑预热（见 §7）。

## 3. 三大问题一句话

| 问题 | 现象 | 本项目解法 |
|------|------|------------|
| **穿透** | 查根本不存在的 id，缓存永远 miss → 打爆 DB | 缓存**空值** `""`，短 TTL（`CACHE_NULL_TTL`） |
| **击穿** | 热点 key 正好过期，大量请求同时打 DB | **互斥锁**（只一人重建）或 **逻辑过期**（先返回可能已过期的旧值，异步重建）——用短暂陈旧换不打爆 DB |
| **雪崩** | 大量 key 同一时刻过期 → 同时打 DB | TTL 错开 / 多级缓存；本课店铺侧重点在前两个，类型 List 无统一大 TTL 也算一种「永不过期式」取舍 |

穿透 / 击穿 / 雪崩是**通用缓存问题**；本课用查商户演示，但同一套思路可套到别的热点读。

```
正常：  请求 → Redis 命中 → 返回
穿透：  请求假 id → Redis miss → DB miss →（不解）每次都打 DB
击穿：  热点过期瞬间 → 并发全部 miss →（不解）一起打 DB
雪崩：  一片 key 同时过期 → 流量洪峰打 DB
```

逻辑过期挡击穿时的并发画面（帮助理解「短暂陈旧」）：

```
请求A → 命中已逻辑过期的旧 JSON → 抢到锁 → 异步查 DB 重建 → 立刻返回旧数据
请求B/C → 同样命中旧 JSON → 抢锁失败 → 也立刻返回旧数据（不去 DB）
```

只有 A 打库；B/C 用旧缓存顶着。店名若刚改过，这几秒用户仍可能看到旧名。

## 4. `CacheClient` 三个方法 + 逻辑过期路径

| 方法 | 做什么 | 防什么 |
|------|--------|--------|
| `setWithLogicalExpire` | 写入 `RedisData{data, expireTime}`；Key **常不设** Redis TTL | （写工具） |
| `queryWithPassThrough` | miss 查库；DB 也没有则缓存空值短 TTL | **穿透**（当前 `queryById` 未启用） |
| `queryWithLogicalExpire` | 无 Key → null（不查库）；有 Key 则按 `expireTime` 决定是否异步重建，**总是先返回已有 data** | **击穿**（线上启用） |

旧缓存会**一直留在 Redis**，直到异步重建覆盖，或业务主动 `DEL`（如 `update`）。逻辑过期到了只表示 JSON 里时间过时，不是 Redis 把 Key 删了。

```
预热（一次性 / 定时）：
  Shop → RedisData{ data: Shop, expireTime: now+30min }
  → SET cache:shop:{id}  （过期靠 JSON 里的时间）

浏览器 GET /api/shop/1
  → ShopController → ShopServiceImpl.queryById
  → CacheClient.queryWithLogicalExpire
       1) GET cache:shop:1
          miss → return null → 「店铺不存在」（不查库！）
       2) 命中 → 反序列化 RedisData
          expireTime > now → 直接返回 Shop
          expireTime ≤ now → tryLock(lock:shop:1)
             抢到锁 → 线程池异步：查 DB → setWithLogicalExpire 写回 → 解锁
             没抢到 → 什么也不做
          无论是否重建 → **立刻返回旧 Shop**（可能已逻辑过期）
```

对比互斥锁（注释里的 `queryWithMutex`）：

```
miss → tryLock
  成功 → 查 DB → 写缓存（带真实 TTL）→ 解锁 → 返回
  失败 → sleep 50ms → 递归重试（等别人重建完）
```

| | 互斥锁 | 逻辑过期 |
|--|--------|----------|
| 过期后能否立刻响应 | 可能等待重建 | **立刻返回旧数据** |
| 一致性 | 较强（等新数据） | 允许短暂陈旧 |
| 是否必须预热 | 否（miss 会查库） | **是** |
| 穿透 | 常配合空值 | **本身不管穿透**（无 key 当不存在） |

## 5. 穿透：空值怎么挡

意图（注释版 / `queryWithPassThrough`）：

```
GET cache:shop:{id}
  有正常 JSON → 反序列化返回
  值是 ""     → 已知不存在，直接 null（不打 DB）
  完全 miss   → 查 DB
                  有 → SET JSON + TTL
                  无 → SET "" + 短 TTL（防反复穿透）
```

布隆过滤器是另一条路（本项目未实现）：启动时把存在的 id 放进过滤器，明显假 id 直接拒。

## 6. 接口分工与 Redis Key

前端常见链路（别把「分类」和「店铺」混成一个接口）：

```
首页分类：GET /shop-type/list              ← 无 shop id；返回的 id 是 typeId
点分类：  GET /shop/of/type?typeId=1       ← 传分类 id，要的是店列表
点某店：  GET /shop/{id}                   ← 这时才传店铺 id（路径参数）
```

详情响应当中已有 `Shop.typeId`（数字外键），本项目详情页一般不再联查 `ShopType` 表要分类名。

| Key | 类型 | TTL / 过期 | 内容 |
|-----|------|------------|------|
| `cache:shop:{id}` | String | 逻辑过期：JSON 内 `expireTime`；穿透/互斥版：真实 TTL 30min | Shop JSON 或 `RedisData` 包装 |
| `lock:shop:{id}` | String | 10s（`SET NX EX`） | 缓存重建互斥锁 |
| `cache:type` | List | 本实现未设 TTL | 每个元素一个 `ShopType` JSON（美食、KTV…） |

「一部分在 Redis、一部分在 DB」怎么理解：

- **`cache:type`**：整表缓存——List 非空就全用 Redis；空则整表从 DB 拉出再全部写入。不会半边 Redis、半边 DB 拼分类列表。
- **`cache:shop:{id}`**：按 id 独立。可以 1、2 在缓存、3 不在。逻辑过期下 3 miss → 直接当不存在（不查库）；若改用穿透方案，miss 才会回源 DB 再写回。

更新店铺：`update` 先改 MySQL，再 `DEL cache:shop:{id}`（Cache Aside）。  
逻辑过期场景下删 Key 后，下次查询会「像未预热」一样直接 miss——生产上逻辑过期常配合主动重建，而不是只删。

## 7. 动手验收

### 7.1 预热逻辑过期缓存

`RedisTest#testSaveShop` 会再起一套 Spring 上下文，**先停后端再预热**，避免 Redis 连接被搅乱：

```bash
pkill -f 'hmdp-1.0-SNAPSHOT.jar' 2>/dev/null || true
mvn -q -Dtest=RedisTest#testSaveShop test
# 再 ./scripts/start-backend.sh 或 java -jar target/hmdp-1.0-SNAPSHOT.jar
```

或用 Redis 看结果：

```bash
# <redis-password> 见 application.yaml → spring.redis.password
redis-cli -a '<redis-password>' --no-auth-warning GET cache:shop:1
# 应看到含 "data" 与 "expireTime" 的 JSON（不是裸 Shop）
```

### 7.2 查存在的店

```bash
curl -s http://127.0.0.1:8081/shop/1 | python3 -m json.tool | head -40
```

### 7.3 体会「未预热 = 逻辑过期当不存在」

```bash
redis-cli -a '<redis-password>' --no-auth-warning DEL cache:shop:1
curl -s http://127.0.0.1:8081/shop/1
# → success:false, 店铺不存在   （尽管 MySQL 里有 id=1）
# 再预热后恢复
```

### 7.4（可选）切换到穿透方案自测

临时改 `queryById` 为：

```java
Shop shop = cacheClient.queryWithPassThrough(
    CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
```

```bash
redis-cli -a '<redis-password>' --no-auth-warning DEL cache:shop:999999
curl -s http://127.0.0.1:8081/shop/999999   # 第一次打 DB，写空值
redis-cli -a '<redis-password>' --no-auth-warning GET cache:shop:999999
curl -s http://127.0.0.1:8081/shop/999999   # 第二次不应再打 DB
```

测完改回 `queryWithLogicalExpire`，并重新预热。

### 7.5 类型 List 缓存

```bash
redis-cli -a '<redis-password>' --no-auth-warning DEL cache:type
curl -s http://127.0.0.1:8081/shop-type/list >/dev/null   # 回源写 List
redis-cli -a '<redis-password>' --no-auth-warning LLEN cache:type
redis-cli -a '<redis-password>' --no-auth-warning LRANGE cache:type 0 0
```

浏览器：http://127.0.0.1:8080/ → 点进商户详情（需缓存已预热）。

## 8. 验收清单

- [ ] 能区分穿透 / 击穿 / 雪崩，各举一个本项目场景
- [ ] 说出当前 `queryById` 用的是逻辑过期，且**必须预热**
- [ ] 分清 `setWithLogicalExpire` / `queryWithLogicalExpire` / `queryWithPassThrough` 各自职责
- [ ] 看懂 `RedisData`：旧 Key 常一直保留到被覆盖或主动删除
- [ ] 能对比互斥锁 vs 逻辑过期：延迟 vs 一致性
- [ ] 分清 `/shop-type/list`、`/shop/of/type`、`/shop/{id}` 各自传什么 id
- [ ] 知道更新为何「先 DB 后删缓存」
- [ ] 演示：预热 → `/shop/1` 成功；删 Key → 立刻「不存在」；类型 List 回源一次后命中
- [ ] （加分）临时切 `queryWithPassThrough`，演示假 id 空值缓存

## 9. 下一步：Phase 3（优惠券秒杀）

精读：`VoucherOrderServiceImpl`、`seckill.lua`、`SimpleRedisLock` / Redisson、`RedisIdWorker`  
主题：一人一单、分布式锁、Lua 原子扣减、Stream 异步下单。
