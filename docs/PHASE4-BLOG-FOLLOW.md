# Phase 4：探店与关注（点赞 ZSet / 关注 Set / Feed 推模式）

> 前置：Phase 0 环境通 · Phase 1 登录懂 Token（点赞 / 关注 / 发笔记 / Feed 都要登录）  
> 目标：搞清点赞为何用 ZSet、关注为何用 Set、Feed 推模式怎么写收件箱、滚动分页的 `lastId` + `offset` 怎么算

## 1. 先读这些文件（顺序）

| 顺序 | 文件 | 看什么 |
|------|------|--------|
| 1 | `BlogController` | 发笔记 / 点赞 / 热门 / 详情 / 点赞榜 / 关注时间线 |
| 2 | `BlogServiceImpl.likeBlog` + `queryBlogLikesById` | **ZSet**：防重复点赞 + 按时间排序的点赞榜 |
| 3 | `FollowController` + `FollowServiceImpl` | 关注/取关写 DB + Redis Set；共同关注 `SINTER` |
| 4 | `BlogServiceImpl.saveBlog` | **推模式**：发笔记 → 查粉丝 → 写入每人 `feed:{userId}` |
| 5 | `BlogServiceImpl.queryBlogOfFollow` | 收件箱滚动分页：`ZREVRANGEBYSCORE` + `ScrollResult` |
| 6 | `ScrollResult` | 前端下一页要用的 `minTime` / `offset` |
| 7 | `RedisConstants`（`BLOG_LIKED_KEY` / `FEED_KEY`） | Key 前缀；注意关注 Set 在代码里硬编码 `follows:` |

前端对照：

| 页面 | 调什么 |
|------|--------|
| `frontend/index.html` | 热门笔记 `GET /blog/hot`（白名单） |
| `frontend/blog-detail.html` | 详情 / 点赞 / 点赞榜 / 关注作者 |
| `frontend/blog-edit.html` | 发笔记 `POST /blog` |
| `frontend/info.html` | 「关注」页签 → `GET /blog/of/follow` |
| `frontend/other-info.html` | 关注别人 + 共同关注 |

## 2. 当前线上走哪条路

| 能力 | 代码位置 | Redis | 当前状态 |
|------|----------|-------|----------|
| 点赞 / 取消 | `likeBlog` | ZSet `blog:liked:{blogId}` | **启用**（DB `liked±1` + ZSet） |
| 是否已赞 | `isBlogLiked` | ZSet `score` | 启用 |
| 点赞 TopN | `queryBlogLikesById` | `ZRANGE` 最早一批 | 启用（按点赞时间升序） |
| 关注 / 取关 | `FollowServiceImpl.follow` | Set `follows:{userId}` | **启用**（DB + Set） |
| 是否已关注 | `isFollow` | **只查 MySQL** | 启用 |
| 共同关注 | `followCommons` | `SINTER` | 启用 |
| Feed 写入 | `saveBlog` | ZSet `feed:{粉丝id}` | **推模式（Fan-out on write）** |
| Feed 读取 | `queryBlogOfFollow` | `ZREVRANGEBYSCORE` | 滚动分页，每页 **2** 条（演示用） |

重要后果：

1. **点赞名单在 Redis**，计数在 MySQL；用 ZSet 的 member 防重复，score=时间戳方便排「最先点赞」。  
2. **关注关系权威在 MySQL**；Redis Set 主要服务「共同关注」交集。若只写库不写 Set，共同关注会空。  
3. **Feed 是推模式**：发笔记时按粉丝列表 fan-out；粉丝再从自己收件箱拉。未关注时收件箱不会有新笔记。  
4. 热门列表 `/blog/hot` 在白名单；点赞、发笔记、关注、Feed **必须带 Token**。

## 3. 三个问题一句话

| 问题 | 现象 | 本项目解法 |
|------|------|------------|
| **重复点赞** | 同一用户对同一笔记刷赞 | ZSet：`score==null` 才点赞；已有则取消 |
| **点赞排行** | 要「最早点赞的几个人」头像墙 | ZSet score=毫秒时间，`ZRANGE` 取分最低的一批 |
| **关注动态** | 看关注的人发了什么 | 发笔记时推到粉丝 `feed:` 收件箱；读时按 score 倒序滚动 |

```
点赞：  未赞 → DB liked+1 + ZADD；已赞 → DB liked-1 + ZREM
关注：  关注 → INSERT tb_follow + SADD follows:{我}；取关反过来
发笔记：INSERT tb_blog → 查粉丝 → 每人 ZADD feed:{粉丝} blogId score=now
读 Feed：ZREVRANGEBYSCORE feed:{我} 0 max LIMIT offset 2 → 拼 ScrollResult
```

