> **课程学习路径**；现行行为见 [`CURRENT.md`](CURRENT.md)。本文件不是第二真相源。

# Phase 0：环境与工程骨架

## 课程当时讲什么

- 跑通后端 `:8081`、nginx 前端 `:8080`、MySQL、Redis
- 认包结构：`controller → service → mapper`；拦截器与 `utils`
- 两条链：公开接口（如店铺类型）vs 发码 → Token → 双拦截器
- 业务域与表的大致对应（用户 / 商户 / 探店 / 券）

启停步骤见根目录 [`README.md`](../README.md)。

## 本仓库现在

| 主题 | 现行约定 | 文档 |
|------|----------|------|
| Schema | Flyway **V1–V5**；可选 `hmdp.sql` 灌种子 | [`CURRENT.md`](CURRENT.md) |
| JDK | 运行时 **17**；Maven bytecode **Java 8** | README |
| 中间件 | `docker compose` 可起 MySQL/Redis；应用宿主机 `mvn` | README |
| 测试 | 默认 `mvn -B test` 跳过 `@Tag("demo")` | README |
| Stream 组 | 应用启动 `XGROUP CREATE … **0-0** MKSTREAM`（不要用 `$`） | [`SECKILL.md`](SECKILL.md) |
| 鉴权边界 | 公开 / 登录 / 角色特权 | [`AUTH-BOUNDARIES.md`](AUTH-BOUNDARIES.md) |

## 还想对照代码就打开

- `HmDianPingApplication`、`application.yaml`、`pom.xml`
- `MvcConfig`、`docker-compose.yml`、`src/main/resources/db/migration/`
- 下一步：[`PHASE1-LOGIN.md`](PHASE1-LOGIN.md)
