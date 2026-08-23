# Applications

`apps/` 只放可独立构建和部署的进程。业务真相由 server 管理，其他应用不能通过共享数据库绕过它。

| 应用 | 技术 | 职责 |
|---|---|---|
| `server` | Java/Spring Boot | 同步 API、业务规则、检索和对话编排 |
| `ingestion-worker` | Java/Spring Boot | 异步同步、解析、分块、向量和候选索引 |
| `web` | Vue/TypeScript | 角色感知 SPA |
| `ai-runtime` | Python | OCR、rerank 等窄职责内部能力 |

统一运行与构建入口位于 [`scripts/dev/README.md`](../scripts/dev/README.md)：宿主机开发使用 `scripts/dev/start-local.bat`，容器化运行使用 `scripts/dev/core.py --profile app` 和 [`deploy/docker/Dockerfile`](../deploy/docker/Dockerfile)。各应用仍可独立执行测试和源码构建；不要在应用目录内新增与统一 Dockerfile 重复的部署入口。
