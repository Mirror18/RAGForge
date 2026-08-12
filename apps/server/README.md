# RAGForge Server

计划基线：Java 21、Spring Boot 3.5.x、Spring AI 1.1.x、Maven、Flyway、PostgreSQL。

建议包结构：

```text
com.ragforge
├─ bootstrap
├─ identity
├─ space
├─ source
├─ ingestion
├─ provider
├─ prompt
├─ retrieval
├─ chat
├─ evaluation
├─ audit
└─ shared
```

每个业务模块内部使用 `domain`、`application`、`adapter.in`、`adapter.out`，但不为了目录美观创建空层。模块公共类型保持最小；Spring AI、JPA、Qdrant 或供应商 SDK 类型不穿透到 domain。

入口能力：REST `/api/v1`、SSE、Session/security、Outbox relay、online retrieval/chat、admin API。批量解析不在本进程执行。
