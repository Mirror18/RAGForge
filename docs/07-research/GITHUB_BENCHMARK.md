# GitHub 成熟 RAG 项目调研与借鉴计划

> 调研日期：2026-08-12。项目版本、许可证和目录可能变化；真正引入时必须针对精确 release/commit 复核。

## 1. 结论先行

RAGForge 不以 Fork/换皮方式建设。最适合的策略是保留 Java 商业项目架构，把成熟项目拆成四类使用：

1. **正式依赖**：Spring AI、Spring AI Alibaba Extensions。
2. **测试/运行工具**：Promptfoo；Langfuse 可选 OTel/API 集成。
3. **允许许可证下的选择性借鉴**：RAGFlow 算法/测试思想，AnythingLLM 本地优先交互模式。
4. **仅产品/架构参考**：Dify、FastGPT、MaxKB、Open WebUI 等附加许可或 copyleft 项目源码。

## 2. 项目比较

| 项目 | 值得学习 | 许可观察 | RAGForge 使用方式 |
|---|---|---|---|
| [RAGFlow](https://github.com/infiniflow/ragflow) | 深度文档理解、摄取可视化、父子 chunk、Retrieval Test | Apache-2.0（引入时复核精确 commit） | 基准产品；参考/选择性复用算法和测试，登记来源 |
| [AnythingLLM](https://github.com/Mintplex-Labs/anything-llm) | workspace、本地优先、桌面/自托管体验、Provider UX | MIT | 基准产品；可借鉴模式，小片段复用需保留版权 |
| [Spring AI](https://github.com/spring-projects/spring-ai) | Java Chat/Embedding/Vector/Tool/Observability 抽象 | Apache-2.0 | 直接依赖，adapter 隔离框架类型 |
| [Spring AI Alibaba Extensions](https://github.com/spring-ai-alibaba/spring-ai-extensions) | Markdown/PDFBox/Tika/POI/Obsidian/GitHub reader 模块 | Apache-2.0 | 逐模块评估，优先 dependency；自建企业 Connector |
| [Promptfoo](https://github.com/promptfoo/promptfoo) | prompt/model matrix、assertions、red-team、CI | MIT | 开发/CI 工具，结果回写自有 Evaluation Run |
| [Langfuse](https://github.com/langfuse/langfuse) | LLM traces、prompt/evaluation UI | core MIT，仓库部分 EE/商业许可目录 | 仅 OTel/API 可选集成，不复制 EE code |
| [Dify](https://github.com/langgenius/dify) | 模型供应商、工作流、数据集和运营产品设计 | 修改版 Apache，含附加条件 | 仅产品/架构参考，不复制源码 |
| [FastGPT](https://github.com/labring/FastGPT) | 知识库、流程和中文产品体验 | 含 SaaS/品牌等附加条款 | 仅产品/架构参考 |
| [MaxKB](https://github.com/1Panel-dev/MaxKB) | 企业知识库功能覆盖和中文管理体验 | GPL-3.0 | 行为参考，不进入核心源码 |
| [Open WebUI](https://github.com/open-webui/open-webui) | 本地模型聊天和管理体验 | 自定义许可/品牌边界 | UI 行为参考，不复制源码 |

许可证判断只用于项目技术规划，不构成法律意见。

## 3. 已观察到的可借鉴设计

### 3.1 RAGFlow

- parent-child chunking：小 child 用于检索，大 parent 用于补足上下文；RAGForge 保留 child 级精确引用。参见 [child chunking guide](https://github.com/infiniflow/ragflow/blob/main/docs/guides/dataset/configure_child_chunking_strategy.md)。
- Retrieval Test：展示召回片段和参数，启发 RAGForge 的 Retrieval Playground。参见 [retrieval test guide](https://github.com/infiniflow/ragflow/blob/main/docs/guides/dataset/run_retrieval_test.md)。
- 摄取过程对用户可见，启发 Parse Report、Chunk Studio 和 versioned pipeline。

### 3.2 Spring AI Alibaba Extensions

扩展仓库包含 Obsidian、Markdown、PDFBox、Tika、POI 等 reader，可减少基础格式适配工作，参见 [document readers](https://github.com/spring-ai-alibaba/spring-ai-extensions/tree/main/document-readers)。

公开 Obsidian reader 可递归读取 Markdown、解析部分 YAML/wikilink 等，但企业 Connector 还需要 include/exclude、Git checkpoint、完整增删改移、路径规范化、幂等和 provenance。评估时重点查看：

- [ObsidianDocumentReader.java](https://github.com/spring-ai-alibaba/spring-ai-extensions/blob/main/document-readers/spring-ai-alibaba-starter-document-reader-obsidian/src/main/java/com/alibaba/cloud/ai/reader/obsidian/ObsidianDocumentReader.java)
- [ObsidianResource.java](https://github.com/spring-ai-alibaba/spring-ai-extensions/blob/main/document-readers/spring-ai-alibaba-starter-document-reader-obsidian/src/main/java/com/alibaba/cloud/ai/reader/obsidian/ObsidianResource.java)

不要直接把 reader 当成完整企业同步系统；在 Windows/Linux 路径、标题/YAML/wikilink fidelity 上建立自己的契约测试。

### 3.3 Langfuse 与 Promptfoo

- Langfuse 可通过 OpenTelemetry 接入 Spring AI，参见 [官方 integration](https://langfuse.com/integrations/frameworks/spring-ai)。因此它应是可插拔观测消费者，不侵入核心领域。
- Promptfoo 作为 CI 工具运行固定 prompt/model/provider 矩阵和安全测试；RAGForge 保留评估集与最终指标的所有权。

## 4. Phase 0 基准方法

### 4.1 数据集

- 30–50 份公开/合成文档：Markdown/YAML/wikilink、PDF、DOCX、表格、长文、扫描件、重复标题、冲突版本。
- 30 个问题：事实、结构、多跳、无答案、冲突、注入和权限边界。
- 同一原始数据、同一问题和同一人工 relevance 判断用于两个系统。

### 4.2 记录项

| 维度 | 记录 |
|---|---|
| 安装 | OS、Compose、启动时间、服务数、CPU/RAM/VRAM/磁盘 |
| 数据 | 支持格式、增量同步、失败恢复、解析/分块可见性 |
| 检索 | Recall@10、MRR@10、过滤、rerank、可调参数 |
| 回答 | faithfulness、citation、abstention、stream/cancel |
| 治理 | 用户、空间、权限、Provider、出境和审计 |
| 运维 | logs/metrics/traces、backup、upgrade、resource usage |
| 体验 | 导入、排障、引用核验、配置学习成本 |

### 4.3 公平性说明

如果两套产品无法使用完全相同 embedding/reranker，应分别报告“默认最佳实践”和“尽可能相同模型”两组结果。不要用本机 9B 推理吞吐代表产品检索能力；模型生成和 retrieval 指标分开。

## 5. 待填写实验结果

| 指标 | RAGFlow | AnythingLLM | RAGForge 目标/启示 |
|---|---:|---:|---|
| 安装峰值 RAM | TBD | TBD | core profile 可在本机稳定运行 |
| 首次摄取耗时 | TBD | TBD | 按格式拆分 |
| 增量摄取耗时 | TBD | TBD | checkpoint + 幂等 |
| Recall@10 | TBD | TBD | >= 0.90 |
| MRR@10 | TBD | TBD | >= 0.75 |
| Citation precision | TBD | TBD | >= 0.90 |
| Abstention accuracy | TBD | TBD | >= 0.90 |
| 最值得借鉴 | TBD | TBD | 形成具体 ADR/Backlog |

在 Phase 0 实际运行前，不能把 TBD 替换为推测数字。

