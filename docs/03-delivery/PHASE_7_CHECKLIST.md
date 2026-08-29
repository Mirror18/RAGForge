# Phase 7 Checklist：实现对齐与 Linux 交付

- 状态：`implementation-reconciliation`
- 审计日期：2026-08-29
- 代码基线：`f6b016840e946ea314cdaf4812c196dcea8ca491`
- 审计方法：直接检查 production code、OpenAPI、迁移、测试、Dockerfile/Compose，并运行当前可用门禁；不采信历史完成声明作为实现证据
- 执行计划：[`PHASE_7_EXECUTION_PLAN.md`](../08-records/phase-7/PHASE_7_EXECUTION_PLAN.md)

## 1. 已由代码确认的能力

- Session 注册/登录、空间成员与 `space_id` 授权、用户/空间软停用或归档已有 Controller、Service、迁移和测试代码。
- 文件/网页上传可创建 revision/artifact/job；Worker 已接入 RabbitMQ、解析、对象存储、embedding、Qdrant candidate index，且 candidate 按空间汇总 active 文档。
- 问答链已接入 revision/artifact material、结构化 citation/provenance、Provider 原生 token streaming、持久化 answer/run/event、SSE replay 和同/多实例上游取消。
- Server/Worker Docker target 使用 UID 10001；Web、Server、Worker 已有 Compose `app` profile 构建定义，但本轮未完成 runtime build/up 验证。
- 2026-08-29 当前增量实测：format、architecture、Markdown links、secret scan、52 contract tests、Web 类型检查/构建、真实 streaming 定向 Java 63/63，以及事件持久化/多实例 fan-out 6/6 通过。

这些事实不证明完整用户旅程、真实模型性能、Linux runtime 或发布门槛已经通过。

## 2. P0：先修复会阻断 MVP 的实现断点

- [x] P7-CORE-01 平台管理员 bootstrap：干净数据库可从登录页使用显式配置的 32 字符以上一次性 Token 创建或提升首个 ACTIVE `PLATFORM_ADMIN`；数据库事务锁保证并发请求最多成功一次，完成后 fail-closed，普通注册用户不会自动提权，审计不记录邮箱、密码或 Token。
- [x] P7-CORE-02 Provider 权限、验证与发布闸门：保持现有 space-scoped 数据模型；只有同时属于目标空间的平台管理员可登记和实测 connection，空间管理员负责 Profile/Route，Editor 不再可写。测试通过现有 adapter 发送固定合成样本，云端逐次显式确认，结果只保存分类、能力、维度、耗时和 ID；最新匹配测试失败、缺失或声明/实测不一致时 Profile 不得 `PUBLISHED`。RERANK adapter 尚未实现时测试明确失败，不伪造 verified capability。
- [x] P7-CORE-03 真实生成 streaming：Ollama NDJSON 与 OpenAI-compatible/MiMo SSE 由 production adapter 实时消费；只把增量解码后的根级 `answer_text` 投影为 durable `answer.delta`，完整结构、claim 与 citation allow-list 仍在终态校验。取消通过同进程 token 和多实例 run-event fan-out 关闭上游流，取消后 event store 拒绝新增 delta；`Last-Event-ID` 继续从持久事件恢复。失败/拒答/取消时 Web 清除未通过终态校验的暂态文本。
- [x] P7-CORE-04 Git 数据源接线：Server 已提供空间隔离的来源配置、全量/增量手动同步与可选定时同步；Worker 通过独立 `source.sync.requested.v1` 事件执行只读 clone/discover，持久化 Git 对象 checkpoint，并把 ADD/MODIFY/MOVE 转换为普通文档摄取任务，DELETE 归档文档并移除 active pointer；Web 已提供配置和同步入口。
- [ ] P7-CORE-05 检索执行语义：BM25 当前为 `InMemoryBm25CandidateStore`，重启后丢失；RERANK route 虽被绑定，但 production retrieval 使用 `LexicalReranker`，AI Runtime 仍只有包骨架。选择并实现 durable lexical 重建/存储与真实 rerank adapter，或用 ADR/产品变更移除虚假的 route 能力。
- [ ] P7-CORE-06 管理闭环：补齐用户反馈 API/UI、Provider/依赖健康聚合、按权限查询的审计/成本视图。当前只有 raw actuator 链接和内部 `Phase6OperationsService`，不等于 PRD 中的管理页面。
- [x] P7-WEB-01 协作闭环：空间管理员可按已知注册邮箱精确添加 ACTIVE 用户并选择初始角色；不暴露平台用户目录。Editor/Viewer 首次进入、非管理员拒绝、重复成员、停用用户、角色变更和最后管理员保护已有单元/集成回归；Web typecheck/build 通过。
- [ ] P7-WEB-02 来源与任务闭环：建立来源库和任务中心；多文件逐项显示提交与终态，支持分页、筛选、失败重试/重放、重新同步、删除/归档和错误详情，不能再用 `slice(0, 5)` 代替任务管理。
- [ ] P7-WEB-03 索引生命周期：显示 candidate 的构建/验证依据，支持发布、查看 active、回滚上一版本和处理 retired 版本；页面文案不得把 candidate 描述为 active。
- [ ] P7-WEB-04 可核验问答：实现明确的新会话入口、历史 answer/citation 恢复、可阅读的来源预览或受权原文跳转、反馈、会话重命名/归档；真实 token streaming 完成前移除“回答增量”误导描述。
- [ ] P7-WEB-05 上下文工具：从来源/Revision/Chunk、检索命中和 Citation 直接进入 Chunk Studio/Playground；普通路径不要求手填 `childChunkId`、`contentRef`、hash、index/profile UUID/version，生产 UI 不暴露 synthetic `queryVector`。
- [ ] P7-WEB-06 配置与运维生命周期：Provider/Profile/Route/Prompt 提供测试、编辑、停用/退役、版本查看与回滚；Run 支持列表、筛选和 correlation ID 搜索；审计/成本/保留和聚合健康具备受权页面。

