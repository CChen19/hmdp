# Phase 5：附近商户 GEO + 签到 Bitmap（收官）

> 前置：Phase 0–4；本阶段两条线可并行学，互不依赖  
> 目标：搞清 Redis GEO 怎么做「附近的店」、Bitmap 怎么做签到与连续天数；知道数据要先预热进 Redis

## 1. 先读这些文件（顺序）

| 顺序 | 文件 | 看什么 |
|------|------|--------|
| 1 | `ShopController` → `GET /shop/of/type` | 可选参数 `x` / `y`（经纬度） |
| 2 | `ShopServiceImpl.queryShopByType` | 无坐标走 DB；有坐标走 **GEOSEARCH** |
| 3 | `RedisTest#testLoadShopData` | **GEO 必须预热**：按 `typeId` 批量 `GEOADD` |
| 4 | `UserController` → `/user/sign`、`/user/sign/count` | 签到入口（需登录） |
| 5 | `UserServiceImpl.sign` / `signCount` | **Bitmap** 写位、数连续签到 |
| 6 | `RedisConstants`（`SHOP_GEO_KEY` / `USER_SIGN_KEY`） | Key 前缀 |
| 7 | （加分）`RedisTest#testHyperLogLog` | UV 统计另一条路：HyperLogLog |

前端对照：`frontend/shop-list.html`（写死杭州附近坐标 `x/y`，带距离展示）。签到接口前端页未必接好，用 curl 验收即可。

## 2. 当前线上走哪条路

| 能力 | 代码位置 | Redis | 当前状态 |
|------|----------|-------|----------|
| 按类型列表（无坐标） | `queryShopByType` 前半 | 无 | MySQL 分页 |
| 附近商户（有 x,y） | `queryShopByType` 后半 | GEO `shop:geo:{typeId}` | **启用**（须预热） |
| 签到 | `UserServiceImpl.sign` | Bitmap `sign:yyyy:MM:{userId}` | **启用** |
| 连续签到天数 | `signCount` | `BITFIELD` 取本月到今天的位 | **启用** |
| UV 演示 | `testHyperLogLog` | HyperLogLog | 仅测试，无业务接口 |

重要后果：

1. **GEO Key 不存在 → 附近列表为空**，不会自动从 DB 建索引。学之前先跑 `testLoadShopData`。  
2. 前端始终传了 `x/y`，所以商户列表页走的是 **GEO 路径**，不是纯 DB。  
3. 签到按 **自然月** 一个 Key；跨月重新累计（连续天数只看当月到今天）。

## 3. 两个问题一句话

| 问题 | 现象 | 本项目解法 |
|------|------|------------|
| **附近的店** | 按距离排序、限定半径，DB 算球面距离又慢又难分页 | Redis **GEO**：按类型一个集合，member=店铺 id，坐标=经纬度 |
| **签到 / 连续天数** | 每人每天一记，用行存或 Hash 太浪费 | **Bitmap**：一天占 1 bit；从今天往回数连续 `1` |

```
附近店：  预热 GEOADD → GEOSEARCH 半径内按距离排序 → 截分页 → 查 Shop 填 distance
签到：    SETBIT sign:yyyy:MM:{uid} (日-1) 1
连续：    BITFIELD 取本月前 N 位 → 从今天对应位往回数有多少个连续 1
```

## 4. 端到端：附近商户（GEO）

### 4.1 预热（一次性）

```
RedisTest#testLoadShopData：
  查全部 Shop
  → 按 typeId 分组
  → 每组 GEOADD shop:geo:{typeId}
       member = shopId
       point  = (x 经度, y 纬度)
```

底层：GEO 建立在 **ZSet** 上，score 是 GeoHash；你看到的 API 是地理语义。

### 4.2 查询

```
浏览器 GET /api/shop/of/type?typeId=1&current=1&x=120.15&y=30.33
  → ShopController
  → x,y 都有：
       from = (current-1)*10 , end = current*10
       GEOSEARCH shop:geo:{typeId}
         FROMLONLAT x y
         BYRADIUS 5000 m          // 固定 5km
         WITHDIST
         COUNT end                // 先取前 end 条（按距离）
       → skip(from) 得到本页 id + distance
       → MySQL IN id … ORDER BY FIELD 保序
       → 填 Shop.distance（米）返回
  → x 或 y 缺省：普通 MySQL 按 typeId 分页（无距离）
```

| | 无坐标 | 有坐标（本课重点） |
|--|--------|-------------------|
| 数据源 | MySQL | Redis GEO → 再查 MySQL 详情 |
| 排序 | 默认/库序 | **距离由近到远** |
| 前提 | 无 | **必须 GEOADD 预热** |
| 分页 | Page 正常 | `COUNT end` + Java `skip(from)`（深分页会多取） |

半径写死 **5000 米**；页大小 `MAX_PAGE_SIZE = 10`。

## 5. 端到端：签到（Bitmap）

### 5.1 签到

```
POST /api/user/sign（已登录）
  → key = sign:{yyyy}:{MM}:{userId}     // 例 sign:2026:08:2
  → offset = 今天是本月第几天 - 1       // 1 号 → bit0，5 号 → bit4
  → SETBIT key offset 1
```

