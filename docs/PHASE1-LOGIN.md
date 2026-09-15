> **课程学习路径**；现行行为见 [`CURRENT.md`](CURRENT.md) 与 [`AUTH-BOUNDARIES.md`](AUTH-BOUNDARIES.md)。

# Phase 1：短信登录与会话（Session → Redis Token）

## 课程当时讲什么

- 验证码进 Redis；登录态从 Servlet Session 换成 Token + Redis Hash
- `RefreshTokenInterceptor` 恢复并续期；`LoginInterceptor` 强制登录
- `UserHolder`（ThreadLocal）在请求结束必须清理
- 前端用 `authorization` 头带 Token

## 本仓库现在

| 主题 | 现行约定 | 文档 |
|------|----------|------|
| 角色 | `tb_user.role`：USER / MERCHANT / ADMIN | [`AUTH-BOUNDARIES.md`](AUTH-BOUNDARIES.md) |
| 验证码 | 登录成功消费（删除）；发码限频；失败计数 | 同上 |
| 登出 | 删除当前 `login:token:{token}` | 同上 |
| 特权写 | `PrivilegeInterceptor` 按 **handler pattern** | 同上 |
| 上传 | 登录 + 类型/大小/路径校验 | 同上 |

## 还想对照代码就打开

- `UserServiceImpl`、`RefreshTokenInterceptor`、`LoginInterceptor`、`PrivilegeInterceptor`
- `MvcConfig`、`UserHolder`、`RedisConstants`（`LOGIN_*`）
- 下一步：[`PHASE2-CACHE.md`](PHASE2-CACHE.md)
