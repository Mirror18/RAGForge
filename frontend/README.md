# RAGForge 前端

前端代码统一位于 [`frontend/ragforge-web/`](ragforge-web/)。这里保留 Web SPA 的源码、单元测试、E2E 测试、构建配置和应用级 README；跨应用测试仍放在根目录 [`tests/`](../tests/)。

## 技术边界

- Vue 3 + TypeScript + Vite。
- `src/api.ts` 集中处理 API、Cookie、CSRF、幂等键和结构化错误。
- 前端角色只控制导航和交互，权限边界由后端按 `space_id` 强制执行。
- 不在浏览器存储正文、embedding、secret、raw prompt 或自由文本 citation。

## 启动与验证

在仓库根目录执行：

```powershell
npm --prefix frontend/ragforge-web ci
npm --prefix frontend/ragforge-web run dev
npm --prefix frontend/ragforge-web run format:check
npm --prefix frontend/ragforge-web run test:unit
npm --prefix frontend/ragforge-web run build
```

默认 Web 端口为 `25174`，API 代理目标为 `http://127.0.0.1:25082`；可通过 `VITE_SERVER_TARGET` 覆盖。完整本地闭环优先使用 [`scripts/dev/start-local.bat`](../scripts/dev/start-local.bat)。

详细页面能力和开发约束见 [`ragforge-web/README.md`](ragforge-web/README.md)。
