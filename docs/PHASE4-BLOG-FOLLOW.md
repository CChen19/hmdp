> **课程演进笔记** — 这是课上的 **Phase 4：探店 / 点赞 / 关注 / Feed**。仓库工程里的「Phase 4 证据」（actuator、bench、ops）**不是**本文件；可靠性索引见 [`CURRENT.md`](CURRENT.md)。

# Phase 4：探店与关注（点赞 ZSet / 关注 Set / Feed）

> 课上目标：点赞为何用 ZSet、关注 Set + `SINTER`、发笔记推模式写 `feed:{粉丝}`、滚动分页 `lastId` + `offset`。

## 课程路径（当时在学什么）

1. `BlogServiceImpl.likeBlog`：ZSet `blog:liked:{id}` 防重复 + 按时间排点赞榜  
2. `FollowServiceImpl`：DB 存关系 + Redis `follows:{userId}`；共同关注 `SINTER`  
3. `saveBlog`：**推模式** fan-out 到粉丝收件箱  
4. `queryBlogOfFollow`：`ZREVRANGEBYSCORE` + `ScrollResult`  

本仓库重建重点在**券交易与缓存正确性**；点赞 / Feed **仍在代码里**，但不是可靠性改造的主线。

## 课程当时 vs 本仓库现在

| 点 | 课程当时 | 本仓库现在 |
|----|----------|------------|
| 主题 | 探店社交（本文件） | **不变**：仍是 blog / like / follow / feed |
| 「Phase 4」一词 | 课程第四阶段 = 本文件 | 工程口述「Phase 4」常指 **证据 / actuator / bench** — 勿混 |
| 改造投入 | 课上完整讲 Feed | 未当作本轮可靠性主路径重写；细节以代码为准 |
| 要看现行券/缓存 | — | [`SECKILL.md`](SECKILL.md) · [`TRADE-FLOW.md`](TRADE-FLOW.md) · [`SHOP-CACHE.md`](SHOP-CACHE.md) |

## 建议打开

- 代码：`BlogServiceImpl`、`FollowServiceImpl`（若要复习课上结构）  
- [`CURRENT.md`](CURRENT.md) — 生产索引（券 / 缓存 / 鉴权）  
- 下一步：[`PHASE5-GEO-SIGN.md`](PHASE5-GEO-SIGN.md)
