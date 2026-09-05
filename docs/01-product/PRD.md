# RAGForge 产品需求文档

## 1. 产品定位

RAGForge 是供企业内部团队部署的通用知识助手。它把分散文档转换为可追溯、可治理的知识索引，并通过检索增强生成提供带来源的问答。产品重点是知识处理质量、权限隔离、模型可替换性和完整运行证据。

## 2. 核心用户旅程

### 2.1 平台初始化

平台管理员创建本地账号、登记模型提供商和模型能力、执行连通性测试，并查看全局健康状态。

### 2.2 建立知识空间

空间管理员建立空间、分配成员角色、选择本地或云端模型路由，并显式决定该空间是否允许数据发送到云端。

### 2.3 接入和发布知识

编辑者创建文件、目录、Git 或网页数据源。系统增量发现变更，经病毒扫描、解析、规范化、元数据增强、父子分块、向量化、验证后发布一个不可变索引版本。

### 2.4 检查知识质量

编辑者在 Chunk Studio 中核验原文、解析文本、标题层级、父子块、token、元数据、向量状态和错误。人工修订形成 override；源文档变化时 override 标记为 `NEEDS_REVIEW`。

### 2.5 检索与问答

用户在一个空间内提问。系统展示流式回答、精确来源、拒答原因和反馈入口。高级用户可在 Retrieval Playground 查看 dense、BM25、RRF、rerank、过滤器、父块扩展和耗时，并 A/B 比较配置。

### 2.6 审计与运维

授权人员可按一次回答追踪 Run/Step、工具调用、模型调用、提示词/索引/流水线版本、token、成本、错误、取消和重试。

## 3. 功能需求

### 3.1 身份与权限

- 本地账号登录，HttpOnly Session Cookie，CSRF 防护。
- 平台角色与空间成员关系分离。
- 空间角色：Space Admin、Editor、Viewer。
- REST service token 支持哈希存储、scope、过期、吊销和最后使用时间。
- 所有内容数据查询强制带 `space_id`，服务端不信任前端过滤。

### 3.2 知识空间

- 空间生命周期：ACTIVE、ARCHIVED；删除采用受审计的延迟清理。
- 会话绑定一个空间，不允许在一次检索中跨空间拼接。
- 每个空间绑定独立的 chat、embedding、rerank、retrieval 和 prompt profile。

### 3.3 数据源

- 文件上传：Markdown、PDF、DOCX、PPTX、XLSX、TXT 首批支持。
- 本地目录：开发环境只读挂载，支持 include/exclude 规则。
- Git：只读 clone/pull，保存 commit checkpoint；生产首个仓库为 Gitee Obsidian 仓库。
- Web：域名和 URL 白名单、robots/速率/大小/内容类型限制。
- 连接器支持初次全量和后续增量同步，能识别新增、修改、移动和删除。

### 3.4 摄取流水线

- 每一步记录配置/算法版本、输入输出摘要、状态、时间、失败、重试、模型和成本。
- 原始文件存对象存储，关系元数据存 PostgreSQL，向量和检索字段存 Qdrant。
- 文本原生解析优先，OCR 只在扫描页或质量阈值未达标时触发。
- 支持 parser/pipeline/index 版本并行构建与原子切换。
- 失败进入可检查的重试或 DLQ，不允许无痕丢失。

### 3.5 检索和生成

- 混合检索：dense top 30 + BM25 top 30，RRF 融合。
- rerank 默认将前 20 个候选收敛到最多 8 个上下文块。
- child chunk 用于检索，parent chunk 用于上下文补全。
- 支持查询改写、元数据过滤、相邻块/父块扩展、上下文预算管理。
- 来源精确到文档、页码/标题、child chunk 和版本。
- 证据不足、权限不足或工具失败时采用结构化拒答，不伪造来源。

### 3.6 模型和提示词

- Provider Registry 管理端点、鉴权方式、自定义 Header 和健康状态。
- Model Profile 声明 `CHAT`、`EMBEDDING`、`RERANK`、`STREAMING`、`TOOLS`、`JSON_SCHEMA`、`VISION`、`USAGE_REPORTING` 等能力。
- 连接测试覆盖鉴权、chat、stream、tools、embedding 维度、usage 和错误映射。
- 提示词模板不可变版本化，空间绑定只指向已发布版本。
- 仅在空间允许且目标 route 兼容时切换模型；不得从本地静默降级到云端。

### 3.7 对话和 Agent

- SSE 流支持单调序号、`Last-Event-ID` 恢复和取消。
- Run 由可追踪 Steps 组成：rewrite、retrieve、rerank、tool、generate。
- 工具参数经 JSON Schema 校验，执行空间权限和白名单策略。
- 取消后停止上游任务；已输出内容标记 cancelled，不重复记 token/成本。

### 3.8 管理和反馈

- 管理页面：用户、空间、成员、数据源、任务、模型、提示词、评估、审计和系统健康。
- 用户反馈：有用/无用、问题分类、可选说明，不强制保存敏感原文。
- Chunk Studio 与 Retrieval Playground 作为商业级可解释能力，而非开发临时页。

## 4. 非功能需求

