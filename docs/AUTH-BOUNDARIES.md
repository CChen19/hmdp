# Auth boundaries (Phase 1)

## Path classes

| Class | Who | Examples |
|-------|-----|----------|
| Public | Anonymous OK | `POST /user/code`, `POST /user/login`, `GET /blog/hot`, `GET /shop-type/**`, `GET /shop/**`, `GET /voucher/list/**`, `GET /actuator/health`, `GET /actuator/info`, `GET /actuator/metrics/**`, `GET /actuator/prometheus` |
| Login required | Any authenticated user | upload, logout, me, sign, blog write/like, follow, seckill order, other writes |
| Privileged write | `MERCHANT` or `ADMIN` | `POST/PUT /shop`, `POST /voucher`, `POST /voucher/seckill`, `POST /voucher-order/{id}/redeem` |
| Admin only | `ADMIN` | `GET /ops/snapshot` |

Actuator metrics/prometheus are treated as **local-only** (same port 8081 — do not publish publicly). See [`CURRENT.md`](CURRENT.md).

Responses: **401** if anonymous on a protected path; **403** if logged in but not privileged.

Interceptors (order): `RefreshTokenInterceptor` → `LoginInterceptor` → `PrivilegeInterceptor`.

## Role model

- Column `tb_user.role`: `USER` (default) / `MERCHANT` / `ADMIN`.
- Existing rows without a role are treated as `USER` (DB default + `UserRole.normalize`).
- **No `owner_id` on shop in Phase 1.** Privileged writes are role-gated only; any MERCHANT/ADMIN may mutate any shop/voucher. Owner scoping is deferred.

Existing DB upgrade:

```sql
ALTER TABLE tb_user
  ADD COLUMN role varchar(16) NOT NULL DEFAULT 'USER'
  COMMENT '角色：USER/MERCHANT/ADMIN' AFTER icon;
```

## Upload

- Requires login.
- Max size 2 MiB; `Content-Type` must be `image/*`; magic-bytes check (JPEG/PNG/GIF/WEBP).
- Delete accepts only app-relative names `/blogs/{d1}/{d2}/{uuid}.ext` under `IMAGE_UPLOAD_DIR`; rejects `..`, absolute paths; verifies canonical path stays under the upload root.

## Captcha / logout

- Successful login **deletes** `login:code:{phone}` (one-time use).
- Send limit: 1 / minute / phone (`login:code:limit:{phone}`).
- Verify failures: 5 / 10 minutes / phone (`login:code:fail:{phone}`).
- Logout deletes `login:token:{token}` for the current `authorization` header.
