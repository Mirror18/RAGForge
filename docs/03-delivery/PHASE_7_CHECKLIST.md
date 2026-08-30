# Phase 7 Checklist：实现对齐与 Linux 交付

- 状态：`p2-ready`
- 最近复核：2026-08-30
- 功能候选基线：`f695936594834f8a870fa95dca5ff0c6634441a1`
- 审计方法：直接检查 production code、OpenAPI、迁移、测试、Dockerfile/Compose，并运行当前可用门禁；不采信历史完成声明作为实现证据
- 执行计划：[`PHASE_7_EXECUTION_PLAN.md`](../08-records/phase-7/PHASE_7_EXECUTION_PLAN.md)

## 1. 已由代码确认的能力

- Session 注册/登录、空间成员与 `space_id` 授权、用户/空间软停用或归档已有 Controller、Service、迁移和测试代码。
- 文件/网页上传可创建 revision/artifact/job；Worker 已接入 RabbitMQ、解析、对象存储、embedding、Qdrant candidate index，且 candidate 按空间汇总 active 文档。
- 问答链已接入 revision/artifact material、结构化 citation/provenance、Provider 原生 token streaming、持久化 answer/run/event、SSE replay 和同/多实例上游取消。
- Server/Worker Docker target 使用 UID 10001；Web、Server、Worker 已有 Compose `app` profile 构建定义，但本轮未完成 runtime build/up 验证。
- 2026-08-30 当前候选实测：统一 preflight 6/6、Maven reactor 307 tests（306 passed、0 failed、0 errors、1 skipped）、contract 52/52、Controller/OpenAPI 102/102、Web Vitest 10/10、Playwright 10/10、RAG 128-case gate、format、architecture、Markdown links、secret scan、dependency inventory 与 Compose static validation 全部通过。

这些事实不证明完整用户旅程、真实模型性能、Linux runtime 或发布门槛已经通过。

## 2. P0：先修复会阻断 MVP 的实现断点

- [x] P7-CORE-01 平台管理员 bootstrap：干净数据库可从登录页使用显式配置的 32 字符以上一次性 Token 创建或提升首个 ACTIVE `PLATFORM_ADMIN`；数据库事务锁保证并发请求最多成功一次，完成后 fail-closed，普通注册用户不会自动提权，审计不记录邮箱、密码或 Token。
- [x] P7-CORE-02 Provider 权限、验证与发布闸门：保持现有 space-scoped 数据模型；只有同时属于目标空间的平台管理员可登记和实测 connection，空间管理员负责 Profile/Route，Editor 不再可写。测试通过现有 adapter 发送固定合成样本，云端逐次显式确认，结果只保存分类、能力、维度、耗时和 ID；最新匹配测试失败、缺失或声明/实测不一致时 Profile 不得 `PUBLISHED`。RERANK adapter 尚未实现时测试明确失败，不伪造 verified capability。
- [x] P7-CORE-03 真实生成 streaming：Ollama NDJSON 与 OpenAI-compatible/MiMo SSE 由 production adapter 实时消费；只把增量解码后的根级 `answer_text` 投影为 durable `answer.delta`，完整结构、claim 与 citation allow-list 仍在终态校验。取消通过同进程 token 和多实例 run-event fan-out 关闭上游流，取消后 event store 拒绝新增 delta；`Last-Event-ID` 继续从持久事件恢复。失败/拒答/取消时 Web 清除未通过终态校验的暂态文本。
- [x] P7-CORE-04 Git 数据源接线：Server 已提供空间隔离的来源配置、全量/增量手动同步与可选定时同步；Worker 通过独立 `source.sync.requested.v1` 事件执行只读 clone/discover，持久化 Git 对象 checkpoint，并把 ADD/MODIFY/MOVE 转换为普通文档摄取任务，DELETE 归档文档并移除 active pointer；Web 已提供配置和同步入口。
- [x] P7-CORE-05 检索执行语义：ADR-0012 已接受；Qdrant candidate payload 持久化 searchable text，`DurableBm25CandidateStore` 按 `space_id/index_version_id` 在重启后重建。production `ProviderReranker` 只调用当前空间已发布的 LOCAL_ONLY AI Runtime RERANK route，失败与未支持能力均 fail-closed；test profile adapter 冲突已由 P7C-05R 修复。
- [x] P7-CORE-06 管理闭环：已提供用户反馈聚合、Provider/依赖健康、按权限和 `space_id` 查询的审计/成本投影及 Control Center 页面；普通用户不可访问，导出保持脱敏。
- [x] P7-WEB-01 协作闭环：空间管理员可按已知注册邮箱精确添加 ACTIVE 用户并选择初始角色；不暴露平台用户目录。Editor/Viewer 首次进入、非管理员拒绝、重复成员、停用用户、角色变更和最后管理员保护已有单元/集成回归；Web typecheck/build 通过。
- [x] P7-WEB-02 来源与任务闭环：来源库、Git 同步、逐项终态、cursor 分页、筛选、失败恢复、重新同步与归档入口已完成；规模 fixture 覆盖 120 sources、7 jobs、6 indexes。
- [x] P7-WEB-03 索引生命周期：candidate/active/retired 状态、验证依据、发布、回滚和退役入口已完成。
- [x] P7-WEB-04 可核验问答：新会话、历史 answer/citation、受权来源预览、反馈、会话重命名/归档及真实 streaming/cancel 已完成。
- [x] P7-WEB-05 上下文工具：来源/Revision/Chunk、检索命中和 Citation 可直接进入 Studio/Playground；URL 可恢复上下文，普通路径不再要求手填内部标识或 synthetic `queryVector`。
- [x] P7-WEB-06 配置与运维生命周期：Provider 配置与验证、Run/correlation 查询、反馈、审计、成本和聚合健康均已有受权页面；列表使用可恢复路由与 cursor 分页。

