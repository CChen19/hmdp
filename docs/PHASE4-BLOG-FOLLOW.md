> **课程学习路径**；这是课上的探店/关注阶段。工程可靠性索引见 [`CURRENT.md`](CURRENT.md)（勿把本文件当成 bench / 证据手册）。

# Phase 4：探店与关注（点赞 ZSet / 关注 Set / Feed）

## 课程当时讲什么

- 点赞：ZSet 防重复 + 按时间排点赞榜
- 关注：MySQL 存关系 + Redis Set；共同关注 `SINTER`
- Feed：**推模式**发笔记 fan-out 到粉丝收件箱
- 滚动分页：`lastId` + `offset`

## 本仓库现在

| 主题 | 现行约定 | 文档 |
|------|----------|------|
| 主题未改 | 仍是 **blog / like / follow / feed** | 本文件（课程） |
| 改造范围 | 本轮可靠性工作**没有**重做这条社交路径 | [`CURRENT.md`](CURRENT.md) |
| 「Phase 4」易混 | 课程 Phase 4 = 本文件；工程口述 Phase 4 常指证据/actuator/bench | [`BENCH.md`](BENCH.md) 仅证据，非本课 |
| 主叙事 | 券交易与商户缓存 | [`SECKILL.md`](SECKILL.md) · [`TRADE-FLOW.md`](TRADE-FLOW.md) · [`SHOP-CACHE.md`](SHOP-CACHE.md) |

## 还想对照代码就打开

- `BlogServiceImpl`、`FollowServiceImpl`、`ScrollResult`
- 下一步：[`PHASE5-GEO-SIGN.md`](PHASE5-GEO-SIGN.md)
