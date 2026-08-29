# Phase 7 执行计划：实现对齐与 Linux 交付

- Version: `phase7-plan.v5`
- Status: `implementation-reconciliation`
- Code baseline: `f6b016840e946ea314cdaf4812c196dcea8ca491`
- Checklist: [`PHASE_7_CHECKLIST.md`](../../03-delivery/PHASE_7_CHECKLIST.md)

## 目标与边界

先消除代码与 MVP 需求之间的断点，再进行 Linux 发布验收。保持模块化单体 + 独立 ingestion worker；所有内容访问继续强制 `space_id`，云出境继续显式 opt-in，citation 继续来自结构化 provenance。AI Runtime 只可承担 OCR/rerank，不得演变成第二业务后端。

## 代码审计结论

| 区域 | 已实现代码 | 未实现或不一致 | 结论 |
|---|---|---|---|
| 身份/空间 | Session、CSRF、成员角色、用户/空间管理、一次性平台管理员 bootstrap、按精确邮箱加入成员 | Provider 首次配置仍缺少验证发布闸门 | 干净数据库可进入平台管理与成员协作，尚不能完成可用 Provider 配置 |
| Provider | space-scoped connection/profile/route/binding 与 adapter | Editor 可登记 connection，与平台管理员 ownership 需求不一致；connection test 仅有 OpenAPI；verified capability 不控制发布 | 权限模型和 `PUBLISHED` 均未达到需求语义 |
| 摄取 | 上传、网页、revision/artifact/job、Worker pipeline | Git/local connector 未接 Server/Web/调度 | 不能宣称 Git 知识源用户旅程完成 |
| 问答 | material-backed retrieval、Provider 原生 streaming、结构化 citation、durable answer/event、Last-Event-ID、同/多实例 cancel | citation 正文、历史 citation、反馈和上下文导航仍未闭环 | 流式生成与取消已真实接线，但完整普通用户核验旅程仍属于 P7-D |
| Retrieval | Qdrant dense、RRF、parent expansion、lexical rerank | BM25 进程内；RERANK route 未调用；AI Runtime 空壳 | 重启与配置语义不满足部署要求 |
| 管理 | 用户/空间/模型/Prompt 页面、raw health link | feedback、审计/成本/聚合健康页面缺失 | PRD 管理闭环不完整 |
| 测试 | Java/contract/安全/评估资产丰富 | Web 无测试脚本；契约未校验 Controller 覆盖；本机 preflight 不可靠 | 当前候选不可据此判定全绿 |
| 部署 | core/app Compose、Server/Worker 非 root | Web root；应用 health/资源/capability/digest/Secret 仍不足 | 只能作为开发部署资产 |

### 前端实用性结论

现有浏览器证据证明特定操作者可以沿预置数据跑通一次 happy path，但不能证明产品闭环。成员无法从页面加入空间；Provider 未测试即可发布；多文件没有逐项终态；来源/任务/索引没有维护和恢复；Citation 不显示来源正文；Chunk Studio、Playground 与 Run 依赖手填内部 ID；列表静默截断且没有自动化。因此 Web 的当前定位是工程控制台，部署验收暂停到下列可用性工作包完成。

## 执行顺序

| 工作包 | 状态 | 主要产物 | 完成条件 | 依赖 |
|---|---|---|---|---|
| P7-A 审计与任务冻结 | completed | 本计划、checklist、风险/追溯更新 | 任务由代码事实驱动，历史声明不作为完成证据 | 无 |
| P7-B 首次设置与协作 | completed | admin bootstrap、成员加入、connection test、verified publish | 干净数据库可通过 Web 完成平台初始化、空间协作和可核验 Provider 配置；完整 RAG 一键绑定仍受 P7-D 真实 RERANK 约束 | P7-A |
| P7-C 来源、任务与索引维护 | pending | 来源库、Git source、多任务终态、重试/同步/归档、索引发布/回滚 | 增长中的知识库可持续维护，失败不需要 API/DB 修复 | P7-B |
| P7-D 问答与上下文工具 | in_progress | ~~streaming/cancel~~、citation 正文、历史 citation、新会话/反馈、Studio/Playground 上下文跳转、durable lexical、rerank adapter | 普通用户不手填内部标识即可完成可核验问答；运行时与配置语义一致 | P7-C |
| P7-E 管理与 Web 自动化 | pending | Provider/Prompt 生命周期、Run/audit/cost/health 查询、Router/pagination、Web tests、contract-implementation gate、preflight | 关键 UI/API/失败/规模/权限路径可自动回归 | P7-B/P7-C/P7-D |
| P7-F 镜像与 Compose 加固 | pending | non-root Web、health、limits、digest、Secret | Definition of Done 可部署能力满足 | P7-E |
| P7-G Ubuntu/观测/升级验收 | pending | clean deploy、smoke、observability、upgrade/rollback evidence | 同一候选 SHA 和镜像 digest 可追溯 | P7-F |
| P7-H 供应链与阶段闭环 | pending | SBOM/Grype、public audit、retrospective、状态记录 | checklist 全满足；release 仍需单独批准 | P7-G |

