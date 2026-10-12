# RAGForge 后端

现行进程如下。ADR-0014 的 space / knowledge / answer / gateway 还没有独立目录，新代码写进下面两个 Java 进程。

| 目录 | 职责 |
|---|---|
| [`server/`](./server/) | 同步 API 与 Flyway。包：`identity`、`space`、`provider`、`ingestion`、`chunk`、`index`、`retrieval`、`answer`、`run` 等 |
| [`ingestion-worker/`](./ingestion-worker/) | 异步摄取：连接器、解析、对象存储、消息、流水线 |
| [`ai-runtime/`](./ai-runtime/) | Python rerank |

公开 API 的字段和路径写在 [`contracts/`](./contracts/)。目录说明见 [`当前代码`](<../backlog/docs/architecture/doc-1 - 当前代码.md>)。

## 启动

```powershell
python scripts/dev/core.py up
python scripts/dev/core.py health
mvn -f backend/pom.xml -pl server spring-boot:run
mvn -f backend/pom.xml -pl ingestion-worker spring-boot:run
```

一键启动：[`scripts/dev/start-local.bat`](../scripts/dev/start-local.bat)。凭据放仓库根目录的 `.env.local`，不要进 Git。

模型只使用空间已授权的 OpenAI 兼容或 MiMo 云端 API。未授权时 fail-closed。
