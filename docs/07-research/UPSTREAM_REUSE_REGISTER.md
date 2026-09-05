# 上游复用登记表

## 1. 记录规则

本表是 Phase 0 的许可证闸门记录，不表示 RAGForge 已经引入任何第三方依赖或复制第三方源码。状态含义如下：

- `CANDIDATE`：已完成固定版本与许可证核验，但尚未批准引入、依赖或源码复制。
- `REJECTED`：仅允许阅读公开产品/架构行为；拒绝代码复用，RAGForge 不复制其源码。
- `APPROVED`、`IN_USE`、`REMOVED`：保留给后续真实引入流程；本表当前没有这些状态。

核验日期：`2026-08-12`。版本字段同时记录官方 release/tag 和可审计 commit SHA；带注释 tag 的项目记录其解引用后的 commit。许可证链接均指向同一 SHA 的官方仓库文件，不使用 `main` 或其他浮动分支作为精确证据。`NOTICE` 不存在时明确记录为“未发现”，不得以空白代替核验结果。

## 2. Phase 0 登记

| ID | 上游仓库 | 精确 release/tag 与 commit SHA | SPDX license / 适用范围 | 同一 SHA 的官方 LICENSE / NOTICE 位置 | RAGForge use mode | 当前决策 | 义务、边界与下一步风险 |
|---|---|---|---|---|---|---|---|
| UR-001 | [Spring AI](https://github.com/spring-projects/spring-ai) | `v2.0.0`；`ef502dab692e26b953a75be4029dba7f1acdc88c`（解引用 commit） | `Apache-2.0` | [LICENSE.txt](https://github.com/spring-projects/spring-ai/blob/ef502dab692e26b953a75be4029dba7f1acdc88c/LICENSE.txt)；根目录未发现 `NOTICE` | dependency（候选 Maven 依赖） | `CANDIDATE`；仅作为 Java AI 抽象的候选依赖，当前未引入 | 正式引入前重新核对 artifact、BOM、模块许可证、传递依赖和 SBOM；分发时保留 Apache copyright/许可声明；版本升级必须重新锁定 SHA 并复核。 |
| UR-002 | [Spring AI Alibaba Extensions](https://github.com/spring-ai-alibaba/spring-ai-extensions) | `v1.1.2.3`；`9ca462036d783f3069645ee0efaf925b5f9e2295`（解引用 commit） | `Apache-2.0` | [LICENSE](https://github.com/spring-ai-alibaba/spring-ai-extensions/blob/9ca462036d783f3069645ee0efaf925b5f9e2295/LICENSE)；根目录未发现 `NOTICE` | dependency（候选 document-reader 模块） | `CANDIDATE`；逐模块评估，当前未引入 | 不能把仓库级许可证替代模块及传递依赖审计；正式引入前核对选定 reader 的 artifact、依赖树、兼容性和 SBOM，并保留 Apache notice/copyright。 |
| UR-003 | [RAGFlow](https://github.com/infiniflow/ragflow) | `v0.26.4`；`cb93883f3f8c975eecb2fed81210effeb3bdb06f` | `Apache-2.0` | [LICENSE](https://github.com/infiniflow/ragflow/blob/cb93883f3f8c975eecb2fed81210effeb3bdb06f/LICENSE)；根目录未发现 `NOTICE` | selective reuse（仅在批准后） | `CANDIDATE`；Phase 0 仅作 reference，未批准复制任何代码 | 优先自行实现 parent-child chunking 和 retrieval-test 思路；若确需复制，必须逐文件记录原路径、copyright、SHA、修改说明和测试，并在复制前取得批准；正式复用前重新核对版本与目录许可证。 |
| UR-004 | [AnythingLLM](https://github.com/Mintplex-Labs/anything-llm) | `v1.15.0`；`70e0d2eb1dcb08cbb18a44b927d94f8667f57a7f` | `MIT` | [LICENSE](https://github.com/Mintplex-Labs/anything-llm/blob/70e0d2eb1dcb08cbb18a44b927d94f8667f57a7f/LICENSE)；根目录未发现 `NOTICE` | reference | `CANDIDATE`；只借鉴 workspace/provider UX，不复制源码 | 若未来复制实质性片段，须保留 MIT copyright 和许可文本；当前以自有实现为边界，不把产品行为观察误记为依赖或源码复用。 |
| UR-005 | [Promptfoo](https://github.com/promptfoo/promptfoo) | `promptfoo-v0.119.13`；`d1419964849e897b61e3871af8d009fc217be93e` | `MIT` | [LICENSE](https://github.com/promptfoo/promptfoo/blob/d1419964849e897b61e3871af8d009fc217be93e/LICENSE)；根目录未发现 `NOTICE` | dependency（候选 dev/CI 工具） | `CANDIDATE`；只考虑评估/CI 环境，当前未引入生产核心 | 若加入 CI，需固定 CLI/package 版本、记录 SBOM 和运行配置；若分发其代码或复制片段，保留 MIT copyright/许可；不得把 promptfoo 运行结果或数据集所有权转移给上游。 |
| UR-006 | [Langfuse](https://github.com/langfuse/langfuse) | `v4.9.0`；`537cd0181d97926437f84be8e6f275772d9819ca` | `MIT`（根目录 core/API/OTel 边界）；`ee/` 为独立许可范围，第三方组件按其原许可证 | [LICENSE](https://github.com/langfuse/langfuse/blob/537cd0181d97926437f84be8e6f275772d9819ca/LICENSE)；[ee/LICENSE](https://github.com/langfuse/langfuse/blob/537cd0181d97926437f84be8e6f275772d9819ca/ee/LICENSE)；根目录未发现 `NOTICE` | optional service（OTel/API 集成候选） | `CANDIDATE`；仅评估 MIT core/API/OTel 边界，禁止复制 EE/商业许可目录，当前未部署 | 正式接入前固定服务版本并审查目录范围、第三方组件许可证、SBOM、数据出境和空间级 opt-in；不得以“仓库整体 MIT”覆盖 `ee/` 或第三方组件义务；云路由不能静默替代本地路由。 |
| UR-007 | [Dify](https://github.com/langgenius/dify) | `1.16.1`；`6f8ed69ee15f9a2e7189ca066275e973d091d1e9` | `LicenseRef-Dify-Modified-Apache-2.0`（基于 Apache-2.0 的官方附加条件，非纯 Apache-2.0） | [LICENSE](https://github.com/langgenius/dify/blob/6f8ed69ee15f9a2e7189ca066275e973d091d1e9/LICENSE)；根目录未发现 `NOTICE` | reference | `REJECTED` for code；仅产品/架构参考 | 不复制源码、前端、logo/copyright 或许可证文本；任何未来代码引入都必须重新取得法律与商业许可结论，不能把附加条件当作普通 Apache-2.0。 |
| UR-008 | [FastGPT](https://github.com/labring/FastGPT) | `v4.15.7`；`ecac36283bcb37196d2b42ddb5bddaa5af29d59a` | `LicenseRef-FastGPT-Modified-Apache-2.0`（官方 LICENSE 含附加条件，非纯 Apache-2.0） | [LICENSE](https://github.com/labring/FastGPT/blob/ecac36283bcb37196d2b42ddb5bddaa5af29d59a/LICENSE)；根目录未发现 `NOTICE` | reference | `REJECTED` for code；仅知识库/流程产品行为参考 | 不复制源码、品牌、UI 或附加许可文本；如未来考虑任何代码复用，必须重新审查 SaaS/品牌/商业限制并单独批准。 |
| UR-009 | [MaxKB](https://github.com/1Panel-dev/MaxKB) | `v2.10.5-lts`；`01b21db88145278d98bf5e9bd55e6abd6b3aad43` | `GPL-3.0-only`（官方仓库 GPL v3） | [LICENSE](https://github.com/1Panel-dev/MaxKB/blob/01b21db88145278d98bf5e9bd55e6abd6b3aad43/LICENSE)；根目录未发现 `NOTICE` | reference | `REJECTED` for core code；仅产品行为参考 | 不复制 GPL 源码进入 RAGForge 核心或专有兼容仓库；若未来改变边界，必须先完成独立 copyleft/分发法律评估。 |
| UR-010 | [Open WebUI](https://github.com/open-webui/open-webui) | `v0.11.0`；`f9590b8017199e56d5e953657e6498e3cef1d246` | `LicenseRef-Open-WebUI-Custom`；历史代码按 `LICENSE_HISTORY` 可能为 MIT/BSD-3-Clause，当前代码含品牌限制 | [LICENSE](https://github.com/open-webui/open-webui/blob/f9590b8017199e56d5e953657e6498e3cef1d246/LICENSE)；[LICENSE_NOTICE](https://github.com/open-webui/open-webui/blob/f9590b8017199e56d5e953657e6498e3cef1d246/LICENSE_NOTICE)；[LICENSE_HISTORY](https://github.com/open-webui/open-webui/blob/f9590b8017199e56d5e953657e6498e3cef1d246/LICENSE_HISTORY)；未发现根 `NOTICE` | reference | `REJECTED` for code；仅本地模型 UI 行为参考 | 不复制源码、品牌或界面；若未来研究历史代码，也必须按文件和 commit 追踪许可证，不能用仓库当前自定义许可证概括全部内容。 |

## 3. 源码复制审批模板

只有明确批准后才能复制源码；当前没有任何批准记录。

```text
Reuse ID:
Business/technical reason:
Why dependency/API is insufficient:
Repository URL:
Release/tag and commit SHA:
Original paths:
License file URL at the same commit:
NOTICE/copyright obligations:
Target paths:
Modifications:
Upgrade/patch owner:
Security review:
Approval date:
```

## 4. 当前声明

截至 `2026-08-12`，RAGForge 根仓库没有引入上述依赖、没有 vendored third-party source，也没有复制第三方许可证全文。正式引入前必须重新核对上游精确版本、文件级许可证、传递依赖/SBOM、Notice/copyright 和适用的商业/分发义务，并同步更新 `THIRD_PARTY_NOTICES.md`。

## 5. 2026-09-05 架构研究补充（reference-only）

版本：`knowledge-architecture-reference.v1`。本轮仅阅读公开官方说明并独立编写设计，没有引入依赖、复制源码或接受新许可证。以下观察不覆盖 §2 历史精确版本登记和已有接受/拒绝结论；证据与读取范围见[增量调研](2026-09-05-knowledge-architecture-benchmark.md)。

| 参考 ID | 上游 | 范围 | 采用程度 | 审批状态 |
|---|---|---|---|---|
| AR-20260905-01 | [RAGFlow](https://github.com/infiniflow/ragflow) | 解析检查、摄取配置与检索测试行为 | reference-only；沿用 UR-001 复用闸门 | 无新增接受 |
| AR-20260905-02 | [Dify](https://github.com/langgenius/dify) | Datasource 与 Pipeline 职责分工 | reference-only；UR-007 代码拒绝结论保持；[官方 LICENSE](https://github.com/langgenius/dify/blob/main/LICENSE) 含附加条件 | 无新增接受 |
| AR-20260905-03 | [Onyx](https://github.com/onyx-dot-app/onyx) | 来源同步状态、refresh/prune、权限处理边界 | reference-only；[官方连接器说明](https://docs.onyx.app/admins/connectors/overview) 将权限同步标为 EE，不将其视为可直接复制的 MIT 能力 | 未申请许可证接受 |
| AR-20260905-04 | [Haystack](https://github.com/deepset-ai/haystack) | 类型化组件与受控执行的设计启发 | reference-only；不引入 Python 框架或其实现；本次不是文件级许可证验收 | 未申请许可证接受 |