## 2026-08-29 可重跑审计结果

- PASS：format、architecture、52 contract tests、Compose static validation、secret scan。
- BLOCKED：系统 `java` 为 21，但 Maven 绑定 JDK 8；显式改为 JDK 21 后 Server 执行 187 tests，其中 20 个 Testcontainers tests 因 Docker daemon 不可用报 error、1 个真实 Ollama 用例 skipped，Worker 因 reactor 中止未执行。
- BLOCKED：Node/npm 当前不在 PATH，Web typecheck/build/test 未在本轮执行；项目本身也没有 Web test script。
- NOT RUN：容器 build/up/health、真实业务 smoke、SBOM/Grype target image、Ubuntu、升级/回滚。

## 2026-08-29 执行增量

- P7-WEB-01 已完成：空间管理员可在 Web 按已知邮箱把 ACTIVE 注册用户加入当前空间并选择 `SPACE_ADMIN`、`EDITOR` 或 `VIEWER`；精确匹配避免开放平台用户目录。
- Server 单元 3/3、完整 `ServerIntegrationTest` 8/8、成员定向集成 1/1、contract 52/52、Web typecheck/build、format、architecture 和 secret scan 均通过。
- P7-CORE-01 已完成：登录页仅在“无 ACTIVE 平台管理员且服务端配置 bootstrap Token”时显示首次设置；Token 最少 32 字符，仅由请求 Header 提交，不持久化到浏览器、响应或审计。服务端可创建新管理员或提升既有 ACTIVE 用户，并替换其密码；停用用户拒绝提升。
- 并发 bootstrap 由 PostgreSQL transaction advisory lock 串行化；首个成功事务提交后，其余请求返回 `409 bootstrap_already_completed`。普通注册仍固定为 `USER`，不采用“首个注册用户自动提权”。
- Bootstrap properties 3/3、完整 `ServerIntegrationTest` 12/12（含密钥、一次性、ACTIVE 用户提升、停用用户拒绝、审计脱敏和并发）、Web typecheck/build 通过；最终静态与契约门禁见本次提交记录。
- 当时 P7-B 工作包保持 `pending`，因为 Provider connection test 与 verified publish gate 尚未完成；后续完成状态见“Provider 验证增量”。部署验收继续暂停。

## 2026-08-29 Provider 验证增量

- P7-CORE-02 已完成：connection 保持 space-scoped；登记/实测仅限目标空间内的平台管理员，Profile/Route 仅限空间管理员或同空间平台管理员，Editor/Viewer 写入拒绝。
- `V18__provider_connection_test_runs.sql` 保存 connection/model/purpose、成功/失败、verified capabilities、embedding dimension、错误分类、retryable、耗时、操作者和 correlation ID；不保存 Secret、credentialRef、Header、合成请求或 Provider 响应正文。
- CHAT/EMBEDDING 通过 production adapter 发送固定合成样本。云端探测要求逐次 `allowCloudProbe=true`；探测前校验 URI、DNS/IP 与出境等级，LOCAL 不得访问公网，CLOUD 只允许 HTTPS 公网。RERANK adapter 不存在时明确记录 `UNSUPPORTED_CAPABILITY`。
- Profile `PUBLISHED` 必须匹配同 connection/model/purpose 的最新测试且结果成功；声明能力必须是 verified 子集，embedding 维度必须一致。后续失败复测会立即阻断新发布，不能继续沿用更早的成功结果。
- P7-B 标记完成，但这不代表完整本地 RAG 一键初始化完成：当前 RERANK runtime 仍是 `P7-CORE-05` 的阻塞项，页面不得把 CHAT 探测冒充 RERANK 验证。部署验收继续暂停。

## 2026-08-29 Streaming 增量

- P7-CORE-03 已完成：Ollama NDJSON 与 OpenAI-compatible/MiMo SSE 进入 production adapter；CHAT connection probe 同时验证真实 `STREAMING`。
- 生成桥只投影增量解码的 `answer_text`，不透传 provider frame/JSON；durable delta、终态 citation 校验、失败清除暂态文本、稳定 answer ID 和不重复终态正文已闭环。
- cancel 通过本机 active token 和共享 run-event fan-out 关闭生成实例上游流；event store 在 `CANCELLED` 后继续独立拒绝 delta，Last-Event-ID 复用持久序列。
- 定向 Java 63/63、事件持久化/多实例 fan-out 回归 6/6 通过。本轮没有部署或调用真实模型；P7-D 仍因 citation 正文/历史、上下文导航、durable lexical 和真实 RERANK 等工作保持 `in_progress`。

## 证据规则

- runtime 证据记录候选 SHA、镜像 digest、JDK/Maven/Node/Docker/Compose 版本、fixture 版本和结果。
- 证据只保存 hash、ID、计数、状态、耗时和脱敏错误，不保存 Secret、Cookie、raw prompt、文档正文或个人路径。
- 失败或环境阻塞必须原样记录；修复后生成新证据，不能覆盖为历史“已通过”。
- RAG、权限、出境、parser 或 tool 行为变化继续触发安全与评估复核。
