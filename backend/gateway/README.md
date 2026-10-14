# gateway

**负责**：浏览器唯一 HTTP 入口，把 `/api/**` 路由到当前上游（默认静态指向 `backend/server` 模块化单体）。

**不负责**：业务规则、权限判定、引用校验。

**当前状态（RF-9 / T006）**：

- 独立 Boot 进程 `ragforge-gateway`，默认端口 `25080`
- 默认上游：`RAGFORGE_GATEWAY_UPSTREAM`（本地 `http://127.0.0.1:25082`，Compose `http://server:8080`）
- Vite / nginx 只代理到 gateway；server 在 Compose 仅 `expose`，不对外映射端口
- Nacos 为 opt-in：构建 `-Pnacos` 且运行 `--spring.profiles.active=nacos`；默认启动不连 Nacos
