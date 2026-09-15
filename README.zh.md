# HMDP — 优惠券交易链路的可靠性改造

[English](README.md) | 中文

黑马点评源于[黑马 Redis 课程](https://www.bilibili.com/video/BV1cr4y1671t)（[原参考仓库](https://github.com/cs001020/hmdp)）。本仓库在课程项目的基础上，围绕 **抢券 → 支付 → 超时取消 → 核销** 完善交易链路的可靠性，并实现有明确边界的商户缓存，配套自动化测试和如实说明限制的运维文档。它不是一个完整的本地生活平台。

**受理 ≠ 最终成功。** HTTP 响应中的 `Result.ok(orderId)` 只表示 Redis 已受理本次请求。Stream 消费者将订单写入 MySQL 的 `tb_voucher_order`，才算最终下单成功，对应指标为 `hmdp.seckill.final_success`。不能把受理 QPS 当作订单吞吐量。

## 当前已实现

| 链路 | 行为 |
|------|------|
| 秒杀 | Lua 校验库存和一人一单，写入 Redis Stream，再由消费者将订单写入 MySQL。 |
| 交易 | 支持模拟支付、未支付订单超时取消、通过 Outbox 释放库存，以及商户核销。 |
| 商户浏览 | 缓存未命中时查询 MySQL，并区分空值标记；Redis 或数据库故障时返回不可用，不误报“店铺不存在”。 |
| 认证与权限 | 支持短信验证码登录、Redis 会话，以及 USER / MERCHANT / ADMIN 角色。 |

## 快速开始

**运行环境：** JDK 17（Maven 编译参数 **source/target 为 8**，生成 Java 8 字节码）、Maven、Docker，也可以使用本地 MySQL 8 和 Redis 7。

```bash
docker compose up -d
# 可选：导入示例数据；也可以让 Flyway V1–V5 在空数据库中建表。
mysql -h127.0.0.1 -uroot -p"${MYSQL_PASSWORD:-001020}" < hmdp.sql

mvn -B test
mvn -DskipTests package && ./scripts/start-backend.sh   # API :8081
./scripts/start-frontend.sh                             # 可选前端 :8080
```

| 服务 | 地址 |
|------|------|
| API | `http://127.0.0.1:8081` |
| 前端 | `http://127.0.0.1:8080/`（`/api` → 8081） |
| 健康检查 | `GET /actuator/health` |
| 指标 | `GET /actuator/metrics`、`/actuator/prometheus` |
| 运维快照 | `GET /ops/snapshot`（需要登录且角色为 **ADMIN**） |

登录验证码会出现在后端日志中（`发送验证码成功，验证码：…`）。启动时使用 `XGROUP CREATE stream.orders g1 0-0 MKSTREAM` 创建消费组。**不要**使用 `$` 创建消费组，否则会跳过积压消息。如果已有用 `$` 创建的旧消费组，执行 `XGROUP SETID stream.orders g1 0-0`。

默认配置位于 `application.yaml`，可以通过 `MYSQL_*` / `REDIS_*` 环境变量覆盖。不要将新的密钥或密码提交到仓库。

### Flyway

迁移顺序为：V1 基线 → V2 `uk_user_voucher` → V3 `tb_user.role` → V4 秒杀死信 → V5 交易 Outbox 与审计。`hmdp.sql` 是包含示例数据的完整 SQL 导出文件，可直接阅读。对于已有的本地数据库，配置使用 `spring.flyway.baseline-on-migrate=true`。

默认执行 `mvn test` 不会进行百万 Key 预热或批量插入用户。演示测试需要显式运行 `./scripts/demo-warmup.sh`，或执行 `mvn -Dgroups=demo -Dsurefire.excludedGroups= test`。CI 配置位于 `.github/workflows/ci.yml`，只运行 `mvn -B test`，**不运行压测**。

## Health 不能当作 Kubernetes liveness

`GET /actuator/health` 汇总了 MySQL、Redis 和**秒杀消费门控（consume gate）**的状态。如果只有消费门控关闭，整体健康状态也会是 **DOWN**，但商户浏览仍然可以正常工作。将这个地址用作 Kubernetes **liveness** 探针，会导致仍能正常运行的进程被重启。

- 在本地开发环境中，该接口便于查看状态，可以公开访问，配置为 `show-details: always`。
- **不要**直接将它用作 Kubernetes liveness 探针。
- 如果需要配置探针，liveness 应判断进程是否存活，不应依赖消费门控；面向**秒杀流量**的 readiness 可以包含消费门控状态。
- **不要**将 8081 端口暴露到公网。

## 文档导航

| 文档 | 用途 |
|------|------|
| [`docs/CURRENT.md`](docs/CURRENT.md) | **从这里开始。** 汇总现行工程文档、设计理由和不做的范围。 |
| [`docs/SECKILL.md`](docs/SECKILL.md) | 说明秒杀约定，包括 Lua、活动时间窗、唯一约束，以及 Stream 消费、PEL、死信和结果查询。 |
| [`docs/TRADE-FLOW.md`](docs/TRADE-FLOW.md) | 说明支付、取消、核销和 Outbox 流程。 |
| [`docs/SHOP-CACHE.md`](docs/SHOP-CACHE.md) | 说明商户缓存未命中时如何回源数据库。 |
| [`docs/AUTH-BOUNDARIES.md`](docs/AUTH-BOUNDARIES.md) | 区分公开访问、需要登录和需要特定权限的接口。 |
| [`docs/RECOVERY.md`](docs/RECOVERY.md) | 提供运维恢复步骤和八种场景的故障演练。 |
| [`docs/BENCH.md`](docs/BENCH.md) | 说明压测方法；本地实际运行前，RESULTS 保持 N/A。 |

目录分工如下：`src/` 是后端，`frontend/` 是静态前端，`scripts/` 包含运行脚本和 `scripts/bench/` 压测脚本，`docker-compose.yml` 用于 Compose 环境。

## 课程笔记（仅记录学习历史）

这些文件保留了 B 站课程的历史学习路径，通过“本仓库现在”对照表说明与现行实现的差异。它们记录的是**当时学了什么，不代表当前实现**。了解现行行为和部署运维时，应以 CURRENT / SECKILL / TRADE-FLOW / SHOP-CACHE / AUTH-BOUNDARIES 为准。

| Phase | 文档 | 主题 |
|-------|------|------|
| 0 | [`docs/PHASE0-SKELETON.md`](docs/PHASE0-SKELETON.md) | 环境与工程骨架。 |
| 1 | [`docs/PHASE1-LOGIN.md`](docs/PHASE1-LOGIN.md) | 短信登录与 Redis 会话。 |
| 2 | [`docs/PHASE2-CACHE.md`](docs/PHASE2-CACHE.md) | 商户缓存，以及穿透、击穿和雪崩。 |
| 3 | [`docs/PHASE3-SECKILL.md`](docs/PHASE3-SECKILL.md) | 优惠券秒杀，以及锁、Lua 和 Stream。 |
| 4 | [`docs/PHASE4-BLOG-FOLLOW.md`](docs/PHASE4-BLOG-FOLLOW.md) | 探店、关注与 Feed。这是课程的 Phase 4，不是工程证据或压测阶段。 |
| 5 | [`docs/PHASE5-GEO-SIGN.md`](docs/PHASE5-GEO-SIGN.md) | GEO 与签到 Bitmap，作为背景知识保留。 |

## 压测

`scripts/bench/` 下的脚本用于本地冒烟验证。**不要编造笔记本上的 QPS。** 在自己的机器上实际完成冒烟验证之前，[`docs/BENCH.md`](docs/BENCH.md) 中的 RESULTS 保持 **N/A**。

## 不在本次范围内

本次不升级 Spring Boot，不引入 Kafka / RabbitMQ、Redis Cluster / 分片、Caffeine / Bloom，也不接入真实支付网关或实现钱包。HTTP 受理吞吐量不能作为最终下单吞吐量。
