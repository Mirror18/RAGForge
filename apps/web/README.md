# RAGForge Web

计划基线：Vue 3、TypeScript、Vite、组件/状态/测试工具在 Phase 1 锁定精确版本。

当前 SPA 已提供两个 P4-G 薄客户端工作区：

- Chunk Studio：按当前 `spaceId` 读取 child projection，展示 provenance、parent-child、anchor、vector/index status 和 override 审计摘要；支持编辑外部已存储内容的 opaque replacement `contentRef` 与 SHA-256 `textHash`、创建 override 以及 `ACTIVE -> NEEDS_REVIEW -> ACTIVE/DISCARDED` 流转。
- Retrieval Playground：提交 query、index version 与 profile A/B candidate，展示 dense、BM25、RRF、rerank、context、evidence 和 abstention 的结构化 trace。

页面只渲染契约允许的引用、hash、位置和审计 metadata，不渲染正文、原文、embedding、vector、secret 或自由文本 citation。replacement 字段只指向外部已存储内容，正文不进入本客户端；contentRef 会拒绝空白、超长和敏感字段，textHash 必须为 64 位 SHA-256 十六进制值。A/B 实验不提供 active profile 操作。

一个 SPA 仍覆盖：

- Viewer：空间、搜索、对话、引用、反馈。
- Editor：数据源、任务、Parse Report、Chunk Studio、Retrieval Playground。
- Space Admin：成员、空间模型/提示词/出境配置。
- Platform Admin：用户、Provider、审计、评估和系统健康。

前端角色只控制导航和交互，不构成安全边界。`src/api.ts` 是当前契约 client 尚未生成时的集中手写 fetch 封装：统一携带 cookie、`X-Correlation-Id`、变更请求的 CSRF 与 `Idempotency-Key`，并将 RFC9457 错误转换为结构化 `ApiError`。请求 body（尤其 queryVector）不写入日志、URL 或浏览器存储。

本地运行：

```bash
npm ci
npm run dev
```

Vite 默认将 `/api` 与 `/actuator` 代理到 `http://127.0.0.1:18082`，可通过 `VITE_SERVER_TARGET` 覆盖。页面启动时读取当前 session 与可见空间；需要后端 session cookie 才能操作。
