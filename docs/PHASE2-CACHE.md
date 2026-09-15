> **课程演进笔记** — 记录 B 站课当时教什么。商户缓存的**现行契约**见 [`SHOP-CACHE.md`](SHOP-CACHE.md)，勿沿用旧「miss = 店铺不存在」。

# Phase 2：商户缓存（穿透 / 击穿 / 雪崩）

> 课上目标：用查商户演示三大缓存问题；对比空值穿透、互斥锁、逻辑过期。

## 课程路径（当时在学什么）

1. `ShopServiceImpl.queryById` + `CacheClient`（穿透 / 逻辑过期封装）  
2. 穿透：假 id 打爆 DB → 缓存空值短 TTL  
3. 击穿：热点过期 → 互斥锁重建 **或** 逻辑过期（先返回旧值、异步重建）  
4. Cache Aside：更新 DB 后删 `cache:shop:{id}`  
5. 分类列表：`cache:type` List  

逻辑过期适合**展示类**店铺详情；**不是**秒杀库存方案。

## 课程当时 vs 本仓库现在

| 点 | 课程当时（常见笔记） | 本仓库现在 |
|----|----------------------|------------|
| 缓存 miss | 逻辑过期下 Key 不存在 → **直接「店铺不存在」、不查库** | **miss → 回源 MySQL**；真无店铺才「不存在」 |
| 空值 | 多在穿透方案里讲 | 空标记 vs Redis/DB 故障 → **UNAVAILABLE**（不是假「不存在」） |
| 失效时机 | 更新后立刻 `DEL` | **afterCommit** 再删缓存 |
| 重建锁 | 简单 unlock | **持有者校验**再解锁 |
| 详情 | — | [`SHOP-CACHE.md`](SHOP-CACHE.md) |

## 建议打开

- [`SHOP-CACHE.md`](SHOP-CACHE.md)  
- [`CURRENT.md`](CURRENT.md)  
- 下一步：[`PHASE3-SECKILL.md`](PHASE3-SECKILL.md)
