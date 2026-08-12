# Phase 0 复盘：竞品基准与许可证闸门

> 复盘日期：2026-08-12
>
> 关联证据：[`PHASE_0_BENCHMARK_RESULTS.md`](../phase-0/PHASE_0_BENCHMARK_RESULTS.md)、[`GITHUB_BENCHMARK.md`](../../07-research/GITHUB_BENCHMARK.md)、[`PHASE_0_CHECKLIST.md`](../../03-delivery/PHASE_0_CHECKLIST.md)

## 1. 事实

- 已建立 36 份可复现基准文档和 33 条不可变问题集；生成器、manifest、dataset index 和验证测试均在仓库中。
- RAGFlow `v0.26.4` 与 AnythingLLM 独立启动并使用同一 Ollama LLM/embedding 配置完成真实 UI 实验。
- RAGFlow retrieval 为 Recall@10 0.879、MRR@10 0.971；AnythingLLM 33/33 生成回答，平均 UI latency 23.57 秒。
- RAGFlow 36/36 文件入库但 image-only PDF 为 0 chunks；AnythingLLM 35/36 入库，重复 basename 导致一份文档上传失败。
- 两套产品均出现跨空间/禁止内容泄漏证据；AnythingLLM 对 image-only PDF 生成伪 OCR 文本。
- 上游借鉴项已固定 release/tag、精确 commit、许可证路径和 use mode；本阶段没有新增第三方源代码、依赖或 NOTICE 义务。

## 2. 保留

- 用合成数据覆盖结构、格式、冲突、无答案、prompt injection、空间边界和二进制文件，且通过脚本重生成 hash 复核。
- 让每个执行 Agent 拥有独立 worktree 和明确文件范围；主 Agent 只在合并后做全局验证和阶段收口。
- 将竞品实验结果与产品验收分开：真实失败项直接进入风险，而不是用“平均分”掩盖安全缺陷。
- 许可证记录同时保存 tag、dereferenced commit 和同 SHA 官方 LICENSE/NOTICE 路径。

## 3. 问题

- 竞品 UI 的 citation 仍是 basename 展示，重名文件会使“引用命中”无法证明 provenance；RAGForge 必须使用稳定 document/chunk ID。
- RAGFlow 的单次上传限制为 32 文件，AnythingLLM 对重复 basename 的处理会丢失一份文件；导入契约不能依赖 basename。
- image-only PDF 的 parser/OCR 行为不一致：RAGFlow 保守拒答，AnythingLLM 生成伪 OCR。OCR 结果必须带 parser 状态和可验证证据。
- RAGFlow 重启约 37 秒才 ready，并出现 `Load term.freq FAIL!` 警告；需要在 Phase 1 建立启动探针、就绪门禁和 Elasticsearch 资源基线。

## 4. 尝试

- Phase 1 先实现 `space_id` 强制贯穿 query/mutation、稳定 citation/provenance、禁止静默云端 fallback 和 parser failure state。
- 将 q-013/q-014/q-015/q-016/q-028/q-032 设为回归安全集，并增加“允许来源/禁止来源/应拒答”三类断言。
- 对增量更新采用 content hash + source identity + version 语义，禁止同名重传默默产生不可追踪副本。
- 将本阶段的 36/33 资产 ID 纳入后续离线评估配置，记录模型、embedding、chunking、reranker 和 prompt 版本。

## 5. Phase 1 入口

Phase 0 结束后进入 Phase 1 工程与领域骨架。第一批不可跳过的入口是：建立 tenant/space/session 基础领域模型、完成 API/event contract、落地 `space_id` 安全边界和最小可运行的本地 provider contract；竞品结果不作为 RAGForge 的实现规范，只作为失败导向的验收夹具。
