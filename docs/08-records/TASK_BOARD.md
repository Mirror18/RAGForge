# RAGForge 任务看板（TASK_BOARD）

> 本看板与 [`AGENT_STATE_CARD.md`](AGENT_STATE_CARD.md) §6 的分派表一一对应，是 Agent 执行任务的预算与验收真源。
> 每张卡片有独立 Token 预算、依赖、归属目录、必跑测试；**超预算 20% 必须停下汇报**。
>
> 版本：`board.v4` | 生效基线：Phase 7 `p2-execution` | 生成日期：2026-08-30

---

## 0. 使用规则（Orchestrator 必须遵守）

1. **串行 / 并行边界**：§3 的依赖图是最高判断。共享资产（见状态卡 §3）一次最多 1 张卡片修改。
2. **分派上限**：同一时刻最多 3 张 active 卡片（保留 orchestrator 能力做集成与审计）。
3. **预算格式**：`Token 预算` 列是包含「阅读指定文件 + 实现 + 测试 + 提交 + 回报摘要」的总上限。
4. **结算规则**：每张卡片完成后，Agent 必须在状态卡 §6 填入「实际消耗」。若实际 > 预算 × 1.2 且验收未通过，立刻停工，由 orchestrator 拆卡或追加预算。
5. **完成验收**：每张卡片的「验收输出」必须被实际执行（脚本跑通 / 测试绿 / 代码差异检查通过），不得仅用文档打勾。
6. **阶段推进**：优先级必须严格按 P0 → P1 → P2。P2 的部署卡片在 P0、P1 全部通过前不得启动。

---

## 1. P0：阻断 MVP 的实现断点（9 张卡片，预算合计 ≈64,000 tokens）

> 这些卡片若不完成，「Web 是工程控制台，不是可交付产品」的结论就无法推翻。
> 部署验收在 P0、P1 完成前暂停，不得把任务重心收缩为「只做部署」。

### 依赖图（P0 内部）

```
P7C-01 ─► P7C-02 ─► P7C-03 ─► P7C-06 ─► P7C-07 ─► P7C-08
P7C-04（可并行） ─► P7C-05
```

