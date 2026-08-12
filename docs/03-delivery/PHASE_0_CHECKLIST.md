# Phase 0 执行清单：竞品基准与许可证闸门

## 1. 实验资产

- [x] 建立 30–50 份公开/合成 benchmark corpus；实际 36 份，见 [`PHASE_0_BENCHMARK_RESULTS.md`](../08-records/phase-0/PHASE_0_BENCHMARK_RESULTS.md)。
- [x] 建立 30 问 question/relevance/reference manifest；实际 33 条，manifest 已固定 hash。
- [x] 固定 Ollama、embedding、reranker、RAGFlow、AnythingLLM 版本和镜像 digest；AnythingLLM 实际运行版本与许可证候选版本差异已明确记录。
- [x] 记录硬件、WSL/Docker、资源限额和数据 hash。
- [x] 明确所有样本可被本地实验和仓库保存；image-only PDF 的不可提取文本边界已记录。

## 2. RAGFlow 基准

- [x] 独立 Compose 启动，不复用 RAGForge volumes/network。
- [x] 记录安装步骤、服务和空载/峰值资源。
- [x] 导入 corpus，记录解析、分块、失败和人工纠正体验；36/36 入库，image-only PDF 为 0 chunks。
- [x] 运行 30 问 retrieval/generation，导出 33 条 case-level 结果。
- [x] 演练文档更新/删除、失败重试和重启；重传副本语义、删除回收和 re-parse 结果已记录。
- [x] 截图/笔记只保留必要产品证据，不复制不必要源码。

## 3. AnythingLLM 基准

- [x] 使用与 RAGFlow 尽可能一致的模型和 corpus；两者均使用同一本地 Ollama LLM/embedding。
- [x] 记录 workspace/权限/Provider/导入/引用体验。
- [x] 运行相同 30 问并导出 33 条 case-level 结果。
- [x] 演练 AnythingLLM chat context 的版本上传/移除、重启和模型切换；持久增量更新入口缺失、重复 basename 丢失和 RAGFlow 重传副本语义均已记录。

## 4. 比较和决策

- [x] 填写 [基准结果表](../07-research/GITHUB_BENCHMARK.md) 并保留 [`逐案例证据`](../08-records/phase-0/PHASE_0_BENCHMARK_RESULTS.md)。
- [x] 区分 retrieval、generation、资源和 UX，不做单一总分。
- [x] 每个候选借鉴项写 Build/Dependency/Selective reuse/Reference-only 结论。
- [x] 精确复核上游 commit 的 LICENSE/NOTICE 和目录级例外。
- [x] 更新 [复用登记表](../07-research/UPSTREAM_REUSE_REGISTER.md)。
- [x] 结论没有改变当前技术基线，因此不新增替代 ADR；Phase 1 仍采用模块化单体 + 独立 ingestion worker。

## 5. Phase 0 评审

- [x] 所有 TBD 结果都有证据，不用推测填数；失败项和不可比口径已显式标出。
- [x] 风险表更新概率、影响和新风险。
- [x] 追溯矩阵补充 Phase 1 测试 ID/入口。
- [x] 形成 [Phase 0 retrospective](../08-records/retrospectives/PHASE_0_RETROSPECTIVE.md)。
- [x] 项目负责人确认进入 Phase 1；状态记录已切换为 Phase 1 Ready。
