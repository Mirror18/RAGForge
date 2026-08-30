# RAGForge Web

计划基线：Vue 3、TypeScript、Vite、组件/状态/测试工具在 Phase 1 锁定精确版本。

当前 SPA 提供本地账号登录/注册、首次知识空间创建，以及业务闭环控制台和三个工作区：

- 业务闭环：注册/登录、创建/切换空间、空间编辑/归档、成员增删改查、Provider/Model Profile/Route/Prompt 发布、云端 MiMo Chat 初始化、Markdown 文件选择与摄取轮询、Parse Report、candidate index 验证/active 发布、带引用回答、citation preview、Run/Step/usage 和增量同步入口。
- 常用本地知识库：可显式选择本地 `notes` 文件夹，仅提交 Markdown 相对路径；`.obsidian`、附件和非 Markdown 文件在浏览器入口过滤，服务端继续做路径与空间校验。不会自动读取本机目录。

- Chunk Studio：按当前 `spaceId` 读取 child projection，展示 provenance、parent-child、anchor、vector/index status 和 override 审计摘要；支持编辑外部已存储内容的 opaque replacement `contentRef` 与 SHA-256 `textHash`、创建 override 以及 `ACTIVE -> NEEDS_REVIEW -> ACTIVE/DISCARDED` 流转。
- Retrieval Playground：提交 query、index version 与 profile A/B candidate，展示 dense、BM25、RRF、rerank、context、evidence 和 abstention 的结构化 trace。
- 带引用问答：创建版本化 Run 与 Answer，消费 SSE，并展示由服务端 provenance 生成的 citation、失败和拒答状态。

页面只渲染契约允许的引用、hash、位置和审计 metadata，不渲染正文、原文、embedding、vector、secret 或自由文本 citation。replacement 字段只指向外部已存储内容，正文不进入本客户端；contentRef 会拒绝空白、超长和敏感字段，textHash 必须为 64 位 SHA-256 十六进制值。A/B 实验不提供 active profile 操作。

真实浏览器闭环证据保存在 [`tests/evidence/business-loop-e2e.v1.json`](../../tests/evidence/business-loop-e2e.v1.json)。Chat 大模型默认优先选择云端 MiMo；云端仍必须由空间显式授权和前端显式选择后使用，不会静默回退到其他云端。Embedding/Rerank 继续按既有 Provider 能力保持本地，避免把 Chat 出境误扩展为全链路出境。

一个 SPA 仍覆盖：

- Viewer：空间、搜索、对话、引用、反馈。来源库、任务中心、问答历史和空间管理页面均提供多数据列表；来源与任务支持跨分页搜索，其他管理列表支持即时关键词过滤。
- Editor：数据源、任务、Parse Report、Chunk Studio、Retrieval Playground。
- Space Admin：成员、空间模型/提示词/出境配置。
- Platform Admin：用户生命周期、Provider、审计、评估和系统健康。平台用户管理仅对 `PLATFORM_ADMIN` 开放；首个管理员需通过受控运维流程授予，普通注册账号不会自动升级。

前端角色只控制导航和交互，不构成安全边界。`src/api.ts` 是当前契约 client 尚未生成时的集中手写 fetch 封装：统一携带 cookie、`X-Correlation-Id`、变更请求的 CSRF 与 `Idempotency-Key`，并将 RFC9457 错误转换为结构化 `ApiError`。请求 body（尤其 queryVector）不写入日志、URL 或浏览器存储。

本地运行：

```bash
npm ci
npm run dev
```

Vite 默认将 `/api` 与 `/actuator` 代理到 `http://127.0.0.1:25082`，可通过 `VITE_SERVER_TARGET` 覆盖。页面启动时读取当前 session 与可见空间；未登录时显示本地账号入口，登录后若没有可见空间则引导创建第一个空间。Session 继续使用后端 HttpOnly Cookie，密码不会写入 URL、日志或浏览器存储。

页面中的日期时间统一使用浏览器时区，并在账号与空间页显示当前 IANA 时区；服务端仍以 UTC 持久化。业务操作建议按“先选空间 → 管理成员/用户 → 配置云端 Chat → 导入并发布索引 → 问答与追踪”顺序执行。
