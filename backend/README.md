# RAGForge 后端

ADR-0014 目标进程为 space / knowledge / answer / gateway。Maven 模块尚未拆分；可运行进程仍是下表。

| 目录 | 职责 |
|---|---|
| [`server/`](./server/) | 同步 API 与 Flyway。包已按业务收拢为 `space` / `knowledge` / `answer` / `shared`，各含 `api` · `application` · `domain` · `infrastructure` |
| [`ingestion-worker/`](./ingestion-worker/) | 异步摄取：连接器、解析、对象存储、消息、流水线 |
| [`ai-runtime/`](./ai-runtime/) | Python rerank（RF-10 将并入业务进程） |
| [`space/`](./space/) [`knowledge/`](./knowledge/) [`answer/`](./answer/) [`gateway/`](./gateway/) [`shared/`](./shared/) | 服务地图 README（目标归属） |

公开 API 字段写在 [`contracts/`](./contracts/)。现行树见 [`当前代码`](<../backlog/docs/architecture/doc-1 - 当前代码.md>)。

## 启动

```powershell
python scripts/dev/core.py up
python scripts/dev/core.py health
mvn -f backend/pom.xml -pl server spring-boot:run
mvn -f backend/pom.xml -pl ingestion-worker spring-boot:run
```

模型只使用空间已授权的 OpenAI 兼容或 MiMo 云端 API。未授权时 fail-closed。
