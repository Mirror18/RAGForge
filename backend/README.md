# RAGForge 后端

ADR-0014 目标进程为 space / knowledge / answer / gateway。业务代码已拆为 Maven 库模块；可运行进程仍是下表。

| 目录 | 职责 |
|---|---|
| [`server/`](./server/) | Boot 壳：Flyway、`application.yml`、主类。依赖下方四业务 jar |
| [`shared/`](./shared/) [`space/`](./space/) [`knowledge/`](./knowledge/) [`answer/`](./answer/) | Maven 库模块。包名仍为 `com.ragforge.server.{shared\|space\|knowledge\|answer}`，各含 `api` · `application` · `domain` · `infrastructure`。ArchUnit 禁止跨业务依赖 `*.infrastructure.*Repository` |
| [`ingestion-worker/`](./ingestion-worker/) | 异步摄取：连接器、解析、对象存储、消息、流水线 |
| [`knowledge/ai-runtime/`](./knowledge/ai-runtime/) | 可选 Python rerank（归 knowledge，非独立部署） |
| [`gateway/`](./gateway/) | 服务地图 README（RF-9 接入 Nacos/gateway） |

公开 API 字段写在 [`contracts/`](./contracts/)。现行树见 [`当前代码`](<../backlog/docs/architecture/doc-1 - 当前代码.md>)。

## 启动

```powershell
python scripts/dev/core.py up
python scripts/dev/core.py health
mvn -f backend/pom.xml -pl server -am spring-boot:run
mvn -f backend/pom.xml -pl ingestion-worker spring-boot:run
```

模型只使用空间已授权的 OpenAI 兼容或 MiMo 云端 API。未授权时 fail-closed。