| 卡片 ID | 标题 | 依赖卡片 | Ownership（可写目录） | Token 预算 | 验收输出 | 必跑测试门禁 |
|---|---|---|---|---:|---|---|
| **P7C-01** | **来源任务中心后端 API**：多文件逐项状态、分页、筛选、失败重试 / 重放、重新同步、归档 / 删除 | 无（P7-B 已完成） | `apps/server/ingestion/`、`apps/server/chunk/`、`apps/server/index/`、`contracts/openapi/ragforge-api-v1.yaml`、`apps/server/src/main/resources/db/migration/`（**迁移单 owner：V19 系列**） | **8,000** | 新增 /jobs、/sources、/index 列表 API；`slice(0,5)` 的内部捷径全部替换；删除/归档/重试都有版本乐观锁 | contract 52/52 + 定向集成测试（`ServerIntegrationTest` 新增 5+ 用例）+ Web typecheck/build |
| **P7C-02** | **来源与任务中心前端 UI**：提交 → 处理中 → 终态展示、分页、筛选、失败重试、重新同步、删除/归档、错误详情 | P7C-01 | `apps/web/`（BusinessFlowView / 新组件 TaskCenter / SourceLibrary） | **7,000** | 100+ 合成资源的 fixture 下，列表无静默截断；所有按钮受 CSRF + `space_id` 保护 | Web typecheck/build + `ServerIntegrationTest` 权限拒绝回归 |
| **P7C-03** | **索引生命周期 UI**：candidate 的构建/验证依据展示 → 发布 → active → 回滚上一版 → retired；文案不得把 candidate 描述为 active | P7C-01 | `apps/web/`、`apps/server/index/`（若 API 缺少回滚/退役端点则补少量） | **6,000** | UI 显示 candidate/active/retired 三种状态；回滚产生 previous pointer；≥2 个索引版本的数据能正确切换 | 定向 UI 路由测试（若无自动化就写手工复现脚本并留 JSON 证据）+ contract 52/52 |
| **P7C-04** | **Durable BM25（R-023 关闭）**：选型 ADR + 替换 `InMemoryBm25CandidateStore`，重启后 lexical 重建持久化 | 无（可与 P7C-01 并行） | `docs/02-architecture/adr/`（ADR-0012）、`apps/server/retrieval/`、`apps/ingestion-worker/`（若重建任务放 Worker） | **10,000** | ADR-0012 状态 Accepted；新 Provider 有 space/index 作用域；重启 + 重建集成测试通过；R-023 在风险表标记 CLOSED | RetrievalServiceTest + 新增「重启后 lexical index 命中」集成测试 + contract 52/52 |
| **P7C-05** | **真实 RERANK adapter 接线**：把声明 `RERANK` route 接到真实 adapter（`apps/ai-runtime`）；Provider connection test 对 RERANK 能真实返回 verified capability；不再允许 `LexicalReranker` 冒充 | P7C-04 | `apps/ai-runtime/`、`apps/server/provider/`、`apps/server/retrieval/` | **8,000** | RERANK connection test 有独立 success/failed/UNSUPPORTED_CAPABILITY 路径；Profile PUBLISHED 闸门对 RERANK 同样生效 | Provider/Model/Binding 14/14 + 新增 RERANK loopback 探针用例 4/4 + contract 52/52 |
| **P7C-05R** | **RERANK test-profile adapter 冲突修复**：全 reactor 中 `FakeProviderAdapter` 与 production AI runtime adapter 重复注册 `AI_RUNTIME`，导致两个 Spring context 无法启动 | P7C-05 | `apps/server/provider/adapter/`、对应 adapter tests | **4,000** | test profile 只保留 fake adapter；默认 production profile 仍注册真实 AI runtime adapter；registry 不再重复 | 两条原失败 Spring 测试 + AI runtime adapter/Provider probe 回归 + contract 52/52 |
| **P7C-06** | **可核验问答 Web**：明确新会话入口；历史 answer + citation 恢复；可阅读来源预览或受权原文跳转；反馈 API + UI；会话重命名/删除；真实 streaming 时「回答增量」文案改为规范描述 | P7C-01、P7C-03 | `apps/web/`（AnswerView / 新组件）、`apps/server/answer/`（若历史 citation API 缺失） | **9,000** | 普通用户不手填 UUID 可完成：新会话 → 问答 → 查看 citation 原文 → 反馈 → 看历史回答；citation preview 不再丢弃响应内容 | Web typecheck/build + 新增历史/反馈 ServerIntegrationTest 3/3 + contract 52/52 |
| **P7C-07** | **上下文工具跳转**：来源/Revision/Chunk → Chunk Studio；检索命中 / Citation → Retrieval Playground；普通路径不要求手填 `childChunkId`、`contentRef`、hash、index/profile UUID/version；生产 UI 不暴露 `queryVector` | P7C-06 | `apps/web/`（Studio 两页 + 跳转参数）、`apps/server/studio/`（补 lookup API，如 GET /studios/lookup-by-doc） | **6,000** | 文档列表 → Chunk Studio；引用 → Playground；Studio / Playground 两页刷新后仍能恢复原上下文 | Web typecheck/build + 新增 lookup 端点单元 3/3 + contract |
| **P7C-08** | **管理闭环**：用户反馈聚合查询 API/UI、Provider/依赖健康聚合、受权的审计 / 成本视图（不再只有 raw actuator 链接与内部 `Phase6OperationsService`） | P7C-06 | `apps/server/ops/`、`apps/server/agent/`（审计投影）、`apps/web/`（ControlCenter 新增子视图） | **6,000** | 平台管理员可查看 aggregate health、cost by space、feedback list、audit export（space-scoped）；普通用户看不到 | 权限集成 4/4、聚合查询单元 4/4 + contract 52/52 |

---

## 2. P1：建立可信的开发与回归基线（7 张卡片，预算合计 ≈44,000 tokens）

> 这些卡片不影响产品功能，但如果不完成，P2 的部署验收无法「同一候选 SHA 的全量证据」要求。

