# RAGForge Agent 记忆（Agent Memory）

> ⚠️ **重定位声明（2026-08-30）**
>
> **本文件不再承担「项目状态 / 当前阶段 / 下一步」的功能**。这些信息曾写在本文件 §1–§4，随着项目推进已严重过期，会误导 Agent 产生不必要的纠错开销。
>
> 从即日起，Agent 读取顺序与权威真源如下：
>
> | 类别 | 权威文件 | 何时读取 |
> |---|---|---|
> | **日常运行状态（唯一入口）** | [`docs/08-records/AGENT_STATE_CARD.md`](docs/08-records/AGENT_STATE_CARD.md) | **任何 Agent 每轮启动都首先读取**；日常执行不读下面这些长文 |
> | **硬规则（不可变）** | [`AGENTS.md`](AGENTS.md) 的 Non-negotiable rules 章节 | 任何 Agent 启动时只读该节；其他章节按需进入 |
> | **任务预算 / 验收标准** | [`docs/08-records/TASK_BOARD.md`](docs/08-records/TASK_BOARD.md) | Orchestrator 分派时引用；Worker 通过 Ticket 继承摘要 |
> | **任务细化指令** | [`docs/08-records/tickets/*.yaml`](docs/08-records/tickets/) | Worker Agent 只读取分派给自己的那份 Ticket |
> | **角色专用提示词** | [`docs/03-delivery/MULTI_AGENT_LOOP_PROMPT.md`](docs/03-delivery/MULTI_AGENT_LOOP_PROMPT.md) | 只在启动对应角色的 Agent 时一次性读取对应章节 |
> | **治理 / 阶段 / 证据级权威** | [`docs/08-records/PROJECT_STATUS.md`](docs/08-records/PROJECT_STATUS.md) + 各阶段 CHECKLIST / EXECUTION_PLAN | 只在审计 / 阶段复盘 / 发布验收时读取；日常执行不得打开全文 |
>
> 若在 `AGENT_STATE_CARD.md` 与 `PROJECT_STATUS.md` 之间发现冲突，**一律以 PROJECT_STATUS 为准**，并立即建议 Orchestrator 回写修正状态卡。

---

## 1. 本文件的唯一作用：跨会话沉淀「经验」，不沉淀「状态」

只把下面这些内容写回本文件：
- 踩坑记录（比如 Python 是 uv shim、Maven 默认绑 JDK 8、Docker Desktop npipe 与 Testcontainers 的兼容）
- 值得复用的「决策习惯」（例如「新增 RERANK 能力前，先用 connection probe 的 UNSUPPORTED_CAPABILITY 测试闸门」）
- 特定目录的历史所有权决定（不要写状态，要写规则，例如 `apps/ai-runtime` 只能承载 OCR/rerank）

**不要把这些东西写进本文件**：
- 任何「当前阶段 / 进行中任务 / 完成 SHA / 最新 CI」字段 —— 请写回 [`AGENT_STATE_CARD.md`](docs/08-records/AGENT_STATE_CARD.md)
- 可从 `AGENTS.md` 直接读到的硬规则（不要复制粘贴，造成双真源）
- 可从 `TASK_BOARD.md` 直接读到的卡片清单与预算
- 任何个人信息、凭证、真实 Obsidian 内容、原始客户提示词

---

## 2. 跨会话经验沉淀（Session-wise Lessons Learned）

> 按日期倒序。条目尽量短，能一句话说完就别写一段。

### 2026-08-30 · Agent 效率改造

- **MEMORY.md 自身治理**：已把「状态」职责剥离到 `AGENT_STATE_CARD.md`。今后如果看到新 Agent 从 MEMORY.md 找「当前阶段」，要立刻纠正。
- **Token 浪费的 6 个常见陷阱**（已在 AGENTS.md §「Agent Token 效率协议」固化）：
  1. 重复读长文档（PROJECT_STATUS / ROADMAP / DoD / TEST_STRATEGY 全文）
  2. Worker 没有精确投喂，自行 Grep/Search 全仓
  3. 证据 / 测试输出 >50 行的长 JSON 直接贴到回报
  4. Orchestrator 同时做「审计 + 实现 + 集成」三种上下文
  5. 任务粒度过大（如「完成 Phase 7」），一张卡片吃掉 50k+ tokens 还验收不了
  6. MEMORY 与 PROJECT_STATUS 冲突，导致来回纠错
- **新的三角色模式**：日常执行只开 Orchestrator；需要全仓审计时单独开 Audit Agent；每张卡片通过 Ticket 启动 Worker。三者的上下文完全不混合。
- **预算护栏**：单张卡片预算的 1.2× 是硬性停工线。不要为了「把它做完」而继续烧 Token。

### 2026-08-15（遗留条目，保留为经验）

- **环境坑 1**：Windows 下 `python` 命令如果是 uv shim 且 `UV_CACHE_DIR` 有权限问题，contract 测试脚本执行会失败；解决方式是显式设置 `UV_CACHE_DIR=$env:TEMP\uv-cache-pN` 再 `uv run --no-project --python 3.12 python ...`。
- **环境坑 2**：本机 `java -version` 显示 JDK 21 不等于 Maven 绑定 JDK 21；用 `mvn -v` 检查 runtime；否则会出现 Spring Boot 3.5.x 编译报错。P7Q-01 已在 TASK_BOARD 中记录需要统一 preflight。
- **环境坑 3**：Testcontainers 在 Windows 上需要 Docker Desktop 实际运行；数据库集成测试失败时，先查 Docker daemon，而不是代码。
- **提交规则记忆**：中文 Conventional Commit `<type>(<scope>): <中文摘要>`；type/scope 保持英文；不写「更新代码 / 处理问题 / wip / final」。
- **安全记忆**：高风险动作必须显式用户审批（接受 ADR / 接受许可证 / 云出境开启 / 生产迁移 / 创建 release），任何 Agent 都不能自作主张。

---

## 3. 更新日志

- 2026-08-30：**定位重写** —— 删除 §1–§4（Facts / 非协商规则 / 文档导航 / 当前状态与下一步）与 §6 假设/开放问题的详细正文；改为「状态指针 + 经验沉淀」双结构，以消除状态双真源与 Token 浪费。历史会话记录保留在 §2 作为经验。对应 AGENT_STATE_CARD.md / TASK_BOARD.md 与 tickets/ 目录一并落地。
- 2026-08-15：初始化本记忆文件（首次 init）。当日后续条目的「状态」内容均已失效，已迁移至 `docs/08-records/AGENT_STATE_CARD.md`。
