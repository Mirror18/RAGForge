# Phase 3 版本化摄取流水线 Checklist

状态：`phase3-contract`（2026-08-13）。本清单是 Phase 3 的验收合同；所有条目初始未勾选，不声明连接器、解析器、Worker 或对象存储能力已经实现。

## 一、契约与领域门禁

- [ ] P3-CONTRACT-01 SourceConnector 契约固定 `discover(checkpoint, rules)`、`fetch(sourceRef, expectedVersion)`、`commitCheckpoint(changeSet, result)`，并覆盖 `ADDED`、`MODIFIED`、`MOVED`、`DELETED`、`UNCHANGED`、规范化 `/` 路径、include/exclude 和 credential reference。
  - 验收条件：契约对象、状态枚举、错误语义和敏感字段禁止项可被 contract test 解析；来源只读，凭据不进入 payload、checkpoint、日志或事件。
  - 证据：`contracts/openapi/`、`contracts/events/`、connector contract test、公共 SourceConnector SPI。
  - 验收命令：`python scripts/ci/contract_test.py`；`python -m unittest discover -s tests/contract -p "test_phase3_*.py" -v`。
  - 环境前置：Python 3.12；不需要真实来源或真实凭据。
  - 当前缺口：Phase 3 contract 尚未创建。

- [ ] P3-CONTRACT-02 版本化领域模型覆盖 `DataSource`、`SourceCheckpoint`、`SourceDocument`、`DocumentRevision`、`PipelineVersion`、`IngestionJob`、`JobAttempt`、`PipelineStepExecution`、`Artifact`、`ParseReport`，所有内容实体强制 `space_id`、稳定 ID、版本和 correlation/provenance。
  - 验收条件：数据库约束、跨空间引用约束、状态机和 API projection 可验证；revision/artifact 不原地覆盖。
  - 证据：Flyway migration、repository integration test、schema inspection、API contract。
  - 验收命令：`mvn --batch-mode --no-transfer-progress test`。
  - 环境前置：Java 21、PostgreSQL Testcontainer。
  - 当前缺口：Phase 3 schema 和 API 尚未创建。

- [ ] P3-CONTRACT-03 异步事件和状态机固定 API transaction → Outbox → RabbitMQ relay → Worker consumer 的边界，明确至少一次投递、consumer 幂等、retry/backoff、DLQ 和“不声称 exactly-once”。
  - 验收条件：事件 schema 包含 `spaceId`、job/attempt/step identity、version、correlation/causation；ack 只发生在副作用和状态持久化之后。
  - 证据：event schemas、outbox/relay/consumer tests、RabbitMQ Testcontainer evidence。
  - 验收命令：`python -m unittest discover -s tests/contract -p "test_phase3_*.py" -v`；`mvn --batch-mode --no-transfer-progress test`。
  - 环境前置：RabbitMQ Testcontainer；独立 exchange/queue 名称。
  - 当前缺口：Phase 3 event producer/consumer 尚未实现。

- [ ] P3-CONTRACT-04 checkpoint 只在对应 revision、artifact 和 parse report 成功持久化后推进；parser、object storage、OCR、数据库或消息失败时不推进，也不污染 active data。
  - 验收条件：每一种注入失败都能证明 checkpoint 保持旧值、active pointer 不变、失败状态可观察且可重试/DLQ。
  - 证据：fault-injection integration tests、数据库前后快照、safe evidence JSON。
  - 验收命令：`mvn --batch-mode --no-transfer-progress test`；`python -m unittest discover -s tests/security -p "test_phase3_*.py" -v`。
  - 环境前置：PostgreSQL、RabbitMQ、S3-compatible object storage Testcontainers。
  - 当前缺口：checkpoint/active-data failure path 尚未实现。

- [ ] P3-CONTRACT-05 文件、本地目录和 Git connector 覆盖全量与增量同步，支持新增、修改、移动、删除、未变化、Git commit SHA provenance、只读来源、Windows/Linux canonical path 和 duplicate basename 隔离。
  - 验收条件：同一 fixture manifest 在 Windows 与 Linux 产生相同 logical IDs、content hash 和 change classification；同名不同路径不碰撞。
  - 证据：connector unit tests、Windows acceptance report、Linux CI artifact、Git checkpoint evidence。
  - 验收命令：`python -m unittest discover -s tests/acceptance -p "test_phase3_*.py" -v`；对应 Java integration tests。
  - 环境前置：仅合成 fixture；本地目录使用独立临时根目录；Git 使用本地合成 repository，不访问真实仓库。
  - 当前缺口：三个 connector 尚未实现。

- [ ] P3-CONTRACT-06 解析和 OCR 覆盖 Markdown、TXT、PDF、DOCX、PPTX、XLSX；原生解析优先，低质量或扫描页才触发 OCR；Parse Report 包含 MIME、页数、字符/token、OCR 页、告警、parser/version、耗时和错误。
  - 验收条件：image-only PDF 不得以空文本或伪文本标记成功；OCR 输出必须有来源、页码、引擎版本、触发原因和可审计状态。
  - 证据：合成 parser corpus、Parse Report JSON、OCR acceptance report、依赖/SBOM/Notice 记录。
  - 验收命令：`python -m unittest discover -s tests/acceptance -p "test_phase3_parser_*.py" -v`；Java parser integration tests。
  - 环境前置：固定合成 corpus；真实 OCR runtime 或明确记录的外部环境阻塞。
  - 当前缺口：parser、Parse Report、OCR runtime 和质量门槛尚未实现。