- 安全：OWASP 基线、上传安全、SSRF 防护、空间隔离、出境控制和完整审计。
- 可靠性：幂等任务、Outbox、DLQ、指数退避、索引原子切换、可恢复备份。
- 性能：见 [性能计划](../04-quality/PERFORMANCE_PLAN.md)。
- 可观测性：请求、异步 Job、Run、Step、模型和索引使用统一关联 ID。
- 可移植性：核心部署不依赖特定云厂商；云模型通过兼容层接入。
- 可维护性：模块边界由架构测试验证，数据库迁移只能向前演进并附回滚策略。

## 5. MVP 验收场景

1. 管理员建立空间，邀请 Editor 和 Viewer。
2. Editor 接入一个 Git Obsidian 数据源并完成增量同步。
3. 用户提问后得到本地模型回答和可打开的 Markdown 标题级引用。
4. 空间切换到获批云端模型后成功调用；关闭出境权限后调用被服务端拒绝。
5. 删除或更新源文档后，新索引原子发布，旧引用仍可追溯到历史版本。
6. 运行 120 条评估，输出可比较的 retrieval、faithfulness、citation 和 abstention 报告。
7. 人为制造解析失败、RabbitMQ 重投和模型超时，可在控制台与 Trace 中定位并恢复。

## 6. 延后能力

- OIDC、LDAP、SCIM。
- 文档/字段级 ACL 和多租户 SaaS。
- Kubernetes 正式交付和自动扩缩容。
- 可写 Agent、工作流编排市场、插件市场。
- 音视频知识处理、实时协作编辑和跨语言自动翻译。

## 7. 本地 notes 与 MiMo 使用边界（2026-08-23）

- 开发环境可将常用本地 notes 根路径写入 ignored `.env.local`；浏览器必须由用户显式选择文件夹，系统仅接收 Markdown 文件并保留相对路径，不自动读取用户本机任意目录。
- `.obsidian` 目录、附件和非 Markdown 文件不进入摄取请求；服务端拒绝绝对路径、路径遍历和控制字符，所有内容仍按当前 `space_id` 隔离。
- MiMo 作为成熟云端 Chat provider 接入现有 Provider Registry。云端 Chat 仅在空间和本次 Run 明确授权时启用；Embedding/Rerank 可继续绑定本地 Ollama；不得从本地路由静默降级到云端。
- MiMo 凭据只允许通过本地 ignored 配置或生产 Secret 管理注入，不得提交到 Git、CI、证据、日志或前端 bundle。

## 7.1 核心业务闭环增量（2026-08-24）

- 知识来源统一进入当前空间：浏览器可显式选择本地文件夹、上传单个受支持文档，或提交公开网页 URL；服务端统一创建 source version、document revision、artifact 和 ingestion job。
- 网页 URL 必须通过空间云端出境授权、服务端域名白名单、DNS 公网地址、响应大小和媒体类型校验；禁止内网/本机地址，不允许静默切换网络路线。
- 问答以 conversation 为单位保留历史，支持在同一 active conversation 中连续追问；conversation 归档为软状态，历史 run、answer、citation provenance 不删除，归档会话禁止继续写入。
- 前端主流程固定为“选择空间 → 接入知识 → 等待索引 → 选择回答模型 → 提问/追问 → 查看历史 → 归档”，模型选择仍受空间绑定和显式云端授权裁决。

## 7. 外部参考

- [RAGFlow：数据摄取与 Retrieval Test 产品参考](https://github.com/infiniflow/ragflow)
- [AnythingLLM：工作区和本地优先体验参考](https://github.com/Mintplex-Labs/anything-llm)
- [Spring AI：Java AI 应用抽象](https://github.com/spring-projects/spring-ai)

## 8. 知识执行架构演进需求（2026-09-05，Accepted，实施待拆卡）

版本：`knowledge-evolution-requirements.v1`。本节是已接受的架构约束；[ADR-0013](../02-architecture/adr/0013-versioned-knowledge-execution.md)已于 2026-09-05 经项目负责人接受。后续仍须拆实施卡；不修改当前交付承诺、不代表功能已实现。[设计与迁移](../02-architecture/ARCHITECTURE_EVOLUTION.md) 是技术细节唯一入口。

| 需求 ID | 用户价值与拟议变化 | 验收场景（计划，尚未执行） |
|---|---|---|
| RF-ARCH-001 | 管理员能定位并重放摄取的失败阶段；在现有版本化 job/step 上完善可复现输入和受控阶段边界 | 重复投递和 Worker 崩溃恢复不重复发布；配置变更不能污染运行中任务 |
| RF-ARCH-002 | 管理员能检查解析产物和质量拒绝原因，再发布候选索引 | 扫描页、空文本、解析部分失败的合成样本被明确展示或阻断；旧 active 索引在失败后仍可用 |
| RF-ARCH-003 | 用户问答、检索调试和离线评估使用同一检索执行计划与快照 | 固定数据、版本、权限和参数时走同一执行路径；基线/候选对比保留配置与 provenance，不承诺模型输出逐字相同 |
| RF-ARCH-004 | 管理员可区分来源暂停、同步失败、删除和访问收回的影响 | 同步失败不被误判为来源删除；已删除或不再授权内容不可通过历史引用、缓存、重放再次获取 |

范围约束：保持单部署租户、多知识空间和空间级 RBAC；不新增文档 ACL、跨空间检索、通用流程画布、GraphRAG 或自动云端降级。复用既有 Chunk Studio、Retrieval Playground、版本化索引和 Provider 路由能力。