## 4. 端到端：点赞（ZSet）

```
浏览器 PUT /api/blog/like/{blogId}（已登录）
  → BlogController.likeBlog
  → BlogServiceImpl.likeBlog
       key = blog:liked:{blogId}
       ZSCORE key {userId}
         null  → UPDATE liked=liked+1
                 成功则 ZADD key {userId} {nowMs}
         有分  → UPDATE liked=liked-1
                 成功则 ZREM key {userId}
  → Result.ok()

详情/热门里展示 isLike：
  有登录用户 → ZSCORE 非 null 则 blog.isLike=true
  未登录     → 不查，isLike 保持 null/false

点赞榜 GET /api/blog/likes/{blogId}：
  ZRANGE blog:liked:{id} 0 6     // 按 score 升序，最早点赞的一批
  → 按同一顺序查用户（MySQL FIELD 保序）→ List<UserDTO>
```

| | Set | ZSet（本项目） |
|--|-----|----------------|
| 防重复 | 可以 | 可以（member 唯一） |
| 点赞时间 / 排序 | 无 | **score 存时间戳** |
| TopN「最先赞」 | 做不到（无序） | `ZRANGE` 直接取 |

说明：代码注释写 top5，实际 `range(0, 6)` 是最多 **7** 个；学习时抓住「按时间排序」即可。

## 5. 端到端：关注 + 共同关注（Set）

```
关注 PUT /api/follow/{followUserId}/true
  → INSERT tb_follow (userId=我, followUserId=对方)
  → SADD follows:{我} {对方}

取关 PUT /api/follow/{followUserId}/false
  → DELETE tb_follow
  → SREM follows:{我} {对方}

是否关注 GET /api/follow/or/not/{id}
  → 只查 MySQL count（不读 Redis）

共同关注 GET /api/follow/common/{对方id}
  → SINTER follows:{我} follows:{对方}
  → 交集 userId 列表查用户信息返回
```

`follows:{userId}` 存的是「我关注了谁」（following），不是粉丝列表。  
发笔记推 Feed 时，粉丝是从 **MySQL** `tb_follow.follow_user_id = 作者` 查出来的，不是从 Redis 反查。

## 6. 端到端：Feed 推模式 + 滚动分页

### 6.1 写入（Fan-out on write）

```
浏览器 POST /api/blog  {title, content, images, shopId...}
  → 填 userId → INSERT tb_blog
  → SELECT * FROM tb_follow WHERE follow_user_id = 作者
  → 对每个粉丝：
       ZADD feed:{粉丝userId} {blogId} {nowMs}
  → 返回 blogId
```

| | 推模式（本项目） | 拉模式 |
|--|------------------|--------|
| 发笔记时 | 写 N 个粉丝收件箱 | 几乎只写自己的发件箱 |
| 读 Feed 时 | 读自己收件箱即可 | 要查关注列表再合并时间线 |
| 适合 | 粉丝量不大 / 读多写少 | 大 V 粉丝极多（避免一次推爆） |

本课用推模式，代码更直观；大 V 场景课程里会提「推拉结合」，本项目未做。

### 6.2 读取（滚动分页）

```
浏览器 GET /api/blog/of/follow?lastId={max}&offset={os}
  → key = feed:{当前用户}
  → ZREVRANGEBYSCORE key 0 max WITHSCORES LIMIT offset 2
       // 分从高到低；第一次 max≈当前时间，offset=0
  → 解析：blogId 列表 + 本页最小 score(minTime) + 连续相同 minTime 的个数(os)
  → 按 id 查 Blog，填 isLike
  → ScrollResult{ list, minTime, offset: os }
```

前端（`info.html`）约定：

- 第一次：`lastId = now+1`，`offset = 0`  
- 下一页：把上次返回的 `minTime` 当作新的 `lastId`，`offset` 用上次返回的 `offset`  
- 为何要 `offset`：同一毫秒可能多条笔记，score 相同；下一次若仍从该 `max` 取且 `offset=0` 会重复。`offset` = 本页末尾那个 `minTime` 连续出现了几次，下次跳过它们。

每页固定 **2** 条是演示滚动分页；生产会更大。

## 7. Redis Key 速查

