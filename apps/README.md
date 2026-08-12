# Applications

`apps/` 只放可独立构建和部署的进程。业务真相由 server 管理，其他应用不能通过共享数据库绕过它。

| 应用 | 技术 | 职责 |
|---|---|---|
| `server` | Java/Spring Boot | 同步 API、业务规则、检索和对话编排 |
| `ingestion-worker` | Java/Spring Boot | 异步同步、解析、分块、向量和候选索引 |
| `web` | Vue/TypeScript | 角色感知 SPA |
| `ai-runtime` | Python | OCR、rerank 等窄职责内部能力 |

真正创建 build files 时，根级 task runner 提供统一的 `dev`、`test`、`lint`、`build`、`compose-up` 入口；各应用仍可独立执行测试。
