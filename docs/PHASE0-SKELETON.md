# Phase 0：环境与工程骨架

> 环境：后端 `8081` · 前端 nginx `8080` · MySQL 库 `hmdp` · Redis（密码见 `application.yaml`）  
> 布局：后端在仓库根；静态页 `frontend/`；脚本与 nginx 在 `scripts/`（`start-backend.sh` / `start-frontend.sh` / `nginx-hmdp.conf`）。  
> 日常启停见根目录 [`README.md`](../README.md)「本机运行」。

## 1. 启动入口与配置

| 文件 | 作用 |
|------|------|
| `HmDianPingApplication` | Spring Boot 入口；`@MapperScan("com.hmdp.mapper")` 扫描 Mapper |
| `application.yaml` | 端口、MySQL、Redis、日志级别 |
| `pom.xml` | Spring Boot 2.7、Redis、MyBatis-Plus、Redisson、Hutool |
| `hmdp.sql` | 业务表与示例数据 |
| `scripts/*` | 本地启动后端 / nginx |
| `frontend/` | 页面静态资源（来自上游 init） |

**`application.yaml` 学习者速查**

| 配置项 | 含义 |
|--------|------|
| `server.port: 8081` | API 端口；前端经 nginx `/api` 反代到这里 |
| `spring.datasource.*` | MySQL：库 `hmdp`；用户名/密码见配置文件（勿写入公开文档） |
| `spring.redis.*` | Redis 地址/密码见配置文件；Lettuce 连接池 |
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
| `service` + `impl` | **主战场**：缓存、登录、秒杀等都在这 | 接口在 `service` 定义能力，实现类写逻辑（便于注入/替换） |
| `mapper` | MyBatis-Plus 数据访问 | 与表/实体对应；多数 CRUD 走 `BaseMapper`，复杂 SQL 才进 XML |
| `entity` | 表对应实体（`@TableName`） | 和 `hmdp.sql` 对照，贴库结构 |
| `dto` | 实际对外传输的数据类 | 如 `Result`、`LoginFormDTO`、`UserDTO`（不带密码），避免直接丢完整 `User` |
| `interceptor` | 登录校验 + Token 续期 | 见下方请求链 |
| `config` | MVC 拦截器、Redisson、MyBatis、全局异常 | `MvcConfig` 先看 |
| `utils` | Redis Key、缓存工具、锁、ID 生成、`UserHolder` | 后续阶段反复回来 |
| `resources/*.lua` | 秒杀 / 解锁脚本 | Phase 3 |
| `resources/mapper/*.xml` | 少量复杂 SQL（优惠券） | 需要时再看 |

读代码时记住：**Redis 几乎都在 `service.impl` + `utils`**，Controller 一般不直接碰 Redis。

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

## 5. Redis / MySQL / nginx（高层）

- **MySQL**：权威数据（用户、商户、订单、探店正文等），经 Mapper 读写。
- **Redis**：会话、缓存、秒杀库存/一人一单、点赞 ZSet、Feed、GEO、签到 Bitmap、Stream 队列等。
- **Redisson**：分布式锁（`RedissonConfig` → 秒杀兜底锁）。
- **nginx**：静态页 + `/api` → `8081`（配置 `scripts/nginx-hmdp.conf`）；启停步骤见根目录 [`README.md`](../README.md)。
- **master 秒杀**：完整版会消费 `stream.orders`；缺消费组时报 `NOGROUP`，先执行  
  `XGROUP CREATE stream.orders g1 $ MKSTREAM`。

## 6. Phase 0 验收清单

- [ ] 能打开页面：http://127.0.0.1:8080/
- [ ] `curl http://127.0.0.1:8081/shop-type/list` 返回 JSON `success:true`
- [ ] 知道启动类与 `application.yaml` 各自干什么
- [ ] 能画出 `controller → service → mapper → MySQL`，并标出 Redis / 拦截器插在哪一层
- [ ] 能说明：公开接口为何不登录也能访问；`/user/me` 为何会 401
- [ ] 登录走过一遍：发码看日志 → 勾协议 → 登录进首页
- [ ] 会停 8081 / nginx，并用 `lsof` 确认端口

## 7. 下一步：Phase 1（短信登录与会话）

文档：[`PHASE1-LOGIN.md`](PHASE1-LOGIN.md)

精读（先读后改）：

1. `UserServiceImpl`（`sendCode` / `login`）
2. `RefreshTokenInterceptor` + `LoginInterceptor`
3. `MvcConfig`（拦截器顺序与白名单）
4. `UserHolder`、`RedisConstants`（`LOGIN_*`）
5. `dto/UserDTO`、`LoginFormDTO`、`Result`

目标：搞清 **Session → Redis Token**、Hash 存用户、TTL 续期、ThreadLocal 传登录态。