## 3. P1：建立可信的开发与回归基线

- [x] P7-TEST-01 统一 preflight：跨平台脚本检查 JAVA_HOME、Java、Maven 实际绑定的 Java 版本、Node/npm 与 Docker daemon；JDK 低于 21 或无法确认 Maven 绑定时失败。
- [x] P7-TEST-02 全量 JVM 回归：JDK 21 + Docker 下 Server/Worker 全 reactor 308 tests，307 passed、0 failed、0 errors、1 skipped；唯一 skipped 为环境依赖的真实 Ollama RAG 用例。
- [x] P7-TEST-03 Web 自动化：Vitest 与 Playwright 已接入；当前候选 10/10 unit、10/10 E2E，覆盖 8 条核心旅程及路由/分页恢复。
- [x] P7-TEST-04 契约-实现一致性：OpenAPI operation 与 Controller mapping 严格双向覆盖 102/102，contract 52/52。
- [x] P7-TEST-05 RAG 变更评估：变更触发脚本已接入；当前候选对 128-case 数据集执行并通过，跨空间、Evidence 外引用、提示注入工具违规和未授权云调用均为 0。
- [x] P7-TEST-06 Web 导航与数据规模：URL Router、可恢复状态和 cursor 分页已完成；120 sources、7 jobs、6 indexes fixture 与刷新恢复 E2E 通过。

## 4. P2：产品闭环后执行 Linux 交付

- [x] P7-DEPLOY-01 容器加固：Web 改为非 root；为 Server/Worker 增加 Compose health/readiness；三类应用统一 capability、只读文件系统/受控写路径、资源限额、优雅关闭和日志验证。证据见 [`phase7-container-hardening.v1.json`](../../tests/evidence/phase7-container-hardening.v1.json)。
- [x] P7-SUPPLY-01 发布镜像：基础与应用镜像固定 immutable digest；使用目标镜像生成 SBOM/Grype 结果；生产 Secret 不使用 Compose 默认占位值，也不进入展开配置、镜像或日志。P7D-02R 已在 `--fail-on high` 下重新验收通过，证据见 [`P7D-02R-worker-summary.v1.json`](../../tests/evidence/P7D-02R-worker-summary.v1.json)。
- [ ] P7-DEPLOY-02 干净 Ubuntu 24.04：从文档构建并启动 core + app，以公共合成 fixture 完成平台初始化、Provider test、空间/成员、Git/文件摄取、active index、streaming 引用问答、反馈、审计及跨空间/未授权出境拒绝。当前阻塞：本机没有独立 Ubuntu 24.04 WSL/VM，不能以 Docker Desktop/共享卷替代。
- [ ] P7-OBS-01 叠加 observability profile，验证 Dashboard、trace/log 脱敏、告警和 Runbook 可定位规定故障。
- [ ] P7-UPGRADE-01 从上一兼容基线升级并在兼容矩阵允许范围内回滚；验证 PostgreSQL、对象、Qdrant、BM25/rebuild 和 citation 一致性，只使用合成数据。
- [ ] P7-PUBLIC-01 执行 secret、个人信息、Obsidian 内容、生产数据、raw prompt、许可证、Notice、历史和大文件检查。根许可证、release 版本和生产迁移仍需用户单独批准。

## 5. 退出条件

P0/P1 的本地实现与回归验收、P7-DEPLOY-01 和 P7-SUPPLY-01 已完成；当前继续等待独立 Ubuntu 24.04 环境以执行 P7-DEPLOY-02，不能以本地 Windows/Docker Desktop 结果替代。实用性验收要求普通用户不借助数据库 seed、API 客户端或手填内部 UUID/hash 完成首次设置、协作、来源维护、失败恢复、索引生命周期和可核验问答。创建 release、接受根级许可证、执行生产迁移不在本清单的自动授权范围内。
