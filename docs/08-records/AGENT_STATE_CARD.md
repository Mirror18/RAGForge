# Agent 状态卡（AGENT_STATE_CARD）

> ⚠️ **Agent 入口规则**：所有 AI Agent 每轮启动时，**本文件是唯一必读的状态入口**。
> 除非执行的是「审计 / 阶段复盘 / 发布验收」类高上下文任务，否则不应主动读取下面这些长文档：
> - `PROJECT_STATUS.md`（治理与证据档案，180+ 行）
> - `ROADMAP.md`（阶段定义，200+ 行）
> - 各阶段 CHECKLIST、EXECUTION_PLAN（仅在 Worker Ticket 明确列出时按需读取）
> - `AGENTS.md` 的全部章节（仅在需要确认硬规则时读取 Non-negotiable rules 与需要的章节）
>
> 项目真相文档与本文件的优先级：
> 1. `AGENTS.md` 的「Non-negotiable rules」= 硬规则（不可变）
> 2. 本文件 = 日常运行状态的最近快照（每次合并后更新）
> 3. `PROJECT_STATUS.md` = 审计/阶段/证据级权威记录（状态卡与它冲突时，以 PROJECT_STATUS 为准并回写修正状态卡）
>
> 上一次更新：2026-08-30 | 更新人：Orchestrator | 对应基线 SHA：609ef5c9a1284bef71ed9295910aeb9c48d383cb

---

## 1. 快照元信息（10 行以内，Agent 一眼定位）