一个月最多约 31 bit，一个用户一个月一个小 String，极省空间。

### 5.2 连续签到天数

```
GET /api/user/sign/count
  → 同一 key
  → BITFIELD key GET u{dayOfMonth} 0
       // 从 offset 0 起取「本月到今天」共 dayOfMonth 位，当成无符号整数
  → 转二进制，从最低位往高位扫（对应从「今天」往「月初」）
       遇到 1 → count++
       遇到 0 → 停下
  → 返回 count
```

| 操作 | 含义 |
|------|------|
| `SETBIT` | 某一天打卡 |
| `GETBIT` | 查某一天有没有（本项目未单独封装） |
| `BITFIELD GET uN 0` | 一次取出前 N 天位图，便于算连续 |

注意：连续天数是「**截止今天、当月内**从今天往前不间断」；中间断一天就停，且不跨月。

## 6. Redis Key 速查

| Key | 类型 | 内容 |
|-----|------|------|
| `shop:geo:{typeId}` | GEO（底层 ZSet） | member=店铺 id；坐标=经度 x / 纬度 y |
| `sign:{yyyy}:{MM}:{userId}` | String（Bitmap 视图） | 第 `(日-1)` 位 = 是否签到 |
| `hl1`（测试） | HyperLogLog | UV 基数估算演示 |

权限：`/shop/**` 白名单，附近店可不登录；`/user/sign*` **要登录**。

## 7. 动手验收

### 7.1 预热 GEO（先停后端，与 Phase 2 预热同理）

```bash
pkill -f 'hmdp-1.0-SNAPSHOT.jar' 2>/dev/null || true
mvn -q -Dtest=RedisTest#testLoadShopData test
# 再启动后端
```

```bash
redis-cli -a 001020 --no-auth-warning ZCARD shop:geo:1
# > 0 说明该类型已入库
redis-cli -a 001020 --no-auth-warning GEOPOS shop:geo:1 1
# 看店铺 1 的坐标
```

### 7.2 查附近店

前端默认坐标约：`x=120.149993, y=30.334229`（杭州一带，与库里店铺一致）。

```bash
curl -s "http://127.0.0.1:8081/shop/of/type?typeId=1&current=1&x=120.149993&y=30.334229" \
  | python3 -m json.tool | head -50
# 每条应有 distance（米），且大致由近到远
```

对比不传坐标（无距离、走 DB）：

```bash
curl -s "http://127.0.0.1:8081/shop/of/type?typeId=1&current=1" | python3 -m json.tool | head -30
```

浏览器：http://127.0.0.1:8080/ → 点某一类商户 → `shop-list.html` 看距离文案。

### 7.3 签到

```bash
# TOKEN 同 Phase 1
curl -s -X POST "http://127.0.0.1:8081/user/sign" -H "authorization: TOKEN"

redis-cli -a 001020 --no-auth-warning \
  GETBIT sign:$(date +%Y:%m):USER_ID $(( $(date +%d) - 1 ))
# → 1

curl -s "http://127.0.0.1:8081/user/sign/count" -H "authorization: TOKEN"
# 今天刚签且昨天没签 → 一般为 1；若连续多天都签过会更大
```

用 `SETBIT` 把昨天也打成 1，再调 `sign/count`，连续天数应变 2（同一自然月内）。

### 7.4（加分）HyperLogLog

```bash
mvn -q -Dtest=RedisTest#testHyperLogLog test
# 往 hl1 丢 100 万 user_*，PFCOUNT 结果接近但不必精确等于 1000000
```

适合海量 UV：占固定小内存，允许误差；不适合要精确去重名单的场景。

## 8. 验收清单

- [ ] 能说明 GEO 解决什么问题，以及和「DB 算距离」的取舍
- [ ] 知道 GEO 底层是 ZSet，业务上按类型分 Key：`shop:geo:{typeId}`
- [ ] 说出有 x,y 才走 GEO；**必须先 `testLoadShopData`**
- [ ] 看懂分页：`COUNT end` + `skip(from)`，半径 5km
- [ ] 能说明 Bitmap 签到：一天 1 bit，offset = 日 - 1
- [ ] 看懂 `signCount`：从今天往回数连续 `1`
- [ ] 演示：预热 → 带坐标列表有 `distance`；登录签到 → `GETBIT` 为 1 → `sign/count` 合理
- [ ] （加分）说清 HyperLogLog 适合 UV、有误差、省内存

## 9. 全系列收官对照

| Phase | 主题 | 核心 Redis |
|-------|------|------------|
| 0 | 环境与骨架 | 连通 |
| 1 | 登录 | String / Hash + TTL |
| 2 | 商户缓存 | String、逻辑过期、锁 |
| 3 | 秒杀 | String、Set、Lua、Stream、锁 |
| 4 | 探店关注 | ZSet、Set、Feed |
| 5 | 附近店 + 签到 | **GEO、Bitmap**（+ HLL 加分） |

学完后建议自己默画一张：「哪个接口 → 哪个 Key → 什么结构 → 解决什么问题」。
