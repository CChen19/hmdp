# 黑马点评前端静态资源

来自上游 `init` 分支的 `nginx-1.18.0/html/hmdp`，已放在本仓库 `frontend/`（与后端同项目）。

- 页面：http://127.0.0.1:8080/
- nginx 配置：`scripts/nginx-hmdp.conf`（`/api` → `http://127.0.0.1:8081`）
- 启动：项目根执行 `./scripts/start-frontend.sh`
- 启停与账号说明见根目录 [`README.md`](../README.md)

**登录页协议圆圈点不了**：自定义样式依赖 `input#readed` 与 `label for="readed"` 对齐（见 `login.html` / `login2.html`）；改完后请强制刷新。  
**「服务器异常」**：多为后端 `8081` 未启动导致 nginx 502；先确认 API 可用。
