> **课程演进笔记** — 记录 B 站课当时教什么。运行时行为以 [`CURRENT.md`](CURRENT.md) / [`AUTH-BOUNDARIES.md`](AUTH-BOUNDARIES.md) 为准。

# Phase 1：短信登录与会话（Session → Redis Token）

> 课上目标：验证码、Token、双拦截器、`UserHolder` 拼成登录态。

## 课程路径（当时在学什么）

1. `UserServiceImpl.sendCode` / `login`：验证码进 Redis，会话从 Session 换成 `login:token:{token}` Hash  
2. `RefreshTokenInterceptor`（先恢复并续期）+ `LoginInterceptor`（再强制登录）  
3. `UserHolder` ThreadLocal，请求结束必须 `remove`  
4. 前端 `authorization` 头带 Token  

精读入口：`UserController`、`MvcConfig`、`RedisConstants`（`LOGIN_*`）。

## 课程当时 vs 本仓库现在

| 点 | 课程当时 | 本仓库现在 |
|----|----------|------------|
| 角色 | 基本无角色模型 | `tb_user.role`：**USER / MERCHANT / ADMIN**（Flyway V3） |
| 验证码 | 发码 + 校验即可 | **用后删除**；发码限频；校验失败计数 |
| 登出 | 常是 TODO | **真实 logout**：删 `login:token:{token}` |
| 特权写 | 白名单 / 粗粒度 | `PrivilegeInterceptor` 按 **handler pattern** 拦商户/管理写 |
| 上传 | 课上简版 | 登录 + 类型/大小/路径约束（见 AUTH） |
| 详情 | — | [`AUTH-BOUNDARIES.md`](AUTH-BOUNDARIES.md) |

## 建议打开

- [`AUTH-BOUNDARIES.md`](AUTH-BOUNDARIES.md)  
- [`CURRENT.md`](CURRENT.md)  
- 下一步：[`PHASE2-CACHE.md`](PHASE2-CACHE.md)
