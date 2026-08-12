# GitHub 成熟 RAG 项目调研与借鉴计划

> 调研日期：`2026-08-12`。本记录使用官方 GitHub 仓库的 release/tag、解引用 commit 和同一 SHA 下的 LICENSE/NOTICE 文件核验。版本、目录和许可证可能变化；真正引入时仍须重新复核。精确版本与许可证闸门详见[上游复用登记表](UPSTREAM_REUSE_REGISTER.md)。

## 1. 结论先行

RAGForge 不采用 Fork 或换皮路线。Phase 0 的使用边界分为：

1. **候选正式依赖**：Spring AI、Spring AI Alibaba Extensions；当前只完成版本和许可证核验，未添加依赖。
2. **候选开发/外部服务**：Promptfoo 作为 dev/CI 工具，Langfuse 作为可选 OTel/API 服务；当前未引入或部署。
3. **选择性借鉴候选**：RAGFlow 只借鉴 parent-child chunking 和 retrieval-test 思路；当前不复制代码。
4. **reference-only**：AnythingLLM、Dify、FastGPT、MaxKB、Open WebUI 只观察产品/交互行为；Dify/FastGPT/MaxKB/Open WebUI 明确拒绝代码复用。

## 2. 固定版本与许可证核验

| 项目 | 精确 release/tag；commit SHA | SPDX license / scope | 官方 LICENSE / NOTICE（同一 SHA） | RAGForge 决策 |
|---|---|---|---|---|
| [RAGFlow](https://github.com/infiniflow/ragflow) | `v0.26.4`；`cb93883f3f8c975eecb2fed81210effeb3bdb06f` | Apache-2.0 | [LICENSE](https://github.com/infiniflow/ragflow/blob/cb93883f3f8c975eecb2fed81210effeb3bdb06f/LICENSE)；无根 `NOTICE` | selective reuse 候选；只作参考，未经审批不复制 |
| [AnythingLLM](https://github.com/Mintplex-Labs/anything-llm) | `v1.15.0`；`70e0d2eb1dcb08cbb18a44b927d94f8667f57a7f` | MIT | [LICENSE](https://github.com/Mintplex-Labs/anything-llm/blob/70e0d2eb1dcb08cbb18a44b927d94f8667f57a7f/LICENSE)；无根 `NOTICE` | reference-only；不复制源码 |
| [Spring AI](https://github.com/spring-projects/spring-ai) | `v2.0.0`；`ef502dab692e26b953a75be4029dba7f1acdc88c` | Apache-2.0 | [LICENSE.txt](https://github.com/spring-projects/spring-ai/blob/ef502dab692e26b953a75be4029dba7f1acdc88c/LICENSE.txt)；无根 `NOTICE` | dependency 候选；当前未引入 |
| [Spring AI Alibaba Extensions](https://github.com/spring-ai-alibaba/spring-ai-extensions) | `v1.1.2.3`；`9ca462036d783f3069645ee0efaf925b5f9e2295` | Apache-2.0 | [LICENSE](https://github.com/spring-ai-alibaba/spring-ai-extensions/blob/9ca462036d783f3069645ee0efaf925b5f9e2295/LICENSE)；无根 `NOTICE` | dependency 候选；逐模块复核，当前未引入 |
| [Promptfoo](https://github.com/promptfoo/promptfoo) | `promptfoo-v0.119.13`；`d1419964849e897b61e3871af8d009fc217be93e` | MIT | [LICENSE](https://github.com/promptfoo/promptfoo/blob/d1419964849e897b61e3871af8d009fc217be93e/LICENSE)；无根 `NOTICE` | dependency 候选；仅 dev/CI，当前未引入 |
| [Langfuse](https://github.com/langfuse/langfuse) | `v4.9.0`；`537cd0181d97926437f84be8e6f275772d9819ca` | MIT（根目录 core/API/OTel）；`ee/` 独立许可，第三方组件各自适用 | [LICENSE](https://github.com/langfuse/langfuse/blob/537cd0181d97926437f84be8e6f275772d9819ca/LICENSE)、[ee/LICENSE](https://github.com/langfuse/langfuse/blob/537cd0181d97926437f84be8e6f275772d9819ca/ee/LICENSE)；无根 `NOTICE` | optional service 候选；只评估 OTel/API，禁止 EE 代码复用 |
| [Dify](https://github.com/langgenius/dify) | `1.16.1`；`6f8ed69ee15f9a2e7189ca066275e973d091d1e9` | `LicenseRef-Dify-Modified-Apache-2.0` | [LICENSE](https://github.com/langgenius/dify/blob/6f8ed69ee15f9a2e7189ca066275e973d091d1e9/LICENSE)；无根 `NOTICE` | reference-only / rejected-for-code；不复制 |
| [FastGPT](https://github.com/labring/FastGPT) | `v4.15.7`；`ecac36283bcb37196d2b42ddb5bddaa5af29d59a` | `LicenseRef-FastGPT-Modified-Apache-2.0` | [LICENSE](https://github.com/labring/FastGPT/blob/ecac36283bcb37196d2b42ddb5bddaa5af29d59a/LICENSE)；无根 `NOTICE` | reference-only / rejected-for-code；不复制 |
| [MaxKB](https://github.com/1Panel-dev/MaxKB) | `v2.10.5-lts`；`01b21db88145278d98bf5e9bd55e6abd6b3aad43` | GPL-3.0-only | [LICENSE](https://github.com/1Panel-dev/MaxKB/blob/01b21db88145278d98bf5e9bd55e6abd6b3aad43/LICENSE)；无根 `NOTICE` | reference-only / rejected-for-core-code；不复制 |
| [Open WebUI](https://github.com/open-webui/open-webui) | `v0.11.0`；`f9590b8017199e56d5e953657e6498e3cef1d246` | `LicenseRef-Open-WebUI-Custom`；历史代码按文件/commit 可能为 MIT/BSD-3-Clause | [LICENSE](https://github.com/open-webui/open-webui/blob/f9590b8017199e56d5e953657e6498e3cef1d246/LICENSE)、[LICENSE_NOTICE](https://github.com/open-webui/open-webui/blob/f9590b8017199e56d5e953657e6498e3cef1d246/LICENSE_NOTICE)、[LICENSE_HISTORY](https://github.com/open-webui/open-webui/blob/f9590b8017199e56d5e953657e6498e3cef1d246/LICENSE_HISTORY)；无根 `NOTICE` | reference-only / rejected-for-code；不复制 |

说明：`LicenseRef-*` 用于标记官方许可证包含项目特有附加条件或多许可证边界，不把它们伪装成纯 Apache-2.0/BSD/MIT。无根 `NOTICE` 是在上述固定 SHA 下的核验结果，不代表未来引入时无需保留传递依赖的 Notice。

## 3. 可借鉴设计与边界

### 3.1 RAGFlow

- parent-child chunking 可启发“小 child 用于检索、较大 parent 用于补足上下文”的设计；RAGForge 仍需保留自己的 chunk/provenance 契约。
- Retrieval Test 可启发召回片段和参数的可见性；不得把上游测试代码直接复制到 RAGForge。
- 参考资料：[child chunking guide](https://github.com/infiniflow/ragflow/blob/cb93883f3f8c975eecb2fed81210effeb3bdb06f/docs/guides/dataset/configure_child_chunking_strategy.md)、[retrieval test guide](https://github.com/infiniflow/ragflow/blob/cb93883f3f8c975eecb2fed81210effeb3bdb06f/docs/guides/dataset/run_retrieval_test.md)。

### 3.2 Spring AI Alibaba Extensions

Document readers 可作为 Markdown/PDF/Obsidian 等适配的候选依赖；不能把公开 reader 当成完整企业同步系统。RAGForge 仍需自行定义 include/exclude、Git checkpoint、增删改同步、路径规范、幂等和 provenance 契约，并逐模块核对兼容性与传递许可证。

### 3.3 Promptfoo 与 Langfuse

- Promptfoo 只考虑在 CI 中运行固定 prompt/model/provider 矩阵和安全评估；评估集、结果和最终指标的所有权及保留策略属于 RAGForge 自己。
- Langfuse 只考虑通过 OTel/API 作为可拔插观测消费者；不能复制 `ee/` 或把整个仓库视为 MIT。接入还需满足空间级云数据出境 opt-in，不能从本地路由静默切换到云路由。

### 3.4 reference-only 项目

Dify、FastGPT、MaxKB、Open WebUI 只用于产品行为和架构观察。它们的附加许可、GPL 或品牌/多许可证边界不进入 RAGForge 核心代码；RAGForge 不复制这些项目的源码、UI、logo、copyright 或许可证文本。

## 4. Phase 0 基准方法

| 维度 | 记录 |
|---|---|
| 数据 | 30–50 份公开/合成文档，覆盖 Markdown/YAML/wikilink、PDF、DOCX、表格、长文、扫描件、重复标题和冲突版本 |
| 问题 | 30 个事实、结构、多跳、无答案、冲突、注入和权限边界问题 |
| 检索 | Recall@10、MRR@10、过滤、rerank 和可调参数 |
| 回答 | faithfulness、citation、abstention、stream/cancel |
| 治理 | 用户、空间、权限、provider、出境和审计 |
| 运维 | logs/metrics/traces、备份、升级和资源使用 |

在 Phase 0 实际运行前，不把 `TBD` 替换成推测数字；不同系统无法使用完全相同 embedding/reranker 时，分别报告默认最佳实践和尽可能同模型两组结果。
