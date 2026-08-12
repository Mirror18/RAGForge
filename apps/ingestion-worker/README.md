# RAGForge Ingestion Worker

Java Worker 消费 RabbitMQ 任务，执行 [版本化摄取流水线](../../docs/02-architecture/INGESTION_PIPELINE.md)。它可以依赖共享的 Java contracts 和 ingestion domain policies，但不能直接复用 server Controller 或把 server 数据库 repository 当作公共 API。

计划队列按资源类型区分：普通 parse/index、OCR-heavy、maintenance/rebuild。并发、prefetch、timeout 和 retry 必须有界；消息至少一次投递，处理器幂等。

Worker 只发布候选索引和结果事件，active index 的最终切换由主系统验证并授权。

