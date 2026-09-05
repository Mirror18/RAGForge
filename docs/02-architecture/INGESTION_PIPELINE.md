# 版本化知识摄取流水线

## 1. 目标

摄取系统要回答三个问题：某段知识从哪里来、经历了什么处理、当前线上为什么使用这个版本。所有处理都围绕不可变 revision、artifact 和 pipeline version，而不是围绕可被覆盖的临时文件。

## 2. 标准流程

```mermaid
flowchart LR
    D["Discover"] --> F["Fetch"]
    F --> AV["Virus Scan"]
    AV --> P["Parse"]
    P --> N["Normalize"]
    N --> M["Metadata Enrich"]
    M --> C["Parent/Child Chunk"]
    C --> E["Embed Children"]
    E --> I["Write Candidate Index"]
    I --> V["Validate"]
    V --> PUB["Publish Atomically"]
```

每个 Step 至少记录：`step_type`、实现和配置版本、输入/输出 hash、状态、开始/结束、attempt、错误分类、重试、模型、token/成本、artifact URI 和 trace ID。

## 3. SourceConnector 契约

```text
discover(checkpoint, rules) -> SourceChangeSet
fetch(sourceRef, expectedVersion) -> ContentStream + SourceMetadata
commitCheckpoint(changeSet, result) -> NewCheckpoint
```

规则：

- 连接器只读，不修改来源系统。
- checkpoint 只在相应变更成功持久化后推进。
- change set 区分 ADDED、MODIFIED、MOVED、DELETED、UNCHANGED。
- include/exclude 在规范化 `/` 路径上执行，不能依赖 Windows 分隔符。
- 凭据通过 secret reference 获取，不写入任务 payload 或日志。

## 4. Obsidian/Git 首版要求

- 生产通过 Git connector 拉取 Gitee，开发可只读挂载本地仓库。
- 解析 YAML frontmatter、Markdown 标题、代码块、表格、callout 和 wikilink。
- 保留 vault-relative path、heading anchor、wikilink target、create/update metadata。
- `.obsidian/`、附件和用户指定路径遵守 include/exclude；附件只在受支持时单独入库。
- 支持 `ai_index: false` 作为显式排除元数据，但不能把它当作唯一安全规则。
- Git commit SHA 进入 revision provenance；删除在新 index 版本中移除，历史记录仍可审计。

[Spring AI Alibaba Extensions 的 Obsidian reader](https://github.com/spring-ai-alibaba/spring-ai-extensions/tree/main/document-readers/spring-ai-alibaba-starter-document-reader-obsidian)可用于理解基础模型，但现有公开实现不覆盖企业所需的 include/exclude、checkpoint、完整增量同步和 Windows/Linux 路径一致性，因此本项目保留自己的 `ObsidianConnector` 与契约测试。

## 5. 解析和 OCR

优先级：原生结构化解析 → 文本质量检测 → 仅对低质量/扫描页 OCR → 结构恢复。

Parse Report 包含：

- MIME、页数、字符/token 估算。
- 原生提取与 OCR 页数。
- 空页、乱码、重复页眉页脚、表格/图片等告警。
- 标题层级、页码/幻灯片/工作表映射。
- parser name/version、耗时和失败原因。

候选 Java reader 可从 [Spring AI Alibaba Extensions](https://github.com/spring-ai-alibaba/spring-ai-extensions) 的 PDFBox、Tika、POI 和 Markdown 模块评估；采用依赖优先于复制源码。

## 6. 父子分块

- Parent 建议 1000–1500 tokens，保留完整小节语义。
- Child 建议 300–500 tokens，适度 overlap，仅 child embedding 和直接召回。
- child 保存 parent ID、文档位置、标题路径、页码和字符/token 范围。
- 检索命中 child 后按预算扩展 parent 或相邻 child；引用仍落到精确 child。
- 表格、代码、列表和标题边界使用专用策略，不能只按字符硬切。

默认值只是假设，必须由本项目评估集验证。父子策略参考 [RAGFlow child chunking guide](https://github.com/infiniflow/ragflow/blob/main/docs/guides/dataset/configure_child_chunking_strategy.md)。

## 7. 幂等和版本发布

- 内容 hash 相同且 parser/pipeline 配置未变时允许复用 artifact。
- embedding cache key 至少包括 normalized text hash、model profile version 和维度。
- 新 index 写入独立 collection/partition 或严格 index version payload。
- VALIDATING 阶段检查文档数、chunk 数、向量维度、孤儿关系、抽样检索和空间过滤。
- 发布只切换 PostgreSQL 中 active pointer；旧索引至少保留 24 小时并在无引用后清理。
- 任何步骤失败都不能污染 ACTIVE 索引。

## 8. Chunk Studio

必须展示：原文件/版本、解析结果、Parse Report、parent-child 树、token、metadata、vector 状态、引用锚点、override 和错误。人工编辑不改原 artifact，而是创建可审计 override。源 revision 更新后旧 override 转 `NEEDS_REVIEW`。

## 9. 关键指标

- source discovery lag、documents/min、pages/min、chunks/min。
- 各 parser 成功率、OCR 触发率、乱码/空文本率。
- 每 Step 时延、重试、DLQ、队列深度、最老消息年龄。
- embedding token、成本、cache hit 和 provider error。
- published/failed index count、索引大小、孤儿 point count。

## 10. 摄取演进提案（Proposed）

[演进总设计](ARCHITECTURE_EVOLUTION.md)第 1 节区分设计与局部代码事实：所查生产 handler 当前每文档一个 parent，child 按字符上限/换行切分且无 overlap，不能把第 6 节目标当成已经实现。第 4 节拟在现有幂等、candidate validation 与原子发布上补版本化产物清单、质量证据及租约重放约束；不重新发明 SourceConnector 或 Deletion Job。分块行为变更需单独离线评估，[ADR-0013](adr/0013-versioned-knowledge-execution.md)尚未接受。
