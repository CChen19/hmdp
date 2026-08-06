# 黑马点评（Redis 实战学习版）

基于 B 站[黑马程序员 Redis 教程](https://www.bilibili.com/video/BV1cr4y1671t) 实战项目整理，本地可运行，并附分阶段学习笔记。

原参考仓库：[cs001020/hmdp](https://github.com/cs001020/hmdp)（仅供学习参考）。

## 目录结构

| 路径 | 内容 |
|------|------|
| `src/` | Spring Boot 后端 |
| `frontend/` | 静态前端（来自 init 分支资源） |
| `docs/` | 分阶段学习笔记（Phase 0–5） |
| `scripts/` | 启动脚本与 nginx 配置 |
| `hmdp.sql` | 数据库初始化 |

## 本地启动

前置：MySQL（库 `hmdp`）、Redis（本仓库默认密码见 `application.yaml`）、JDK 17、Maven、nginx。

```bash
# 初始化库（按需）
# mysql -uroot -p < hmdp.sql

mvn -DskipTests package
./scripts/start-backend.sh    # API :8081
./scripts/start-frontend.sh   # 页面 :8080，/api → 8081
```

打开 http://127.0.0.1:8080/

秒杀相关若启动报 `NOGROUP ... stream.orders`，先执行：

```text
XGROUP CREATE stream.orders g1 $ MKSTREAM
```

## 学习笔记

| 阶段 | 文档 | 主题 |
|------|------|------|
| 0 | [`docs/PHASE0-SKELETON.md`](docs/PHASE0-SKELETON.md) | 环境与工程骨架 |
| 1 | [`docs/PHASE1-LOGIN.md`](docs/PHASE1-LOGIN.md) | 短信登录与 Redis Session |
| 2 | [`docs/PHASE2-CACHE.md`](docs/PHASE2-CACHE.md) | 商户缓存（穿透 / 击穿 / 雪崩） |
| 3 | [`docs/PHASE3-SECKILL.md`](docs/PHASE3-SECKILL.md) | 优惠券秒杀（锁 / Lua / Stream） |
| 4 | [`docs/PHASE4-BLOG-FOLLOW.md`](docs/PHASE4-BLOG-FOLLOW.md) | 探店点赞、关注与 Feed |
| 5 | [`docs/PHASE5-GEO-SIGN.md`](docs/PHASE5-GEO-SIGN.md) | 附近商户 GEO、签到 Bitmap |

## 说明

- `master`：完整实现，适合对照学习。
- 课程视频从实战篇（约 P24）起对应本仓库业务代码。