- **阶段**：Phase 7（`implementation-reconciliation`）
- **主线基线 SHA（main）**：`609ef5c9a1284bef71ed9295910aeb9c48d383cb`；`origin/main`：`9fdd94e0e12afae1c3843d0680fb48017e00669f`
- **最近已通过 CI**：GitHub Actions quality Run [32577917976](https://github.com/Mirror18/RAGForge/actions/runs/32577917976)（4m52s，全绿）
- **状态卡锚点提交**：`609ef5c9a1284bef71ed9295910aeb9c48d383cb`（Agent 效率文档骨架已落主线）。
- **最近阶段完成**：Phase 6（2026-08-23，`completed-with-explicit-waiver`，豁免人工评审 / red-team 签名门槛，见 PROJECT_STATUS §89）
- **当前工作包完成度（P7-A..H）**：
  - ✅ P7-A 审计与任务冻结
  - ✅ P7-B 首次设置与协作（bootstrap + 成员加入 + Provider 实测闸门）
  - ⏳ P7-C 来源/任务/索引维护（WEB-02/03 未完成）
  - 🚧 P7-D 问答与上下文工具（streaming/cancel 已完成，CORE-05/WEB-04/05 未完成）
  - ⏳ P7-E 管理与 Web 自动化
  - ⏳ P7-F 镜像与 Compose 加固
  - ⏳ P7-G Ubuntu/观测/升级验收
  - ⏳ P7-H 供应链与阶段闭环

---

## 2. 非协商红线（压缩版，原文见 AGENTS.md）

Agent 在任何场景下都不能违反的 6 条：

1. **`space_id` 是安全边界**：所有读取/写入租户内容的查询都必须包含并强制 `space_id`。
2. **云出境显式 opt-in**：禁止从本地路由静默 failover 到云端路由。
3. **结构化 provenance**：citation 必须来自 Evidence Bundle / chunk provenance，不得以自由文本生成引用。
4. **架构：模块化单体 + 独立 Ingestion Worker**；`apps/ai-runtime` 仅承担 OCR 与 rerank，不得演变为第二业务后端。
5. **提交规范**：一任务一分支一 worktree；中文 Conventional Commit：`<type>(<scope>): <中文摘要>`。
6. **高风险动作需用户审批**：接受 ADR、接受第三方许可证、开启云出境、执行生产迁移、创建 release。

---

## 3. 工作包依赖图（P7）

**并行组最多 3 张卡片；Orchestrator 必须确保无写冲突。**

```
P7-B（已完成）
├─► P7C-01 来源任务中心 API ◄── ownership: apps/server + contracts/openapi
│    ├─► P7C-02 来源任务中心 Web ◄── ownership: apps/web
│    └─► P7C-03 索引生命周期 UI ◄── ownership: apps/web + server 少量
│         └─► P7C-06 可核验问答 Web
│              ├─► P7C-07 上下文跳转
│              ├─► P7C-08 管理闭环（反馈/审计/成本）
│              └─► P7Q-06 Router + 分页 + 可恢复状态
├─► P7C-04 durable BM25（ADR + 实现）  ◄── **可并行，ownership: docs/adr + server**
│    └─► P7C-05 真实 RERANK adapter（apps/ai-runtime + server）
P7C-06 ─► P7E 卡片（P7Q-01~06，见 TASK_BOARD.md）
P7Q-01~06 全部通过 → 进入 P7-F 容器加固
P7-F → P7-G Ubuntu/观测/升级 → P7-H 供应链 & 阶段闭环（需用户显式批准 release）
```

**写冲突禁止并行的共享资产**：
- 数据库迁移 `V*.sql`：一个批次最多 1 个 owner
- `contracts/openapi/ragforge-api-v1.yaml`：一次最多 1 张卡片修改
- `docs/08-records/AGENT_STATE_CARD.md`、`PROJECT_STATUS.md`、`RISK_REGISTER.md`、`TRACEABILITY_MATRIX.md`：仅 Orchestrator 修改
- 根构建文件（`pom.xml`、`package.json`、`compose.yaml`、`.github/workflows/*.yml`）：一次最多 1 张卡片修改

---

## 4. 已完成的 P7 断点卡片速览（避免重复实现）

| 卡片 ID | 标题 | 对应 Checklist | 关键验证 | 提交 SHA（合并到 main 后回写） |
|---|---|---|---|---|
| P7-CORE-01 | 平台管理员 bootstrap | P7-B | BootstrapAdminProperties 3/3、ServerIntegrationTest 12/12 | 见 PROJECT_STATUS §13 |
| P7-CORE-02 | Provider 权限、验证与发布闸门 | P7-B | Provider/Model/Binding 集成 14/14；审计脱敏；云端未确认拒绝 403 | 见 PROJECT_STATUS §14 |
| P7-CORE-03 | 真实生成 Streaming/Cancel | P7-D | Provider HTTP + generation bridge + RAG + Answer API + loopback 63/63 | 见 PROJECT_STATUS §15 |
| P7-CORE-04 | Git 数据源接线 | P7-B | Git 只读 clone/discover、checkpoint、删除归档、任务投递 | PROJECT_STATUS §6 第 24 项 |
| P7-WEB-01 | 协作闭环（按邮箱加成员） | P7-B | SpaceServiceTest 3/3、ServerIntegrationTest 8/8、contract 52/52 | 见 PROJECT_STATUS §12 |

---

## 5. 证据与 CI 快速入口（失败才读全文，否则只用链接）

- 最近全量 CI：<https://github.com/Mirror18/RAGForge/actions/runs/32577917976>
- Phase 6 证据：`tests/evidence/phase6-*.v1.json`（8 类：evaluation / security / capacity / recovery / cost / observability / multi-instance / real-RAG）
- Phase 7 最新证据：`docs/08-records/2026-08-23-business-loop-e2e.md`、`2026-08-23-mimo-notes-business-loop.md`
- 契约测试命令：`python scripts/ci/contract_test.py`（本轮 52/52 通过）
- 全量 Maven（JDK 21，需 Docker）：Server + Worker，CI 最新 238 tests 0 failures
- Web 构建：`npm --prefix apps/web run typecheck` 与 `npm --prefix apps/web run build` 通过
- RAG 评估数据集：`tests/evaluation/phase6-evaluation-dataset.v1.json`（128 cases）

---

## 6. 本轮分派表（Orchestrator 唯一可自由编辑的区域）

每张完成的卡片必须写回「状态」与「完成 SHA」。Agent 本轮到此就结束，不要重复阅读工作包详细文档。

| 卡片 ID | 标题 | 优先级 | 状态 | 担当 Agent | branch | worktree | Token 预算 | 实际消耗 | 完成 SHA | 备注 |
|---|---|---|---|---|---|---|---:|---:|---|---|
| AGENT-OPT-01 | Agent 效率文档骨架落地 | P0 | ✅ completed | Orchestrator | main | RAGForge | 8,000 | 未记录 | 609ef5c9a1284bef71ed9295910aeb9c48d383cb | 已由主线提交完成；历史实际 token 未记录，不重复执行 |
| P7C-04 | durable BM25 ADR + 实现（R-023） | P0 | 🚧 in_progress | A2 | codex/p7-durable-bm25-a2 | RAGForge-worktrees/codex-p7-durable-bm25-a2 | 10,000 | — | — | 不新增迁移；与 P7C-01 并行 |
| P7C-01 | 来源任务中心后端 API | P0 | 🚧 in_progress | A3 | codex/p7-source-task-api-a3 | RAGForge-worktrees/codex-p7-source-task-api-a3 | 8,000 | — | — | 迁移 owner 固定为 V21；V19/V20 已被 P7-B 占用 |
| P7C-02 | 来源任务中心前端 UI | P0 | ⏳ blocked | — | — | — | 7,000 | — | — | 依赖 P7C-01 |
| P7C-03 | 索引生命周期 UI | P0 | ⏳ blocked | — | — | — | 6,000 | — | — | 依赖 P7C-01 |
| P7C-05 | 真实 RERANK adapter | P0 | ⏳ blocked | — | — | — | 8,000 | — | — | 依赖 P7C-04 |
| P7C-06 | 可核验问答 Web | P0 | ⏳ blocked | — | — | — | 9,000 | — | — | 依赖 P7C-01 / P7C-03 |
| P7C-07 | 上下文跳转 | P0 | ⏳ blocked | — | — | — | 6,000 | — | — | 依赖 P7C-06 |
| P7C-08 | 管理闭环（反馈/审计/成本） | P0 | ⏳ blocked | — | — | — | 6,000 | — | — | 依赖 P7C-06 |
| P7Q-01 | 统一 preflight | P1 | ⏳ pending | — | — | — | 3,000 | — | — | JDK 绑定、Node PATH、Docker daemon |
| P7Q-02 | 全量 Maven 回归 CI 配方 | P1 | ⏳ pending | — | — | — | 2,000 | — | — | |
| P7Q-03 | Web 自动化测试（8 条旅程） | P1 | ⏳ pending | — | — | — | 15,000 | — | — | Playwright/Vitest，package.json 补脚本 |
| P7Q-04 | 契约-实现一致性门禁 | P1 | ⏳ pending | — | — | — | 4,000 | — | — | |
| P7Q-05 | RAG 变更强制评估触发脚本 | P1 | ⏳ pending | — | — | — | 5,000 | — | — | |
| P7Q-06 | URL Router + 可恢复页面 + 分页门禁 | P1 | ⏳ blocked | — | — | — | 11,000 | — | — | 依赖 P7C-06 |
| P7D-01~07 | Linux 交付与发布（7 张） | P2 | ⏳ blocked | — | — | — | 60,000 | — | — | P0+P1 全通过后进入 |

---

## 7. Agent 停机条件（什么时候主动停下汇报）

满足以下任意一条，Orchestrator/Worker 必须停止并汇报给用户：
1. 触及 AGENTS.md 里的「高风险动作」（ADR 接受 / 许可证接受 / 云出境开启 / 生产迁移 / 创建 release）—— 先汇报，不要做。
2. 发现状态卡与 PROJECT_STATUS / 代码事实冲突 —— 先停下，以 PROJECT_STATUS 为准，建议由 Orchestrator 更新状态卡再继续。
3. 发现需要跨出 Ticket ownership 区域的修改 —— 向 Orchestrator 提精确建议，不要自行修改。
4. 一张卡片的实际 Token 消耗达到预算 × 1.2 且仍未通过验收 —— 停下，让 Orchestrator 决定是否拆卡或追加预算。
5. 需要外部凭据或系统（真实云 API Key / 独立 Ubuntu 机 / 签名密钥）—— 先停下。

---

## 8. 文件导航（按需读取，不要全量打开）

| 需求 | 只需要读这些文件 |
|---|---|
| 契约细节 | `contracts/<domain>/*.schema.json` + `contracts/openapi/ragforge-api-v1.yaml` |
| 架构原则 | 仅对应 ADR：`docs/02-architecture/adr/NNNN-*.md`（按编号精确打开） |
| 质量门禁 | `docs/03-delivery/DEFINITION_OF_DONE.md` 中与当前卡片对应的 1–2 节即可 |
| 测试策略 | `docs/04-quality/TEST_STRATEGY.md` 中与卡片范围相关的章节 |
| 安全规则 | `docs/06-security-compliance/SECURITY_BASELINE.md` 对应威胁条目（不读全文） |
| 许可证登记 | `docs/07-research/UPSTREAM_REUSE_REGISTER.md`（仅引入新依赖时） |
| 阶段证据/CI | 仅看 `tests/evidence/phaseX-*.json` 的 `summary` 字段或 `passed` 顶层字段，不展开 detail 数组 |

---

## 9. 更新与维护规则

- 本文件属于 **integration-sensitive 资产**，只允许 **Orchestrator Agent 或人类用户** 编辑；Worker Agent 只可读取，不可写入。
- 每次完成 1 批集成（1–3 张卡片合并到 main 且 CI 全绿后），Orchestrator 必须：
  1. 更新 §6 分派表的「状态 / 实际消耗 / 完成 SHA / 备注」。
  2. 如果工作包完成度发生跳变，更新 §1 的完成度列表与 §3 的依赖图。
  3. 在 §1 顶部写回最新的 main SHA 与 CI Run 链接。
  4. 不修改其他章节（除非发生了审计级纠正）。
- Worker Agent 如果发现本文件缺失或明显过期（例如 §6 的分派表与 main 提交不一致），必须先停下并通知 Orchestrator，不得凭记忆继续。
