# 开源知识库架构对标记录

- 记录版本：`knowledge-architecture-benchmark.v1`。
- 检索日期：2026-09-05；用途：设计参考，全部 `reference-only`。
- 结论：借鉴可检查的解析产物、获取与处理边界、同步生命周期和组件契约；由 RAGForge 自行设计有限阶段与统一检索快照。
- 本记录没有引入依赖、复制源码、接受许可证或证明性能收益。设计入口为[演进提案](../02-architecture/ARCHITECTURE_EVOLUTION.md)。

## 1. 来源与版本证据

以下是本轮研究包中的官方页面实读结果。GitHub API 遇到 403 限流；SHA 通过 `git ls-remote HEAD` 只读确认。HEAD 探测只证明当时的引用身份，**不代表固定 SHA 下全文已经读取或审计**；固定 SHA 页面批量读取未成功。文档站与 branch 页面可变，也不能假定与该 HEAD 一一对应。

| 项目 | 仓库及观察到的 HEAD | 实读页面 |
|---|---|---|
| RAGFlow | [infiniflow/ragflow](https://github.com/infiniflow/ragflow)，`0c28d59ea1d362d9b6aa7481eed48c7fd9a95f0b` | [Configure knowledge base](https://github.com/infiniflow/ragflow/blob/main/docs/guides/dataset/configure_knowledge_base.md) |
| Dify | [langgenius/dify](https://github.com/langgenius/dify)，`00e578606715a9da34488608edee8c68d4ef4893` | [Datasource plugin](https://docs.dify.ai/en/develop-plugin/dev-guides-and-walkthroughs/datasource-plugin) |
| Onyx | [onyx-dot-app/onyx](https://github.com/onyx-dot-app/onyx)，`06aa2b09cc4aa5135fa2627e5235814e996f1514` | [Connectors overview](https://docs.onyx.app/admins/connectors/overview) |
| Haystack | [deepset-ai/haystack](https://github.com/deepset-ai/haystack)，`82da3adc2fac4675b80ff5573b790ec07113697b` | [Pipelines](https://docs.haystack.deepset.ai/docs/pipelines)；页面显示文档版本 3.1，不据此声称最新 release |

## 2. 官方事实与本项目推论

### RAGFlow

官方事实：页面提供分块模板配置、解析结果检查和干预、检索测试及摄取 pipeline 的说明。

项目推论：RAGForge 已有 ParsedArtifact、ParseReport、Chunk Studio 与版本化索引；值得补的是可验证 lineage 和质量判定贯穿发布的闭环，不能把现有模型重新命名后当作新能力。

建议：先补产物清单与质量证据，再通过离线评估考虑改进生产 handler 的分块策略；不复制上游 parser 或 UI。

### Dify

官方事实：Datasource plugin 是 Knowledge Pipeline 的内容入口，manifest、provider 与 datasources 分工明确。

项目推论：获取内容和处理内容需要清楚的版本契约，但已有 SourceConnector 应继续承载来源身份和 checkpoint。

建议：只借鉴职责边界，不引入其工作流平台。[官方 LICENSE](https://github.com/langgenius/dify/blob/main/LICENSE)为附额外条件的 Apache 2.0 修改文本，涉及多租户与前端标识；沿用 UR-007 的 `reference-only`、拒绝源码复用结论，不作新许可证接受。

### Onyx

官方事实：连接器文档区分定期同步、refresh/prune 与索引任务状态；权限同步明确属于 Enterprise Edition，不能标作免费 MIT 能力。

项目推论：RAGForge 应区分暂停同步、同步错误与真实删除，并把当前权限作用于历史证据和派生内容；不是移植文档级 ACL。

建议：细化已有 Deletion Job 与墓碑恢复语义。许可证范围参考[官方 LICENSE](https://github.com/onyx-dot-app/onyx/blob/main/LICENSE)：`ee` 属 enterprise 范围，其他范围适用根许可说明；本记录不接受任何代码复用许可。

### Haystack（框架补充）

官方事实：Pipeline 由组件构成有向多重图，支持分支、有限循环及异步并发。它是框架参考，不是与前三者同类的完整知识库产品。

项目推论：组件边界和显式依赖有助于可测试执行，但 RAGForge 当前只需要受控有限阶段。

建议：保留 Java application ports；不新增 Python 业务后端、通用图执行器或任意循环配置。

## 3. 采用范围与未决问题

| 建议 | 现行基础 | 后续决策 |
|---|---|---|
| 阶段执行与安全重放 | PipelineVersion、阶段记录、幂等、独立 Worker | 最小状态字段、租约条件提交契约 |
| 可验证产物与质量发布 | 解析报告、candidate validation、原子 pointer | 质量策略版本及阈值校准 |
| 统一执行快照 | RetrievalProfileVersion、混合检索、Evidence Bundle | 三入口最小公共参数和快照保留 |
| 历史再授权 | 空间 RBAC、引用鉴权、删除与墓碑 | 派生回答和缓存的拒读语义与安全测试 |

以上建议见 [ADR-0013](../02-architecture/adr/0013-versioned-knowledge-execution.md)，均为 Proposed。后续若需要源码复用或新增依赖，必须重新固定对应源码版本、审查具体路径及许可证，并更新[复用登记](UPSTREAM_REUSE_REGISTER.md)。本记录中的 HEAD 不能代替该审批证据。