| Key | 类型 | score / 成员 | 内容 |
|-----|------|--------------|------|
| `blog:liked:{blogId}` | ZSet | score=点赞毫秒时间；member=userId | 点过赞的用户 |
| `follows:{userId}` | Set | member=被关注者 userId | 我的关注列表（共同关注用） |
| `feed:{userId}` | ZSet | score=推送毫秒时间；member=blogId | 粉丝收件箱 |

常量：`BLOG_LIKED_KEY` / `FEED_KEY` 在 `RedisConstants`；`follows:` 与部分 `feed:` 拼接在 Service 里写死——读代码时别被常量表误导。

白名单：仅 `/blog/hot`；`/blog/{id}`、`/blog/like/**`、`/blog/of/**`、`/follow/**` 都要登录。

## 8. 动手验收

### 8.1 准备两个用户

需要 **用户 A（作者）** 和 **用户 B（粉丝）**。用两个手机号各登录一次，分别记下 `TOKEN_A`、`TOKEN_B` 和双方 `userId`（`GET /user/me`）。

```bash
# 用户 B 关注用户 A
curl -s -X PUT "http://127.0.0.1:8081/follow/USER_A_ID/true" \
  -H "authorization: TOKEN_B"

redis-cli -a 001020 --no-auth-warning SMEMBERS follows:USER_B_ID
# 应含 USER_A_ID
```

### 8.2 点赞 ZSet

找一篇已有笔记 id（首页热门或库里的 `tb_blog`），用 B 点赞：

```bash
curl -s -X PUT "http://127.0.0.1:8081/blog/like/BLOG_ID" \
  -H "authorization: TOKEN_B"

redis-cli -a 001020 --no-auth-warning ZRANGE blog:liked:BLOG_ID 0 -1 WITHSCORES
curl -s "http://127.0.0.1:8081/blog/likes/BLOG_ID" \
  -H "authorization: TOKEN_B" | python3 -m json.tool
```

再点一次同一接口 → 取消赞，ZSet 中该 userId 消失，`liked` 回落。

### 8.3 发笔记推 Feed

用 **A** 发一篇（字段可按前端 `blog-edit` 简化）：

```bash
curl -s -X POST "http://127.0.0.1:8081/blog" \
  -H "authorization: TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{
    "shopId": 1,
    "title": "Phase4 探店测试",
    "content": "关注推送验证",
    "images": "/imgs/blogs/blog1.jpg"
  }'
# data 为新 blogId
```

```bash
redis-cli -a 001020 --no-auth-warning ZRANGE feed:USER_B_ID 0 -1 WITHSCORES
# 应出现刚发的 blogId
```

用 **B** 读收件箱：

```bash
NOW=$(($(date +%s%3N)+1))
curl -s "http://127.0.0.1:8081/blog/of/follow?lastId=$NOW&offset=0" \
  -H "authorization: TOKEN_B" | python3 -m json.tool
# → list 含新笔记；记下 minTime、offset，再请求下一页
```

### 8.4 共同关注

让 A、B 都关注同一个第三方用户 C（或互相关注场景下找交集），再：

```bash
curl -s "http://127.0.0.1:8081/follow/common/USER_A_ID" \
  -H "authorization: TOKEN_B" | python3 -m json.tool
```

浏览器路径：登录 B → 打开 A 发的笔记详情关注作者 → 个人页「关注」页签看 Feed；或 `other-info.html?id=对方` 看共同关注。

## 9. 验收清单

- [ ] 能说明点赞为何用 **ZSet** 而不是 Set / 只改 DB
- [ ] 说出点赞：DB 管计数、Redis 管「谁赞过 + 时间」
- [ ] 看懂关注：DB 存关系，Set 服务 `SINTER` 共同关注
- [ ] 能画出 Feed **推模式**：发笔记 → 查粉丝 → `ZADD feed:{粉丝}`
- [ ] 看懂滚动分页：`lastId`(max score) + `offset`（跳过同分）+ 每页 2 条
- [ ] 知道 `/blog/hot` 可不登录，点赞/关注/Feed 必须登录
- [ ] 演示：B 关注 A → A 发笔记 → B 的 `feed:` 有 blogId → `/blog/of/follow` 能读到；点赞进 ZSet，再点取消
- [ ] （加分）对比推 vs 拉；说明大 V 时纯推的压力

## 10. 下一步：Phase 5（附近商户 GEO + 签到 Bitmap）

见 [`PHASE5-GEO-SIGN.md`](PHASE5-GEO-SIGN.md)：`ShopServiceImpl.queryShopByType`、`UserServiceImpl.sign` / `signCount`、`RedisTest#testLoadShopData`。