## 3. P1：建立可信的开发与回归基线

- [ ] P7-TEST-01 统一 preflight：检查 Maven 实际 JVM、Node/npm 和 Docker daemon，而不是只检查命令是否存在。初始审计曾出现 Maven 绑定 JDK 8、Node/npm 不在 PATH、Docker daemon 不可用；当前会话已通过显式 JDK 21、Node/npm 和 Docker/Testcontainers 执行定向门禁，但仓库仍没有自动发现这类漂移的统一 preflight。
- [ ] P7-TEST-02 全量 JVM 回归：在 JDK 21 + 可用 Docker 上运行 Server/Worker 全量测试。当前已补跑 streaming 相关 Server 定向 63/63 和事件持久化/多实例 Testcontainers 6/6；尚未在当前候选运行 Server + Worker 全 reactor，因此不能记录为全量全绿。
- [ ] P7-TEST-03 Web 自动化：`apps/web/package.json` 只有 typecheck/build/dev，没有 unit/component/E2E test 脚本；为登录、首次设置、上传/轮询、索引发布、问答/取消、历史/归档、管理权限和跨空间拒绝建立自动化。
- [ ] P7-TEST-04 契约-实现一致性：增加 Controller/OpenAPI operation 对照门禁，至少捕获 provider connection test 这类“契约存在、实现缺失”；反向检查生产端点是否遗漏契约。
- [ ] P7-TEST-05 RAG 变更评估：最近 retrieval/answer 相关性逻辑变化必须重新生成 baseline/candidate、引用/拒答/隔离/注入、latency/token/cost 证据；Phase 6 的人工评审豁免不能自动覆盖新发布候选。
- [ ] P7-TEST-06 Web 导航与数据规模：引入 URL Router/可恢复页面上下文；所有 cursor API 提供分页或增量加载，并用超过 100 个资源、超过 5 个任务/索引的 fixture 验证，不允许静默截断。

## 4. P2：产品闭环后执行 Linux 交付

- [ ] P7-DEPLOY-01 容器加固：Web 改为非 root；为 Server/Worker 增加 Compose health/readiness；三类应用统一 capability、只读文件系统/受控写路径、资源限额、优雅关闭和日志验证。
- [ ] P7-SUPPLY-01 发布镜像：基础与应用镜像固定 immutable digest；使用目标镜像生成 SBOM/Grype 结果；生产 Secret 不使用 Compose 默认占位值，也不进入展开配置、镜像或日志。
- [ ] P7-DEPLOY-02 干净 Ubuntu 24.04：从文档构建并启动 core + app，以公共合成 fixture 完成平台初始化、Provider test、空间/成员、Git/文件摄取、active index、streaming 引用问答、反馈、审计及跨空间/未授权出境拒绝。
- [ ] P7-OBS-01 叠加 observability profile，验证 Dashboard、trace/log 脱敏、告警和 Runbook 可定位规定故障。
- [ ] P7-UPGRADE-01 从上一兼容基线升级并在兼容矩阵允许范围内回滚；验证 PostgreSQL、对象、Qdrant、BM25/rebuild 和 citation 一致性，只使用合成数据。
- [ ] P7-PUBLIC-01 执行 secret、个人信息、Obsidian 内容、生产数据、raw prompt、许可证、Notice、历史和大文件检查。根许可证、release 版本和生产迁移仍需用户单独批准。

## 5. 退出条件

P0/P1 未完成前暂停部署验收，不得把任务重心收缩为“只做部署”。实用性验收要求普通用户不借助数据库 seed、API 客户端或手填内部 UUID/hash 完成首次设置、协作、来源维护、失败恢复、索引生命周期和可核验问答。全部任务必须有同一候选 SHA 的代码、测试和 runtime 证据；创建 release、接受根级许可证、执行生产迁移不在本清单的自动授权范围内。
