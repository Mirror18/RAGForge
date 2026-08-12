# RAGForge

RAGForge 是一个面向企业内部、单租户多用户场景的通用 RAG 知识助手学习型工程。仓库的首要目标不是快速堆出一个聊天页面，而是完整实践商业项目从产品定义、架构决策、研发、测试、安全、交付到运维复盘的全过程。

当前阶段为 **Foundation / Phase 1 Evidence Ready**：工程与领域骨架已可在隔离 Compose 中运行；Phase 1 的 Linux CI 取证仍待配置 remote 后完成，RAG 业务纵向能力尚未开始。

## 1. 已确认的产品边界

- 单租户部署，多用户、多个知识空间。
- 空间级 RBAC：Platform Admin、Space Admin、Editor、Viewer。
- 首批数据源：文件上传、本地目录、Git、受控网页抓取。
- 本地模型：Ollama + `qwen3.5:9b`；预留通用 OpenAI-compatible API。
- 每个空间独立决定是否允许内容发送给云端模型，默认仅本地处理。
- RAG 对话绑定单一知识空间，回答必须提供可定位引用；证据不足时明确拒答。
- 只读 Agent 工具：知识检索、文档读取、白名单网页读取。不开放 Shell、SQL 或任意网络访问。
- Linux Docker Compose 为首个交付形态，Ubuntu 24.04 WSL2 为本地验收环境。

## 2. 技术基线

| 层级 | 基线 |
|---|---|
| Web | Vue 3 + TypeScript，单一角色感知 SPA |
| 主系统 | Java 21 + Spring Boot 3.5.x + Spring AI 1.1.x，模块化单体 |
| 异步摄取 | 独立 Java Worker，共享领域契约，不共享运行生命周期 |
| AI Runtime | 小型 Python 服务，仅承载 OCR、rerank 等 Java 生态不擅长的能力 |
| 关系数据 | PostgreSQL |
| 向量检索 | Qdrant |
| 消息 | RabbitMQ + Transactional Outbox |
| 缓存/会话 | Valkey（Redis 协议兼容） |
| 对象存储 | S3-compatible storage |
| 可观测性 | OpenTelemetry + Prometheus + Grafana + Loki + Tempo；Langfuse 可选 |
| 质量评估 | 自有评估数据与指标为准，Promptfoo 用于 CI 矩阵和 red-team |

版本不是永久锁定值。实际开始编码时必须通过 ADR 和兼容性验证确定精确 patch 版本。

## 3. 仓库导航

```text
RAGForge/
├─ apps/                    # 可部署应用：server、worker、web、ai-runtime
├─ libs/                    # 跨应用复用库；须受架构规则约束
├─ contracts/               # OpenAPI、事件 Schema、跨语言契约
├─ config/                  # 非敏感配置样例、提示词和模型配置模板
├─ deploy/                  # Compose 首发部署；Kubernetes 仅预留
├─ docs/                    # 产品、架构、交付、质量、安全、研究和过程记录
├─ fixtures/                # 可公开的测试文档和评估样本
├─ scripts/                 # 开发、CI、运维脚本
├─ tests/                   # 跨应用测试套件
├─ third_party/             # 经审批复制进仓库的第三方源码
└─ licenses/                # 被复用组件要求随附的许可证文本
```

文档入口：

- [项目章程](docs/00-governance/PROJECT_CHARTER.md)
- [仓库许可策略](docs/00-governance/REPOSITORY_LICENSING.md)
- [产品需求文档](docs/01-product/PRD.md)
- [总体架构](docs/02-architecture/ARCHITECTURE.md)
- [交付路线图](docs/03-delivery/ROADMAP.md)
- [Phase 0 执行清单](docs/03-delivery/PHASE_0_CHECKLIST.md)
- [Phase 1 执行清单](docs/03-delivery/PHASE_1_CHECKLIST.md)
- [多 Agent 循环执行提示词](docs/03-delivery/MULTI_AGENT_LOOP_PROMPT.md)
- [测试策略](docs/04-quality/TEST_STRATEGY.md)
- [部署与运行](docs/05-operations/DEPLOYMENT.md)
- [安全基线](docs/06-security-compliance/SECURITY_BASELINE.md)
- [GitHub 成熟项目调研](docs/07-research/GITHUB_BENCHMARK.md)
- [风险登记表](docs/08-records/RISK_REGISTER.md)
- [当前项目状态](docs/08-records/PROJECT_STATUS.md)
- [Phase 1 实施与验收结果](docs/08-records/phase-1/PHASE_1_IMPLEMENTATION_RESULTS.md)

## 4. 当前工作的完成标准

Foundation 阶段完成不等于“目录已经建好”。还需满足：

1. 所有核心需求都能追溯到架构、测试和验收项。
2. 关键选择都有 ADR，状态为 Accepted 或 Superseded。
3. 第三方代码先过许可证闸门，再进入 `third_party/`。
4. 同一批 30–50 份文档、30 个问题完成 RAGFlow 与 AnythingLLM 的基准对比。
5. 本地开发和 CI 所需的密钥全部使用环境变量或 Secret 管理，不进入 Git。

## 5. 开始开发前的顺序

1. 执行 [Phase 0 基准研究](docs/03-delivery/ROADMAP.md#phase-0竞品基准与许可证闸门)。
2. 固化首版 ADR、领域词汇和验收样本。
3. 建立 CI、依赖锁定、SBOM 与秘密扫描。
4. 创建最薄端到端切片：登录 → 建空间 → 导入 Markdown → 检索 → 带引用回答。
5. 每个阶段复盘保存在 `docs/08-records/retrospectives/`；项目稳定后再选择性同步到 Obsidian。

## 6. 许可证与引用

本仓库当前没有复制任何上游源码。采用或改写上游实现前，必须更新：

- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- [上游复用登记表](docs/07-research/UPSTREAM_REUSE_REGISTER.md)
- `licenses/` 中相应许可证文本

参考链接集中在 [REFERENCES.md](docs/07-research/REFERENCES.md)，正文在具体技术结论附近保留直接链接。
