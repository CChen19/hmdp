# Phase 1：短信登录与会话（Session → Redis Token）

> 前置：Phase 0 环境已通（后端 `8081` · Redis 密码 `001020`）  
> 目标：搞清验证码、Token、双拦截器、`UserHolder` 如何拼成登录态

## 1. 先读这些文件（顺序）

| 顺序 | 文件 | 看什么 |
|------|------|--------|
| 1 | `UserController` | HTTP 入口：`/user/code`、`/user/login`、`/user/me` |
| 2 | `UserServiceImpl` | `sendCode` / `login`（注释里还留着旧 Session 写法） |
| 3 | `RefreshTokenInterceptor` | 有 Token → 从 Redis 取用户 → 放入 ThreadLocal → **续期** |
| 4 | `LoginInterceptor` | ThreadLocal 无用户则 **401** |
| 5 | `MvcConfig` | 拦截器 **order** 与白名单 |
| 6 | `UserHolder`、`RedisConstants`（`LOGIN_*`）、`UserDTO` / `LoginFormDTO` | 细节 |

前端对照：`frontend/login.html`（存 token）、`frontend/js/common.js`（请求头带 `authorization`）。

## 2. 端到端链路

```
浏览器 POST /api/user/code?phone=138xxxx
  → UserController.sendCode
  → UserServiceImpl.sendCode
  → Redis String: login:code:{phone} = 六位数字，TTL 2 分钟
  → 日志：发送验证码成功，验证码：xxxxxx   （本项目不真发短信）

浏览器 POST /api/user/login  {phone, code}
  → 校验 Redis 验证码
  → 无用户则 INSERT tb_user（随机昵称）
  → 生成 token（UUID 无横线）
  → Redis Hash: login:token:{token}  ← UserDTO 字段（id/nickName/icon 全是字符串）
  → TTL 30 分钟，返回 token
  → 前端 sessionStorage.setItem("token", ...)

之后任意请求（头：authorization: token）
  → RefreshTokenInterceptor (order=0)  【拦截所有 /**】
        无 token / Redis 无用户 → 放行（不 set UserHolder）
        有用户 → UserHolder.saveUser → expire 续 30 分钟
  → LoginInterceptor (order=1)         【白名单除外】
        UserHolder 为空 → 401
        有用户 → 放行
  → Controller（如 /user/me 读 UserHolder.getUser()）
  → afterCompletion: UserHolder.removeUser()   // 防线程池脏数据
```

白名单（可不登录）：`/user/code`、`/user/login`、`/blog/hot`、`/shop/**`、`/shop-type/**`、`/upload/**`、`/voucher/**`。

## 3. Session vs Redis：本项目改了什么

| 点 | 旧 Session（注释掉） | 现 Redis Token |
|----|----------------------|----------------|
| 验证码 | `session.setAttribute("code")` | `SET login:code:{phone}` + TTL |
| 登录态 | `session.setAttribute("user")` | `HSET login:token:{token}` + TTL |
| 客户端 | 依赖 Cookie / JSESSIONID | 自管 `authorization` 头 |
| 多实例 | Session 粘滞 / 复制 | 任意节点读同一 Redis |
| 续期 | Session 超时策略 | 每次请求 `EXPIRE` 刷新 |

核心动机：**无状态 Token + 集中式会话**，适合前后端分离与水平扩展。

## 4. Redis Key 速查

| Key | 类型 | TTL | 内容 |
|-----|------|-----|------|
| `login:code:{phone}` | String | 2 min | 6 位验证码 |
| `login:token:{token}` | Hash | 30 min（访问续期） | `id` / `nickName` / `icon` |

注意：Hash 的值被转成 **字符串**（`setFieldValueEditor`），否则 Lettuce 写 Hash 可能类型报错。

## 5. 双拦截器为何拆开

| | RefreshTokenInterceptor | LoginInterceptor |
|--|-------------------------|------------------|
| order | **0（先）** | **1（后）** |
| 路径 | `/**` | 非白名单 |
| 职责 | 有合法 Token 就恢复登录态并续期 | 要求必须已登录 |
| 无 Token 时 | **仍放行**（公开接口需要） | 若无 UserHolder → 401 |

因此：访问 `/shop/1` 即使没登录也能过；访问 `/user/me` 没 Token 会 401。  
若把「取用户」和「强制登录」揉成一个拦截器，白名单逻辑会很难维护。

## 6. ThreadLocal（`UserHolder`）

- 请求线程内：`saveUser` → Controller/Service `getUser` → `removeUser`
- **必须**在 `afterCompletion` 清理，否则 Tomcat 线程复用会串用户
- Service 里签到等接口直接 `UserHolder.getUser().getId()`，无需再传 userId

## 7. 动手验收

```bash
# 1) 发码
curl -X POST 'http://127.0.0.1:8081/user/code?phone=13800138000'
# 看后端日志 或：
redis-cli -a 001020 GET login:code:13800138000

# 2) 登录（把 CODE 换成上一步验证码）
curl -X POST http://127.0.0.1:8081/user/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800138000","code":"CODE"}'
# 响应 data 即 token

# 3) 无 Token → 401
curl -i http://127.0.0.1:8081/user/me

# 4) 带 Token → 用户信息
curl http://127.0.0.1:8081/user/me -H 'authorization: TOKEN'

# 5) 看会话
redis-cli -a 001020 HGETALL login:token:TOKEN
redis-cli -a 001020 TTL login:token:TOKEN
```

浏览器：http://127.0.0.1:8080/login.html → 发码（日志看验证码）→ 勾协议 → 登录进首页。

## 8. 验收清单

- [ ] 能说明：为何不用 Session、Token 存哪、请求头叫什么
- [ ] 画出双拦截器顺序，解释「公开接口为何不登录也能访问」
- [ ] 知道 `UserDTO` 为何不含手机号/密码等敏感字段
- [ ] 演示：发码 → Redis 有码 → 登录拿 Token → `/user/me` 成功；无 Token 401
- [ ] 知道 `UserHolder.removeUser()` 防什么问题
- [ ] （加分）想清楚 logout 该删哪个 Redis Key（代码里 TODO 未做）

## 9. 下一步：Phase 2（商户缓存）

精读：`ShopServiceImpl`、`CacheClient`、`ShopTypeServiceImpl`  
主题：缓存穿透 / 击穿 / 雪崩。
