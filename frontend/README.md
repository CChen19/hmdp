# 黑马点评前端静态资源

来自仓库 `origin/init` 分支的 `nginx-1.18.0/html/hmdp`。

- 页面入口：由 `scripts/nginx-hmdp.conf` 提供（默认 http://127.0.0.1:8080/）
- API 代理：`/api` → `http://127.0.0.1:8081`
- 启动：在项目根执行 `./scripts/start-frontend.sh`