| 卡片 ID | 标题 | 依赖 | Ownership | Token 预算 | 验收输出 | 必跑测试/门禁 |
|---|---|---|---|---:|---|---|
| **P7Q-01** | **统一 preflight 脚本**（Powershell + Bash）：检查 Maven 实际绑定 JDK（不是只看 `java -version`）、Node/npm 是否真的可用、Docker daemon 可连接到 Testcontainers。把 2026-08-29 审计中暴露的「Maven 绑 JDK 8 / Node 不在 PATH / Docker 未运行」三种漂移自动化告警。 | P7C-01（部分，主要是工具链） | `scripts/agent/`（新建）、`.github/workflows/`（CI 接入） | **3,000** | Windows + Linux 均可跑；失败 exit code 非 0；输出明确写明失败项和修复指令 | 脚本自测 + CI workflow 新增 preflight step 通过 |
| **P7Q-02** | **全量 Maven 回归配方**：显式 JDK 21 执行 `mvn -q test` 的 Server + Worker 全 reactor，并把汇总写入 CI artifact（当前本地手工指定 JDK 才能跑）。 | P7Q-01 | `pom.xml`、`apps/server/pom.xml`、`apps/ingestion-worker/pom.xml`、`.github/workflows/` | **2,000** | GitHub Actions 增加「全量 JVM 回归」job，Server 完整 200+ / Worker 28/28 汇总到 artifact | CI job 全绿 + artifact 下载可查看汇总 |
| **P7Q-03** | **Web 自动化测试套件**（登录 → 首次设置 → 上传/轮询 → 索引发布 → 问答 → 取消 → 历史/归档 → 权限 → 跨空间拒绝 共 8 条关键旅程）。为 `apps/web/package.json` 增加 `test:unit` 与 `test:e2e` 脚本；禁止 Web 继续只有 typecheck/build。 | P7C-06、P7Q-01 | `apps/web/`（新建 tests 目录） | **15,000** | package.json 至少包含 `vitest run` 和 `playwright test` 两个 script；8 条旅程有 E2E；合成 fixture 跑通 | `npm --prefix apps/web run test:unit` 通过；E2E 在隔离环境有证据文件 `tests/evidence/phase7-web-e2e.v1.json` |
| **P7Q-04** | **契约-实现一致性门禁**：脚本校验 OpenAPI 的每个 `operationId` 都有对应 `@RequestMapping` Controller；反向检查 Server 暴露的端点都在 OpenAPI 中。捕获 P7 审计时遇到的「provider-connections/{id}/test 契约存在、实现缺失」类缺口。 | 无（独立） | `scripts/ci/`、`.github/workflows/` | **4,000** | contract 目录新增 1 个 `coverage_test.py`；CI 接入；遗漏项显式列出 operationId 名 | 脚本对当前代码无 false positive；故意删除一个 operation 对应 controller 能让脚本失败 |
| **P7Q-04R** | **契约/Controller 对齐修复**：修正 P7Q-04 发现的 8 个 OpenAPI→Controller 缺口与 6 个 Controller→OpenAPI 缺口；保持既有安全边界与公开语义，不通过忽略规则掩盖漂移。 | P7Q-04 分析结果 | `contracts/openapi/`、`apps/server/`、`scripts/ci/`、`.github/workflows/` | **12,000** | 严格双向 coverage 通过；缺口修复有定向测试；contract 52/52；CI 门禁可阻断故意缺口 | 所有 operationId 与 mapping 双向匹配；无 false positive |
| **P7Q-05** | **RAG 变更强制评估触发**：识别 git diff 涉及 retrieval/answer/prompt/chunk/embedding/rerank/parser 文件时，自动要求重跑 128-case 评估，生成 baseline/candidate 对比报告；Phase 6 的人工评审豁免不得自动覆盖新候选。 | 无（独立） | `scripts/ci/`、`.github/workflows/` | **5,000** | CI 新增评估触发 gate；评估失败时阻断合并；评估报告保存在 `tests/evidence/phase7-evaluation-*.v1.json` | 对一段 retrieval 逻辑变更的合成 commit，确实触发评估执行；无变更时跳过 |
| **P7Q-05R** | **RAG gate 浅克隆恢复**：quality Run 33306553953 在 push 跨 101 commits 时因 checkout 不含 `github.event.before`，`git diff` 报 `fatal: bad object` | P7Q-05 | `.github/workflows/quality.yml`、workflow 回归测试/证据 | **4,000** | checkout 必须保证 push/PR 的 base/head commit 均可解析；RAG gate 继续 fail-closed，不允许把 bad object 当作 skipped | 本地复现 shallow failure + 完整历史/显式 fetch 修复验证 + YAML/format/secret gate |
| **P7Q-06** | **Web 导航与规模门禁**：引入 URL Router；所有列表都要有 cursor 分页或增量加载；在 >100 个资源、>5 个任务 / 索引的合成 fixture 下验证；任何 `slice(0, 5)` 或 `limit=100` 的硬截断必须移除；刷新页面后回到同状态。 | P7C-06 | `apps/web/`（Router + 通用分页组件） | **11,000** | 所有列表 >100 的数据能正确翻页；刷新 5 个典型页面都能回到同状态；Playwright 有一条「>100 sources 分页 + 状态恢复」E2E | Web typecheck/build + P7Q-03 的相关 E2E 扩展通过 |

