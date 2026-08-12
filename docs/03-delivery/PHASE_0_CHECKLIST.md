# Phase 0 执行清单：竞品基准与许可证闸门

## 1. 实验资产

- [ ] 建立 30–50 份公开/合成 benchmark corpus。
- [ ] 建立 30 问 question/relevance/reference manifest。
- [ ] 固定 Ollama、embedding、reranker、RAGFlow、AnythingLLM 版本和镜像 digest。
- [ ] 记录硬件、WSL/Docker、资源限额和数据 hash。
- [ ] 明确所有样本可被本地实验和仓库保存。

## 2. RAGFlow 基准

- [ ] 独立 Compose 启动，不复用 RAGForge volumes/network。
- [ ] 记录安装步骤、服务和空载/峰值资源。
- [ ] 导入 corpus，记录解析、分块、失败和人工纠正体验。
- [ ] 运行 30 问 retrieval/generation，导出 case-level 结果。
- [ ] 演练文档更新/删除、失败重试和重启。
- [ ] 截图/笔记只保留必要产品证据，不复制不必要源码。

## 3. AnythingLLM 基准

- [ ] 使用与 RAGFlow 尽可能一致的模型和 corpus。
- [ ] 记录 workspace/权限/Provider/导入/引用体验。
- [ ] 运行相同 30 问并导出 case-level 结果。
- [ ] 演练增量更新、删除、重启和模型切换。

## 4. 比较和决策

- [ ] 填写 [基准结果表](../07-research/GITHUB_BENCHMARK.md#5-待填写实验结果)。
- [ ] 区分 retrieval、generation、资源和 UX，不做单一总分。
- [ ] 每个候选借鉴项写 Build/Dependency/Selective reuse/Reference-only 结论。
- [ ] 精确复核上游 commit 的 LICENSE/NOTICE 和目录级例外。
- [ ] 更新 [复用登记表](../07-research/UPSTREAM_REUSE_REGISTER.md)。
- [ ] 如结论改变当前技术基线，新增替代 ADR。

## 5. Phase 0 评审

- [ ] 所有 TBD 结果都有证据，不用推测填数。
- [ ] 风险表更新概率、影响和新风险。
- [ ] 追溯矩阵补充 Phase 1 测试 ID。
- [ ] 形成 Phase 0 retrospective。
- [ ] 项目负责人确认进入 Phase 1。
