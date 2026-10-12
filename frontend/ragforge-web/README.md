# RAGForge Web

计划基线：Vue 3、TypeScript、Vite、组件/状态/测试工具在 Phase 1 锁定精确版本。

当前 SPA 提供本地账号登录/注册、首次知识空间创建，以及业务闭环控制台和三个工作区：

## 面向业务用户的使用顺序

默认导航按业务闭环排列：空间 → 云端模型 → 资料与索引 → 带引用问答。内容证据和检索实验从引用或索引进入；运行与管理承担健康、成本和审计。视图文件在 `src/views/`。

问答页的历史会话使用表格集中展示，每页 10 条；点击会话会载入最近一次回答，点击运行记录可切换到具体回答，运行记录也按每页 10 条收拢；归档会话可以查看但不能继续提问。业务向导中的导入任务同样按每页 10 条展示。页面布局见 [`当前代码`](<../../backlog/docs/architecture/doc-1 - 当前代码.md>)。既有截图在 `tests/evidence/business-demo/`。

- 业务闭环：注册/登录、创建/切换空间、空间编辑/归档、成员增删改查、Provider/Model Profile/Route/Prompt 发布、云端 MiMo Chat 初始化、Markdown 文件选择与摄取轮询、Parse Report、candidate index 验证/active 发布、带引用回答、citation preview、Run/Step/usage 和增量同步入口。
- 常用本地知识库：可显式选择本地 `notes` 文件夹，仅提交 Markdown 相对路径；`.obsidian`、附件和非 Markdown 文件在浏览器入口过滤，服务端继续做路径与空间校验。不会自动读取本机目录。

- Chunk Studio：按当前 `spaceId` 读取 child projection，展示 provenance、parent-child、anchor、vector/index status 和 override 审计摘要；支持编辑外部已存储内容的 opaque replacement `contentRef` 与 SHA-256 `textHash`、创建 override 以及 `ACTIVE -> NEEDS_REVIEW -> ACTIVE/DISCARDED` 流转。
- Retrieval Playground：提交 query、index version 与 profile A/B candidate，展示 dense、BM25、RRF、rerank、context、evidence 和 abstention 的结构化 trace。
- 带引用问答：创建版本化 Run 与 Answer，消费 SSE，并展示由服务端 provenance 生成的 citation、失败和拒答状态。
- 索引生命周期：以数据列表展示构建、验证、发布、回滚和退役版本，每页 10 条；当前版本与上一版本指针保留在列表上方。

页面只渲染契约允许的引用、hash、位置和审计 metadata，不渲染正文、原文、embedding、vector、secret 或自由文本 citation。replacement 字段只指向外部已存储内容，正文不进入本客户端；contentRef 会拒绝空白、超长和敏感字段，textHash 必须为 64 位 SHA-256 十六进制值。A/B 实验不提供 active profile 操作。

真实浏览器闭环证据保存在 [`tests/evidence/business-loop-e2e.v1.json`](../../tests/evidence/business-loop-e2e.v1.json)。Chat 与 Embedding 都只走空间已授权的 OpenAI 兼容云端 API；未授权则失败，没有本地 Ollama 可退。

一个 SPA 仍覆盖：

- Viewer：空间、搜索、对话、引用、反馈。来源库、任务中心、问答历史和空间管理页面均提供多数据列表；来源与任务使用服务端 cursor 分页与 `q` 搜索，页面默认每页 10 条，其他管理列表支持即时关键词过滤。
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