---

## 3. P2：Linux 交付与发布准备（8 张卡片，预算合计 ≈64,000 tokens）

> **硬性前置**：P0 的 9 张卡片（含 P7C-05R）+ P1 的 7 张卡片（含 P7Q-05R）全部验收通过，且同一候选 SHA 的 CI 全绿。
> 发布前的「根许可证选择 / 版本号 / 生产迁移执行」仍需用户单独批准（AGENTS.md Non-negotiable rules 高风险审批点）。

| 卡片 ID | 标题 | 前置 | Ownership | Token 预算 | 验收输出 | 必跑测试/门禁 |
|---|---|---|---|---:|---|---|
| **P7D-00** | **Actions Node.js 24 运行时升级**：升级仍以 Node.js 20 为目标的官方与第三方 Actions；保留完整 git 历史获取，避免 RAG gate 因浅克隆失效；工作流显式启用 Node.js 24 兼容运行时。 | P0 + P1 全绿 | `.github/workflows/quality.yml`、`.github/workflows/README.md`（如涉及） | **4,000** | `setup-java` 升级到 v5；checkout/setup-python/cache/setup-node/upload-artifact/Anchore 扫描动作使用已验证的 Node.js 24 兼容版本；YAML 中无旧版本残留；远程 quality run 无 Node.js 20 deprecation warning | workflow YAML/format/secret gates + 远程 quality run 绿 |
| **P7D-01** | **容器加固**：Web 改为非 root；Server/Worker 在 Compose 里新增 health/readiness；三类应用统一 capability、只读文件系统 + 受控写路径、资源限额（mem/cpu）、优雅关闭、日志上限。 | P0 + P1 全绿 | `deploy/compose/compose.yaml`、各应用 Dockerfile | **8,000** | `docker compose --profile app up -d` 后 `docker inspect` 三项都为 non-root；healthcheck 状态变成 healthy；日志超限时自动轮转 | 本地脚本化验收（输出到 `tests/evidence/phase7-container-hardening.v1.json`） |
| **P7D-02** | **发布镜像与供应链硬化**：基础镜像与应用镜像全部锁定 immutable digest；使用目标镜像（不是源码）生成 SBOM/Grype 结果；生产 Secret 不使用 Compose 默认占位值、不进入展开配置、镜像或日志。 | P7D-01 | `deploy/compose/`、`.github/workflows/`、根 `.env.example` | **7,000** | 所有镜像 digest 在 `deploy/compose/` 有清单；镜像级 SBOM 和 Grype SARIF artifact 最新可用；Secret 审计脚本返回 0 | 目标镜像 SBOM/Grype；secret scan 脚本针对镜像执行 |
| **P7D-02R** | **P7D-02 漏洞修复与重新验收**：修复目标镜像的 Critical/High 漏洞（基础层与应用依赖），重新生成 SBOM/Grype，并保持 fail-closed；不得通过降低阈值、忽略规则或未审批例外掩盖发现。 | P7D-02（阻塞基线 `20c7f87`） | `pom.xml`、`apps/server/pom.xml`、`apps/ingestion-worker/pom.xml`、`deploy/docker/`、`deploy/compose/`、`scripts/ci/`、`tests/ci/`、`tests/evidence/` | **10,000** | server/worker/web 目标镜像在 `--fail-on high` 下通过；所有修复版本与 digest 可追溯；保留 P7D-02 的 Secret/SBOM/Grype 证据链 | Java/Web 回归 + 目标镜像构建 + 镜像 SBOM/Grype + Secret 审计 + format/secret gates |
| **P7D-03** | **干净 Ubuntu 24.04 完整部署验收**：从 0 文档执行到 RAG 业务闭环 smoke（平台初始化 → Provider test → 空间/成员 → Git/文件摄取 → active index → streaming 引用问答 → 反馈 → 审计 → 跨空间拒绝 → 未授权云出境拒绝）。合成 fixture。 | P7D-02R | `docs/05-operations/DEPLOYMENT.md`、`deploy/compose/`、`tests/e2e/` | **18,000** | Ubuntu 24.04 ISO + Docker + RAGForge 部署脚本；所有 10 条旅程产出结构化证据；RPO=0s 满足 | 独立证据文件 `tests/evidence/phase7-ubuntu-smoke.v1.json`；人工复核清单可勾选 |
| **P7D-04** | **Observability overlay 验证**：叠加 observability profile，验证 Dashboard、trace/log 脱敏、告警、Runbook 可定位规定故障。 | P7D-03 | `deploy/compose/observability.yaml`、`docs/05-operations/` runbooks | **8,000** | Grafana dashboard 有数据；OTel trace 的 prompt/正文字段脱敏；4 个 Runbook 演练 4/4 可定位故障 | 证据文件 `tests/evidence/phase7-observability.v1.json` |
| **P7D-05** | **升级与回滚演练**：从上一兼容基线（Phase 6 闭环的 `462c7a5`）升级到当前版本 → 验证 citation、索引、对象一致性 → 在兼容窗口内回滚。仅用合成数据。 | P7D-03 | `docs/05-operations/DEPLOYMENT.md`（upgrade/rollback 章节） | **9,000** | upgrade / rollback 各执行一次；citation 与 Qdrant 引用一致；所有版本化 migration 兼容 | 证据文件 `tests/evidence/phase7-upgrade-rollback.v1.json` |
| **P7D-06** | **公共化清理**：执行 secret / 个人信息 / Obsidian 内容 / 生产数据 / raw prompt / 许可证 / Notice / 历史 / 大文件检查。根许可证、release 版本、生产迁移仍需用户单独批准。 | P7D-05（清理是 release 前最后一步） | 仓库根 `.gitignore`、`docs/00-governance/REPOSITORY_LICENSING.md`、根 LICENSE（待用户批准） | **5,000** | secret scan / PII scan / 大文件 scan 全部通过；公共化检查 checkist 有逐项 SHA 证明 | 扫描脚本 + 输出 + `docs/07-research/UPSTREAM_REUSE_REGISTER.md` 与 `THIRD_PARTY_NOTICES.md` 最新核对 |
| **P7D-07** | **Release 文档与阶段闭环**：PROJECT_STATUS、RISK_REGISTER、TRACEABILITY_MATRIX、阶段 retrospective 更新；创建 CHANGELOG 条目。**在用户显式批准版本号 + 根许可证 + 生产迁移之前，禁止 `git tag` 或创建 release。** | 用户批准：版本号 / 根许可证 / 生产迁移。否则不进入。 | `docs/08-records/` 全部治理文档、`CHANGELOG.md` | **5,000** | Phase 7 retrospective 完成；RISK_REGISTER 所有 OPEN/MITIGATING 高风险要么关要么接受要么缓解有证据；追溯矩阵通过；checklist 全部勾选 | （治理文档审查，没有测试；必须含用户签名/批注的豁免项） |

