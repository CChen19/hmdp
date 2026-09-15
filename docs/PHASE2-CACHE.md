> **课程学习路径**；商户缓存现行契约见 [`SHOP-CACHE.md`](SHOP-CACHE.md) 与 [`CURRENT.md`](CURRENT.md)。

# Phase 2：商户缓存（穿透 / 击穿 / 雪崩）

## 课程当时讲什么

- 穿透：假 id 打爆 DB → 空值短 TTL
- 击穿：热点过期 → 互斥锁重建，或逻辑过期（先返回旧值、异步重建）
- 雪崩：TTL 错开等（课上店铺侧重点在前两个）
- `CacheClient` 封装；更新后 Cache Aside 删 key
- 逻辑过期用于**店铺展示**，不是秒杀库存

## 本仓库现在

| 主题 | 现行约定 | 文档 |
|------|----------|------|
| 缓存 miss | **回源 MySQL**；只有库中确无才「不存在」 | [`SHOP-CACHE.md`](SHOP-CACHE.md) |
| 空标记 vs 故障 | 空标记 ≠ Redis/DB 故障；故障走 **UNAVAILABLE** | 同上 |
| 失效 | 店铺更新后 **afterCommit** 再删缓存 | 同上 |
| 重建锁 | 解锁校验持有者 | 同上 |
| 策略 | 仍用逻辑过期读路径，但 miss 会查库 | 同上 |

## 还想对照代码就打开

- `ShopServiceImpl.queryById`、`CacheClient`
- `ShopTypeServiceImpl`、`RedisConstants`（`CACHE_*` / `LOCK_*`）
- 下一步：[`PHASE3-SECKILL.md`](PHASE3-SECKILL.md)
