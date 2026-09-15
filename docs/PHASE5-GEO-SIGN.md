> **课程演进笔记** — GEO / 签到是课上收官能力。本仓库亮点在券交易与商户缓存；这两条线仍可用，但不是主宣传路径。现行索引：[`CURRENT.md`](CURRENT.md)。

# Phase 5：附近商户 GEO + 签到 Bitmap

> 课上目标：`GEOSEARCH` 做附近的店；Bitmap 做签到与当月连续天数。

## 课程路径（当时在学什么）

1. `ShopServiceImpl.queryShopByType`：有 `x/y` 走 GEO；无坐标走 DB 分页  
2. 预热：`GEOADD shop:geo:{typeId}`（课上常用测试预热）  
3. `UserServiceImpl.sign` / `signCount`：按月 Bitmap，`SETBIT` + 从今天往回数连续 `1`  
4. （加分）HyperLogLog 估 UV  

## 课程当时 vs 本仓库现在

| 点 | 课程当时 | 本仓库现在 |
|----|----------|------------|
| 地位 | 系列收官重点之一 | **背景能力**：代码仍在，非本轮可靠性主叙事 |
| GEO | 须预热，否则附近列表空 | 同左；默认 `mvn test` **不**灌百万/全量 GEO demo |
| 签到 | 当月连续天数 | 行为大体同课上；鉴权见 [`AUTH-BOUNDARIES.md`](AUTH-BOUNDARIES.md) |
| 主路径文档 | 本 PHASE 长文 | 券 / 缓存 / 交易 → [`SECKILL.md`](SECKILL.md) · [`SHOP-CACHE.md`](SHOP-CACHE.md) · [`TRADE-FLOW.md`](TRADE-FLOW.md) |

## 建议打开

- [`CURRENT.md`](CURRENT.md)  
- 全系列对照（课程序号，不是工程里程碑）：Phase 0 环境 → 1 登录 → 2 缓存 → 3 秒杀 → 4 探店 → 5 GEO/签到  
