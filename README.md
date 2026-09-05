# RAGForge

RAGForge 是一个面向企业内部、多用户知识空间的商业级 RAG 学习工程。目标是把数据接入、解析、分块、索引、检索、生成、引用、安全、评估、部署和复盘做成可追踪的工程闭环。

## 先看这里

如果你不知道下一步做什么，先读 [`docs/00-governance/START_HERE.md`](docs/00-governance/START_HERE.md)。当前主线在 Phase 7 P2，P0/P1 功能与回归门禁已完成，P7D-03 因缺少独立 Ubuntu 24.04 环境而阻塞；不要把历史记录或页面文案当作部署验收证据。

Agent 日常入口固定为：

1. [状态卡](docs/08-records/AGENT_STATE_CARD.md)
2. [任务板](docs/08-records/TASK_BOARD.md)
3. 对应的 [Worker Ticket](docs/08-records/tickets/)

## 工程结构

```text
RAGForge/
├── frontend/ragforge-web/   # Vue SPA；前端源码、前端测试和启动说明集中在这里
├── backend/                 # 后端运行面；Server、Worker、AI Runtime 分开部署
│   ├── server/
│   ├── ingestion-worker/
│   └── ai-runtime/
├── contracts/               # OpenAPI、事件和跨语言 schema
├── config/                  # 非敏感配置、Prompt 和模型 Profile 模板
│   └── private/              # 本机私密配置目录；仅保留 README，不提交真实内容
├── deploy/                  # Docker Compose、Dockerfile 和运行资产
├── docs/                    # 治理、产品、架构、交付、质量、安全、运维和记录
├── fixtures/                # 可公开的文档、评估和安全样本
├── libs/                    # 受约束的跨应用复用库
├── scripts/                 # 开发、CI、评估和运维脚本
├── tests/                   # 跨应用、契约、验收和证据
├── third_party/             # 仅存放已过许可证闸门的第三方源码
└── licenses/                # 已批准复用组件的许可证文本
```

关键边界：模块化单体由 `backend/server` 负责业务真相；`backend/ingestion-worker` 独立运行但共享契约；`backend/ai-runtime` 只承载 OCR/rerank 等窄职责；`frontend/ragforge-web` 只负责交互，不构成安全边界；所有租户内容查询和 mutation 都强制 `space_id`；云端出境必须按空间显式授权；回答引用使用结构化 provenance。

目录取舍、职责矩阵、启动顺序和文档阅读顺序见[项目手册](docs/00-governance/PROJECT_MANUAL.md)。

## 文档入口

- [文档索引](docs/README.md)
- [项目手册](docs/00-governance/PROJECT_MANUAL.md)
- [项目章程](docs/00-governance/PROJECT_CHARTER.md)
- [总体架构](docs/02-architecture/ARCHITECTURE.md)
- [架构演进与 ADR-0013](docs/02-architecture/ARCHITECTURE_EVOLUTION.md)
- [交付路线图](docs/03-delivery/ROADMAP.md)
- [Phase 7 清单](docs/03-delivery/PHASE_7_CHECKLIST.md)
- [测试策略](docs/04-quality/TEST_STRATEGY.md)
- [部署与运行](docs/05-operations/DEPLOYMENT.md)
- [安全基线](docs/06-security-compliance/SECURITY_BASELINE.md)
- [风险登记表](docs/08-records/RISK_REGISTER.md)
- [当前项目状态](docs/08-records/PROJECT_STATUS.md)

## 本地开发入口

前置条件：Docker Desktop、Java 21、Maven、Node.js，以及本机 Ollama（如需真实本地模型验收）。

```powershell
.\scripts\dev\start-local.bat
```

只启动基础设施：

```powershell
python scripts/dev/core.py up
python scripts/dev/core.py health
```

前端入口见 [`frontend/README.md`](frontend/README.md)，后端入口见 [`backend/README.md`](backend/README.md)，容器化应用入口见 [`deploy/README.md`](deploy/README.md)，脚本边界见 [`scripts/README.md`](scripts/README.md)。

直接入口：[`deploy/docker/Dockerfile`](deploy/docker/Dockerfile) · [`deploy/compose/compose.yaml`](deploy/compose/compose.yaml) · [`scripts/dev/start-local.bat`](scripts/dev/start-local.bat)

## 交付规则

根目录 [`AGENTS.md`](AGENTS.md) 是硬规则真源；[任务板](docs/08-records/TASK_BOARD.md) 是任务与预算真源；[状态卡](docs/08-records/AGENT_STATE_CARD.md) 是日常运行快照；`PROJECT_STATUS.md` 只在审计、阶段闭环和发布治理时作为证据级记录。不要提交凭据、个人 Obsidian 内容、生产数据、raw prompt、构建产物或未经批准的第三方源码。
