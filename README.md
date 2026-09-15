# 黑马点评（Redis 实战学习版）

基于 B 站[黑马程序员 Redis 教程](https://www.bilibili.com/video/BV1cr4y1671t) 整理，可本地运行，并附分阶段笔记。原参考仓库：[cs001020/hmdp](https://github.com/cs001020/hmdp)。

| 路径 | 内容 |
|------|------|
| `src/` | Spring Boot 后端 |
| `frontend/` | 静态前端 |
| `docs/` | 分阶段学习笔记 + **current engineering docs** |
| `scripts/` | 启动脚本、nginx、[`scripts/bench/`](scripts/bench/) |
| `docker-compose.yml` | 本地 MySQL 8 + Redis 7（应用仍建议宿主机 `mvn` 启动） |
| `hmdp.sql` | 数据库初始化（含种子数据的完整 dump） |
| `src/main/resources/db/migration/` | Flyway V1–V5 |

**Start here for production behavior:** [`docs/CURRENT.md`](docs/CURRENT.md).

Also: [`TRADE-FLOW.md`](docs/TRADE-FLOW.md) · [`SECKILL-CONSUME.md`](docs/SECKILL-CONSUME.md) · [`SHOP-CACHE.md`](docs/SHOP-CACHE.md) · [`AUTH-BOUNDARIES.md`](docs/AUTH-BOUNDARIES.md) · [`BENCH.md`](docs/BENCH.md) · [`RECOVERY.md`](docs/RECOVERY.md) · [`DESIGN-NOTES.md`](docs/DESIGN-NOTES.md) · [`FAULT-DRILLS.md`](docs/FAULT-DRILLS.md)

## 测试与 CI

默认 `mvn test` **不会**跑用户生成、Redis 预热、百万 HLL 等 demo（`@Tag("demo")`，Surefire 已排除）。

```bash
# 默认安全套件（CI 同款，无需课程 MySQL/Redis 数据）
mvn -B test

# 显式跑 demo / warmup（需要本机 MySQL + Redis）
./scripts/demo-warmup.sh
# 或: mvn -Dgroups=demo -Dsurefire.excludedGroups= test
```

GitHub Actions（`.github/workflows/ci.yml`）：JDK 17，`mvn -B test`，触发 push / PR。**Load benches are not in CI.**

## Flyway

- `V1__baseline.sql`：基线表结构（schema only；V1 不含 `uk_user_voucher` / `tb_user.role`）。
- `V2__uk_user_voucher.sql`：`uk_user_voucher (user_id, voucher_id)`。
- `V3__user_role.sql`：`tb_user.role`（USER/MERCHANT/ADMIN）。
- `V4__seckill_dead_letter.sql`：秒杀死信表。
- `V5__trade_outbox_audit.sql`：取消出箱 + 核销审计。
- `hmdp.sql`：人工可读的全量 dump（含 INSERT）；本地首次装库仍可用 `mysql ... < hmdp.sql`。
- `spring.flyway.baseline-on-migrate=true`：已有本地库会 baseline。空库则 V1 → … → V5。

## 本机运行

前置：**JDK 17** 运行时（Maven **compiler source/target 8**）、Maven、MySQL（库 `hmdp`）、Redis、nginx（可选前端）。

账号密码默认见 `application.yaml`；可用环境变量覆盖：`MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_USER` / `MYSQL_PASSWORD` / `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`（勿把新密钥提交进仓库）。

### Docker Compose（中间件）

```bash
docker compose up -d
# 等待 healthy 后灌种子（任选）
mysql -h127.0.0.1 -uroot -p"${MYSQL_PASSWORD:-001020}" < hmdp.sql
```

应用仍在宿主机：

```bash
mvn -DskipTests package && ./scripts/start-backend.sh
```

| 服务 | 地址 |
|------|------|
| API | `http://127.0.0.1:8081` |
| 页面 | `http://127.0.0.1:8080/`（nginx 托管 `frontend/`，`/api` 反代到 8081） |
| Health | `GET /actuator/health`（含 MySQL、Redis、seckill consume gate） |
| Metrics | `GET /actuator/metrics`、`/actuator/prometheus`（**本地**；勿对公网开放 8081） |
| Ops snapshot | `GET /ops/snapshot`（登录 + **ADMIN**） |

```bash
# 1) 中间件（compose 或本机）
# brew services start mysql redis
mysql -uroot -p'<mysql-password>' < hmdp.sql

# 2) 后端 :8081（改代码后需先 package）
mvn -DskipTests package && ./scripts/start-backend.sh

# 3) 另开终端：前端 :8080
./scripts/start-frontend.sh
```

打开 http://127.0.0.1:8080/ 。登录验证码在后端日志：`发送验证码成功，验证码：xxxxxx`。秒杀消费组由应用启动时创建（`0-0`，见 [`docs/SECKILL-CONSUME.md`](docs/SECKILL-CONSUME.md)）。若仍报 `NOGROUP`，在 Redis 执行：

```text
XGROUP CREATE stream.orders g1 0-0 MKSTREAM
```

**Do not** create the group with `$` — that skips existing stream history. If an old `$` group already exists: `XGROUP SETID stream.orders g1 0-0`.

停止：`lsof -iTCP:8081 -sTCP:LISTEN` 后 `kill <pid>`；nginx 用 `nginx -s stop -p "$(pwd)" -c "$(pwd)/scripts/nginx-hmdp.conf`。

可选：用 IntelliJ 打开仓库根目录，SDK 选 JDK 17 并启用 Lombok，Run `HmDianPingApplication`（勿与脚本抢 8081）。工程骨架见 [`docs/PHASE0-SKELETON.md`](docs/PHASE0-SKELETON.md)。Bench：[`docs/BENCH.md`](docs/BENCH.md)。

## 学习笔记

| 阶段 | 文档 | 主题 |
|------|------|------|
| 0 | [`docs/PHASE0-SKELETON.md`](docs/PHASE0-SKELETON.md) | 环境与工程骨架 |
| 1 | [`docs/PHASE1-LOGIN.md`](docs/PHASE1-LOGIN.md) | 短信登录与 Redis Session |
| 2 | [`docs/PHASE2-CACHE.md`](docs/PHASE2-CACHE.md) | 商户缓存（穿透 / 击穿 / 雪崩） |
| 3 | [`docs/PHASE3-SECKILL.md`](docs/PHASE3-SECKILL.md) | 优惠券秒杀（锁 / Lua / Stream） |
| 4 | [`docs/PHASE4-BLOG-FOLLOW.md`](docs/PHASE4-BLOG-FOLLOW.md) | 探店点赞、关注与 Feed（课程 Phase 4；非本仓库「证据」工程阶段） |
| 5 | [`docs/PHASE5-GEO-SIGN.md`](docs/PHASE5-GEO-SIGN.md) | 附近商户 GEO、签到 Bitmap |

`master` 为完整实现；课程实战篇（约 P24）起对应本仓库业务代码。Current ops index: [`docs/CURRENT.md`](docs/CURRENT.md).
