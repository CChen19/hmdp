> **课程学习路径**；现行总索引见 [`CURRENT.md`](CURRENT.md)。GEO / 签到是课上收官能力，不是本仓库主亮点。

# Phase 5：附近商户 GEO + 签到 Bitmap

## 课程当时讲什么

- 附近的店：按类型 `GEOADD` / `GEOSEARCH`，再回表填详情与距离
- 签到：按月 Bitmap（一天 1 bit）；从今天往前数连续签到
- （加分）HyperLogLog 估 UV

## 本仓库现在

| 主题 | 现行约定 | 文档 |
|------|----------|------|
| 地位 | **背景能力**：代码仍在，非可靠性主路径 | [`CURRENT.md`](CURRENT.md) |
| GEO | 有坐标才走 GEO；须有预热数据；默认测试不灌全量 demo | 代码 `ShopServiceImpl` |
| 签到 | 行为大体同课上；接口需登录 | [`AUTH-BOUNDARIES.md`](AUTH-BOUNDARIES.md) |
| 主路径 | 秒杀 / 交易 / 商户缓存 | [`SECKILL.md`](SECKILL.md) · [`TRADE-FLOW.md`](TRADE-FLOW.md) · [`SHOP-CACHE.md`](SHOP-CACHE.md) |

## 还想对照代码就打开

- `ShopServiceImpl.queryShopByType`、`UserServiceImpl.sign` / `signCount`
- `RedisConstants`（`SHOP_GEO_KEY` / `USER_SIGN_KEY`）
