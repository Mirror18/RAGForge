# Phase 7 执行计划：实现对齐与 Linux 交付

- Version: `phase7-plan.v2`
- Status: `implementation-reconciliation`
- Code baseline: `f6b016840e946ea314cdaf4812c196dcea8ca491`
- Checklist: [`PHASE_7_CHECKLIST.md`](../../03-delivery/PHASE_7_CHECKLIST.md)

## 目标与边界

先消除代码与 MVP 需求之间的断点，再进行 Linux 发布验收。保持模块化单体 + 独立 ingestion worker；所有内容访问继续强制 `space_id`，云出境继续显式 opt-in，citation 继续来自结构化 provenance。AI Runtime 只可承担 OCR/rerank，不得演变成第二业务后端。

## 代码审计结论

| 区域 | 已实现代码 | 未实现或不一致 | 结论 |
|---|---|---|---|
| 身份/空间 | Session、CSRF、成员角色、用户/空间管理 | 无首个平台管理员 bootstrap | 干净部署无法进入平台管理旅程 |
| Provider | space-scoped connection/profile/route/binding 与 adapter | Editor 可登记 connection，与平台管理员 ownership 需求不一致；connection test 仅有 OpenAPI；verified capability 不控制发布 | 权限模型和 `PUBLISHED` 均未达到需求语义 |
| 摄取 | 上传、网页、revision/artifact/job、Worker pipeline | Git/local connector 未接 Server/Web/调度 | 不能宣称 Git 知识源用户旅程完成 |
| 问答 | material-backed retrieval、同步 generation、citation、answer/event persistence | provider 固定非 streaming；SSE 是结果事件读取 | 不能宣称真实流式回答/取消闭环 |
| Retrieval | Qdrant dense、RRF、parent expansion、lexical rerank | BM25 进程内；RERANK route 未调用；AI Runtime 空壳 | 重启与配置语义不满足部署要求 |
| 管理 | 用户/空间/模型/Prompt 页面、raw health link | feedback、审计/成本/聚合健康页面缺失 | PRD 管理闭环不完整 |
| 测试 | Java/contract/安全/评估资产丰富 | Web 无测试脚本；契约未校验 Controller 覆盖；本机 preflight 不可靠 | 当前候选不可据此判定全绿 |
| 部署 | core/app Compose、Server/Worker 非 root | Web root；应用 health/资源/capability/digest/Secret 仍不足 | 只能作为开发部署资产 |

## 执行顺序

| 工作包 | 状态 | 主要产物 | 完成条件 | 依赖 |
|---|---|---|---|---|
| P7-A 审计与任务冻结 | completed | 本计划、checklist、风险/追溯更新 | 任务由代码事实驱动，历史声明不作为完成证据 | 无 |
| P7-B 平台初始化与 Provider 闸门 | pending | admin bootstrap、connection test、verified publish | 干净数据库可安全初始化；未验证能力不可发布 | P7-A |
| P7-C 来源与 RAG 运行时对齐 | pending | Git source、streaming、durable lexical、rerank adapter | PRD 核心旅程与实际执行路径一致 | P7-B |
| P7-D 管理与自动化闭环 | pending | feedback/audit/health、Web tests、contract-implementation gate、preflight | 关键 UI/API/失败路径可自动回归 | P7-B/P7-C |
| P7-E 镜像与 Compose 加固 | pending | non-root Web、health、limits、digest、Secret | Definition of Done 可部署能力满足 | P7-D |
| P7-F Ubuntu/观测/升级验收 | pending | clean deploy、smoke、observability、upgrade/rollback evidence | 同一候选 SHA 和镜像 digest 可追溯 | P7-E |
| P7-G 供应链与阶段闭环 | pending | SBOM/Grype、public audit、retrospective、状态记录 | checklist 全满足；release 仍需单独批准 | P7-F |

## 2026-08-29 可重跑审计结果

- PASS：format、architecture、52 contract tests、Compose static validation、secret scan。
- BLOCKED：系统 `java` 为 21，但 Maven 绑定 JDK 8；显式改为 JDK 21 后 Server 执行 187 tests，其中 20 个 Testcontainers tests 因 Docker daemon 不可用报 error、1 个真实 Ollama 用例 skipped，Worker 因 reactor 中止未执行。
- BLOCKED：Node/npm 当前不在 PATH，Web typecheck/build/test 未在本轮执行；项目本身也没有 Web test script。
- NOT RUN：容器 build/up/health、真实业务 smoke、SBOM/Grype target image、Ubuntu、升级/回滚。

## 证据规则

- runtime 证据记录候选 SHA、镜像 digest、JDK/Maven/Node/Docker/Compose 版本、fixture 版本和结果。
- 证据只保存 hash、ID、计数、状态、耗时和脱敏错误，不保存 Secret、Cookie、raw prompt、文档正文或个人路径。
- 失败或环境阻塞必须原样记录；修复后生成新证据，不能覆盖为历史“已通过”。
- RAG、权限、出境、parser 或 tool 行为变化继续触发安全与评估复核。