---

## 4. 预算汇总

| 优先级 | 卡片数 | 预算合计 | 说明 |
|---|---:|---:|---|
| P0 | 9 | 64,000 | 含 P7C-05R 回归恢复；全部通过后，Web 才从「工程控制台」变成普通用户可用产品 |
| P1 | 7 | 44,000 | 含 P7Q-05R 浅克隆恢复；全部通过后，部署验收有同一 SHA 的真实全量门禁 |
| P2 | 9 | 74,000 | 含 Actions Node.js 24 兼容、供应链漏洞修复、Ubuntu 真实机、观测叠加、升级回滚、公共化清理、release 治理 |
| **合计** | **25** | **182,000** | 实际执行若 <159.6k = 高效；>218.4k（超预算 20%）= 必须中途拆卡复盘 |

---

## 5. 卡片新增/变更规则

- 新增卡片必须写入 §1/§2/§3 中对应表格，同时同步更新 `AGENT_STATE_CARD.md` §6 分派表。
- 删除卡片必须写理由：是完成、合并、还是取消。取消类必须在 `PROJECT_STATUS.md` 留审计记录。
- 预算调整：单张卡片预算修改 >10% 必须在本看板的「备注」里列出理由，不得在 Ticket 中偷偷放大。
- 本看板版本化：每次改卡片结构（不是改状态），在页头把 `board.vN` 加 1，记录 SHA。

> board.v4 结构变更：新增 P7D-02R 漏洞修复与重新验收卡，并将 P7D-03 的前置调整为 P7D-02R。
