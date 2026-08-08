# 黑马点评（Redis 实战学习版）

基于 B 站[黑马程序员 Redis 教程](https://www.bilibili.com/video/BV1cr4y1671t) 整理，可本地运行，并附分阶段笔记。原参考仓库：[cs001020/hmdp](https://github.com/cs001020/hmdp)。

| 路径 | 内容 |
|------|------|
| `src/` | Spring Boot 后端 |
| `frontend/` | 静态前端 |
| `docs/` | 分阶段学习笔记（Phase 0–5） |
| `scripts/` | 启动脚本与 nginx 配置 |
| `hmdp.sql` | 数据库初始化 |

## 本机运行

前置：JDK 17、Maven、MySQL（库 `hmdp`）、Redis、nginx。账号密码见 `src/main/resources/application.yaml`。

| 服务 | 地址 |
|------|------|
| API | `http://127.0.0.1:8081` |
| 页面 | `http://127.0.0.1:8080/`（nginx 托管 `frontend/`，`/api` 反代到 8081） |

```bash
# 1) 中间件（示例）
# brew services start mysql redis
mysql -uroot -p'<mysql-password>' < hmdp.sql

# 2) 后端 :8081（改代码后需先 package）
mvn -DskipTests package && ./scripts/start-backend.sh

# 3) 另开终端：前端 :8080
./scripts/start-frontend.sh
```

打开 http://127.0.0.1:8080/ 。登录验证码在后端日志：`发送验证码成功，验证码：xxxxxx`。秒杀启动若报 `NOGROUP ... stream.orders`，在 Redis 执行：

```text
XGROUP CREATE stream.orders g1 $ MKSTREAM
```

停止：`lsof -iTCP:8081 -sTCP:LISTEN` 后 `kill <pid>`；nginx 用 `nginx -s stop -p "$(pwd)" -c "$(pwd)/scripts/nginx-hmdp.conf`。

可选：用 IntelliJ 打开仓库根目录，SDK 选 JDK 17 并启用 Lombok，Run `HmDianPingApplication`（勿与脚本抢 8081）。工程骨架见 [`docs/PHASE0-SKELETON.md`](docs/PHASE0-SKELETON.md)。

## 学习笔记

| 阶段 | 文档 | 主题 |
|------|------|------|
| 0 | [`docs/PHASE0-SKELETON.md`](docs/PHASE0-SKELETON.md) | 环境与工程骨架 |
| 1 | [`docs/PHASE1-LOGIN.md`](docs/PHASE1-LOGIN.md) | 短信登录与 Redis Session |
| 2 | [`docs/PHASE2-CACHE.md`](docs/PHASE2-CACHE.md) | 商户缓存（穿透 / 击穿 / 雪崩） |
| 3 | [`docs/PHASE3-SECKILL.md`](docs/PHASE3-SECKILL.md) | 优惠券秒杀（锁 / Lua / Stream） |
| 4 | [`docs/PHASE4-BLOG-FOLLOW.md`](docs/PHASE4-BLOG-FOLLOW.md) | 探店点赞、关注与 Feed |
| 5 | [`docs/PHASE5-GEO-SIGN.md`](docs/PHASE5-GEO-SIGN.md) | 附近商户 GEO、签到 Bitmap |

`master` 为完整实现；课程实战篇（约 P24）起对应本仓库业务代码。
