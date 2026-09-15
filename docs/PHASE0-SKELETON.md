> **课程演进笔记** — 记录 B 站课当时教什么。运行时行为以 [`CURRENT.md`](CURRENT.md) 为准，勿把本文件当第二真相源。

# Phase 0：环境与工程骨架

> 课上目标：把后端 / 前端 / MySQL / Redis 跑通，认识包结构与两条请求链。

## 课程路径（当时在学什么）

1. 启动类 `HmDianPingApplication`、`application.yaml`、`pom.xml`
2. 包：`controller → service/impl → mapper`；`interceptor` / `utils` / Lua
3. 公开接口（如 `/shop-type/list`）vs 登录链（发码 → Token → 双拦截器）
4. 业务域与表的大致对应（用户 / 商户 / 探店 / 券）

日常启停见根目录 [`README.md`](../README.md)。

## 课程当时 vs 本仓库现在

| 点 | 课程当时 | 本仓库现在 |
|----|----------|------------|
| Schema | 手工 `hmdp.sql` | Flyway **V1–V5** + 可选 `hmdp.sql` 种子 |
| 运行时 | 课上常 JDK 8 一把梭 | **JDK 17 运行**，Maven **bytecode Java 8** |
| 中间件 | 本机装 MySQL/Redis | `docker compose` 可用；应用仍宿主机 `mvn` |
| 测试 | 常混 demo / 预热 | 默认 `mvn -B test` **跳过** `@Tag("demo")` |
| 秒杀消费组 | 笔记里常见 `$` | 启动时 `XGROUP … **0-0**`；勿用 `$`（见 [`SECKILL.md`](SECKILL.md)） |
| 真相源 | 本系列 PHASE 笔记 | [`CURRENT.md`](CURRENT.md) + 工程文档 |

## 建议打开

- [`CURRENT.md`](CURRENT.md) — 生产行为索引  
- [`AUTH-BOUNDARIES.md`](AUTH-BOUNDARIES.md) — 公开 / 登录 / 特权路径  
- 下一步：[`PHASE1-LOGIN.md`](PHASE1-LOGIN.md)
