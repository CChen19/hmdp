# Phase 0：环境与工程骨架

> 环境已就绪：后端 `8081` · 前端 nginx `8080` · MySQL `hmdp` · Redis 密码 `001020`  
> 前端静态资源：项目内 `frontend/`（来自 `origin/init`）；nginx 配置：`scripts/nginx-hmdp.conf`；启动：`./scripts/start-frontend.sh`

## 1. 启动入口与配置

| 文件 | 作用 |
|------|------|
| `HmDianPingApplication` | Spring Boot 入口；`@MapperScan("com.hmdp.mapper")` 扫描 Mapper |
| `application.yaml` | 端口、MySQL、Redis、日志级别 |
| `pom.xml` | Spring Boot 2.7、Redis、MyBatis-Plus、Redisson、Hutool |
| `hmdp.sql` | 业务表与示例数据 |

**`application.yaml` 学习者速查**

| 配置项 | 含义 |
|--------|------|
| `server.port: 8081` | API 端口；前端经 nginx `/api` 反代到这里 |
| `spring.datasource.*` | MySQL：库名 `hmdp`，账号密码本地已对齐 |
| `spring.redis.*` | Redis 地址/密码；Lettuce 连接池 |
| `mybatis-plus.type-aliases-package` | entity 别名包，XML/注解里可写短类名 |
| `logging.level.com.hmdp: debug` | 业务包 DEBUG（验证码会打在日志里） |

## 2. 包结构地图

```
请求 → controller → service / impl → mapper → MySQL (entity)
                 ↘ interceptor / config
                 ↘ utils + Redis / Redisson / Lua
```

| 包 / 目录 | 职责 | 学习时怎么看 |
|-----------|------|--------------|
| `controller` | HTTP 入口，返回统一 `Result` | 只认路径与入参，业务很薄 |
| `service` + `impl` | **主战场**：缓存、登录、秒杀等都在这 | 按业务域打开对应 `*ServiceImpl` |
| `mapper` | MyBatis-Plus 接口 | 多数 CRUD 无 XML |
| `entity` | 表对应实体（`@TableName`） | 和 `hmdp.sql` 对照 |
| `dto` | 传输对象：`Result`、`LoginFormDTO`、`UserDTO`… | 对外不直接丢完整 `User` |
| `interceptor` | 登录校验 + Token 续期 | 见下方请求链 |
| `config` | MVC 拦截器、Redisson、MyBatis、全局异常 | `MvcConfig` 先看 |
| `utils` | Redis Key、缓存工具、锁、ID 生成、`UserHolder` | 后续阶段反复回来 |
| `resources/*.lua` | 秒杀 / 解锁脚本 | Phase 3 |
| `resources/mapper/*.xml` | 少量复杂 SQL（优惠券） | 需要时再看 |

## 3. 两条请求路径（务必走通）

### A. 公开接口：店铺类型列表

```
浏览器 GET /api/shop-type/list
  → nginx rewrite 为 /shop-type/list → 8081
  → ShopTypeController.queryTypeList()
  → ShopTypeServiceImpl.getTypeList()   # 可能读 Redis List: cache:type
  → ShopTypeMapper / tb_shop_type
  → Result.ok(data)
```

白名单：在 `MvcConfig` 里 `/shop-type/**` **不要求登录**。

### B. 登录相关：发码 → 登录 → 带 Token 访问

```
1) POST /user/code?phone=...
   → UserController → UserServiceImpl.sendCode
   → Redis String: login:code:{phone} + TTL
   → 验证码打在日志：发送验证码成功，验证码：xxxxxx

2) POST /user/login  {phone, code}
   → 校验 Redis 验证码
   → 无用户则 INSERT tb_user
   → Redis Hash: login:token:{token} 存 UserDTO，返回 token
   → 前端 sessionStorage.setItem("token", ...)

3) 之后任意请求（请求头 authorization: token）
   → RefreshTokenInterceptor (order=0)
        从 Redis 取用户 → UserHolder(ThreadLocal) → 续期 TTL
   → LoginInterceptor (order=1)
        UserHolder 无用户则 401（白名单路径除外）
   → Controller / Service
   → afterCompletion: UserHolder.removeUser()
```

白名单含：`/user/code`、`/user/login`、`/blog/hot`、`/shop/**`、`/shop-type/**`、`/upload/**`、`/voucher/**`。

## 4. 业务域 ↔ 表 / 实体

| 业务域 | 主要表 | 实体 | 后续阶段 |
|--------|--------|------|----------|
| 用户与会话 | `tb_user`, `tb_user_info`, `tb_sign` | `User`, `UserInfo` | Phase 1 / 5 签到 |
| 商户 | `tb_shop`, `tb_shop_type` | `Shop`, `ShopType` | Phase 2 / 5 GEO |
| 探店 / 评论 | `tb_blog`, `tb_blog_comments` | `Blog`, `BlogComments` | Phase 4 |
| 关注 | `tb_follow` | `Follow` | Phase 4 |
| 优惠券 / 秒杀 | `tb_voucher`, `tb_seckill_voucher`, `tb_voucher_order` | `Voucher`, `SeckillVoucher`, `VoucherOrder` | Phase 3 |

## 5. Redis 与 MySQL 在骨架中的位置（高层）

- **MySQL**：权威数据（用户、商户、订单、探店正文等），经 Mapper 读写。
- **Redis**：会话、缓存、秒杀库存/一人一单、点赞 ZSet、Feed、GEO、签到 Bitmap、Stream 队列等——**几乎都在 `service.impl` + `utils` 里完成**，Controller 一般不直接碰 Redis。
- **Redisson**：分布式锁（`RedissonConfig` → 秒杀兜底锁）。
- **前端**：静态页只认 `/api/*`；nginx 剥掉 `/api` 前缀转到 8081。

## 6. Phase 0 验收清单

- [ ] 能打开页面：http://127.0.0.1:8080/
- [ ] `curl http://127.0.0.1:8081/shop-type/list` 返回 JSON `success:true`
- [ ] 知道启动类与 `application.yaml` 各自干什么
- [ ] 能画出 `controller → service → mapper → MySQL`，并标出 Redis 插在哪一层
- [ ] 能说明：公开接口为何不登录也能访问；`/user/me` 为何会 401
- [ ] 登录走过一遍：发码看日志 → 勾协议 → 登录进首页

## 7. 下一步：Phase 1（短信登录与会话）

精读（先读后改）：

1. `UserServiceImpl`（`sendCode` / `login`）
2. `RefreshTokenInterceptor` + `LoginInterceptor`
3. `MvcConfig`（拦截器顺序与白名单）
4. `UserHolder`、`RedisConstants`（`LOGIN_*`）
5. `dto/UserDTO`、`LoginFormDTO`、`Result`

目标：搞清 **Session → Redis Token**、Hash 存用户、TTL 续期、ThreadLocal 传登录态。
