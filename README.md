# 黑马点评（Redis 实战学习版）

基于 B 站[黑马程序员 Redis 教程](https://www.bilibili.com/video/BV1cr4y1671t) 实战项目整理，本地可运行，并附分阶段学习笔记。

原参考仓库：[cs001020/hmdp](https://github.com/cs001020/hmdp)（仅供学习参考）。

## 目录结构

| 路径 | 内容 |
|------|------|
| `src/` | Spring Boot 后端 |
| `frontend/` | 静态前端（来自 init 分支资源） |
| `docs/` | 分阶段学习笔记（Phase 0–5） |
| `scripts/` | `start-*.sh` 与 `nginx-hmdp.conf` |
| `hmdp.sql` | 数据库初始化 |

> 前端已放在本仓库 `frontend/`，**不要**再单独建 `hmdp-frontend` 目录。

## 本机运行

前置：JDK 17、Maven、MySQL（库 `hmdp`）、Redis、nginx。不必先装 IDEA——用下面的脚本即可跑通；IDEA 可选，方便 Debug。

账号与密码见 `src/main/resources/application.yaml` 中的 `datasource` / `redis`（**勿把真实生产密码写进文档或提交到公开仓库**）。

| 服务 | 说明 |
|------|------|
| MySQL | 库名 `hmdp`；用户名/密码见 `application.yaml` |
| Redis | `localhost:6379`；密码见 `application.yaml` |
| API | `http://127.0.0.1:8081` |
| 页面 | `http://127.0.0.1:8080/`（nginx 挂 `frontend/`，并把 `/api` 转到 8081） |

### 启动顺序

建议：**MySQL / Redis → 后端 8081 → nginx 8080**。只开页面而后端挂掉时，前端 axios 会对 502/超时弹出「服务器异常」——先用 `curl http://127.0.0.1:8081/shop-type/list` 确认 API。

脚本跑的是已打包的 jar，改代码后需先编译：

```bash
# 1) 中间件
# brew services start mysql redis

# 2) 按需导入库（密码自行替换）
mysql -uroot -p'<mysql-password>' < hmdp.sql

# 3) 打包并起后端（保持该终端不关）
mvn -DskipTests package
./scripts/start-backend.sh    # API :8081

# 4) 另开终端起前端
./scripts/start-frontend.sh   # 页面 :8080，配置 scripts/nginx-hmdp.conf
```

浏览器打开 http://127.0.0.1:8080/ 。登录验证码不会真发短信，在后端日志里找：`发送验证码成功，验证码：xxxxxx`（IDEA 控制台或启动日志）。若登录页协议圆点点不动，确认 `frontend/login.html` 里 radio 的 `id` 与 `label for` 一致后强制刷新。

秒杀相关若启动报 `NOGROUP ... stream.orders`，先在 Redis 执行：

```text
XGROUP CREATE stream.orders g1 $ MKSTREAM
```

### 用 IntelliJ IDEA（可选）

1. `File → Open` → 选**本仓库根目录**（含 `pom.xml` 的那一层）
2. Project / Module SDK 选 **本机 OpenJDK 17**（如 Homebrew `openjdk@17`，或已配置的 `$JAVA_HOME`）；开启 Lombok 注解处理
3. 若 8081 已被 `./scripts/start-backend.sh` 占用，先停掉终端里的后端，再 Run `HmDianPingApplication`（IDEA Run 时由 IDE 编译，不必再手动 `mvn package`）
4. 前端仍用 `./scripts/start-frontend.sh`（或已在跑的 nginx）

Mac 上跳转到定义常用 `⌘ + 单击`；调大代码字体：`Settings → Editor → Font`。Cursor 可继续改代码；IDEA 主要用于 Run/Debug。

### 停止与排查

```bash
# 停后端（8081）
lsof -iTCP:8081 -sTCP:LISTEN
kill <pid>

# 停本项目 nginx（8080）
nginx -s stop -p "$(pwd)" -c "$(pwd)/scripts/nginx-hmdp.conf"
# 或：kill "$(cat logs/nginx.pid)"

# 可选：停 MySQL / Redis
# brew services stop mysql redis
```

确认端口：`lsof -iTCP:8080 -sTCP:LISTEN`、`lsof -iTCP:8081 -sTCP:LISTEN`。  
浏览器里偶发 `CLOSE_WAIT` 无害，可忽略。

### nginx / Redis / MySQL 怎么分工

前后端通过 nginx 汇合：页面由 `scripts/nginx-hmdp.conf` 指向 `frontend/`；接口走前端 `common.js` 的 `baseURL = "/api"`，nginx 剥掉前缀转发到 `8081`。

- **nginx**：静态页 + `/api` 反代（本身不含业务逻辑）
- **Redis**：登录会话、缓存、秒杀、点赞 Feed、GEO、签到等（写在 `service.impl` / `utils`）
- **MySQL**：权威数据，经 `mapper` 读写

分层与包结构见 [`docs/PHASE0-SKELETON.md`](docs/PHASE0-SKELETON.md)。

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
- 上游另有 `init` 分支（含前端资源）；本仓库已把静态页放进 `frontend/`，可直接用 master 对照 + 本地页面。
- 课程视频从实战篇（约 P24）起对应本仓库业务代码。