- [ ] P3-CONTRACT-07 上传、对象存储和日志安全固定大小/MIME/扩展名、路径穿越、符号链接逃逸、压缩炸弹/资源上限、space_id object URI、日志/event/DLQ 脱敏规则。
  - 验收条件：跨空间 source/document/revision/artifact/job 访问不泄漏；object key 不可伪造跨空间；任何证据不含真实个人内容、Secret 或完整文档正文。
  - 证据：security tests、object-key contract、secret scan、DLQ redaction fixture、Threat Model review。
  - 验收命令：`python scripts/ci/secret_scan.py`；`python -m unittest discover -s tests/security -p "test_phase3_*.py" -v`。
  - 环境前置：合成恶意 fixture；S3-compatible Testcontainer；不挂载真实 Obsidian vault。
  - 当前缺口：上传/对象/日志安全实现和测试尚未完成。

## 二、ROADMAP 阶段退出条件

- [ ] P3-EXIT-01 初次全量和增量同步在 Windows dev 与 Linux acceptance 行为一致。
  - 量化门槛：同一 manifest 的 logical document ID、content hash、revision identity 和五类 change classification 100% 一致；仅允许显示路径分隔符差异。
  - 证据：Windows 本地报告、GitHub Actions Linux report、fixture manifest hash 和对比脚本。
  - 验收命令：`python -m unittest discover -s tests/acceptance -p "test_phase3_cross_platform_*.py" -v`；GitHub Actions Run URL。
  - 环境前置：Windows 11 本地；Ubuntu GitHub runner；固定 synthetic fixture manifest。
  - 当前缺口：跨平台 fixture 和 acceptance 尚未创建。

- [ ] P3-EXIT-02 中途失败不会推进错误 checkpoint 或污染 active data。
  - 量化门槛：parser、object upload、OCR timeout、DB rollback、消息发布失败各至少 1 个 fault case；所有 case checkpoint 不推进、active pointer 不变、失败可定位。
  - 证据：fault-injection test report、数据库/object snapshot 摘要、失败 trace/correlation。
  - 验收命令：`mvn --batch-mode --no-transfer-progress test`；`python -m unittest discover -s tests/security -p "test_phase3_fault_*.py" -v`。
  - 环境前置：PostgreSQL、RabbitMQ、S3-compatible storage、可控 parser/OCR failure seam。
  - 当前缺口：failure state machine 尚未实现。

- [ ] P3-EXIT-03 消息重投和并发重复消费不产生重复 revision/artifact。
  - 量化门槛：同一 job/step/artifact key 至少执行 20 次 redelivery/concurrent delivery；最终 revision/artifact 唯一计数与一次成功执行相同，且无重复 object side effect。
  - 证据：RabbitMQ redelivery report、唯一约束查询、object manifest、consumer trace。
  - 验收命令：`mvn --batch-mode --no-transfer-progress test`；`python -m unittest discover -s tests/performance -p "test_phase3_*.py" -v`。
  - 环境前置：RabbitMQ Testcontainer；独立 queue/exchange；合成内容。
  - 当前缺口：幂等 consumer、重投演练和性能测试尚未实现。

- [ ] P3-EXIT-04 解析质量样本和 OCR 样本达到预设门槛。
  - 量化门槛：6 类原生格式各至少 1 个 valid fixture，结构/文本断言 100% 通过；Markdown 必须保留 YAML、heading、wikilink、code、table、callout；image-only PDF 至少 2 个样本。真实 OCR 可用时 OCR 样本至少 2/2 成功且 Parse Report 完整、无伪文本；不可用时必须保留未完成状态并记录阻塞，不得勾选本项。
  - 证据：fixture manifest、parser quality summary、OCR report、版本化配置和依赖许可证记录。
  - 验收命令：`python -m unittest discover -s tests/acceptance -p "test_phase3_parser_*.py" -v`。
  - 环境前置：固定 synthetic corpus；OCR runtime 是本项的真实前置。
  - 当前缺口：parser corpus、质量脚本和 OCR 演练尚未完成。

## 三、实现状态约束

- 本清单与 `contracts/openapi/`、`contracts/events/`、`docs/02-architecture/INGESTION_PIPELINE.md` 共同定义 Phase 3 合同；未勾选项不得被解释为运行时能力已上线。
- 任何新增依赖必须先核对精确版本、许可证、传递依赖、SBOM、Notice 和 `docs/07-research/UPSTREAM_REUSE_REGISTER.md`，不得复制第三方源码。
- 所有 source、checkpoint、document、revision、artifact、job、attempt、step、object key、event 和查询必须强制 `space_id`，来源只读，日志和消息不含正文或 Secret。
- 本阶段不把 Web connector、Qdrant retrieval、Chunk Studio、RAG answer 或 citation UI 作为 Phase 3 退出条件；不得用范围扩张替代四项退出条件。
- 本清单只能在对应实现、测试、CI 或演练证据合入 main 后由主 Agent 勾选；不使用文档勾选替代真实验收。
