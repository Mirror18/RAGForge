# Phase 6 Evaluation Dataset

本任务冻结 `phase6-evaluation-v1`：128 条可公开保存、由标准库 Python 按固定 seed 复现生成的合成评估用例。数据集只保存合成问题、opaque document/revision/artifact/chunk/evidence 引用和哈希，不保存真实客户内容、原始客户 prompt、文档正文、凭据或第三方样本。

覆盖 Markdown、表格、PDF、OCR、多段、多文档、同名相似、时间/版本冲突、无答案、权限、prompt injection、恶意文档和跨空间；每条记录有 `expected_claims`、`required_citations`、`forbidden_citations`、`answerability`、`manual_review` 和安全期望字段。

## 复现与验证

在仓库根目录运行：

```powershell
python scripts/phase6/evaluation_runner.py --generate-dataset --validate-only
python scripts/phase6/evaluation_runner.py
python -m unittest scripts/phase6/evaluation_runner_test.py
```

默认 runner 生成 deterministic baseline/candidate fixture，并写入 `tests/evidence/phase6-evaluation-report.v1.json`。真实候选结果可通过 `--candidate` 和 `--baseline` 传入同一 result schema；runner 会拒绝缺失/重复 case，记录 Evidence Bundle 外引用、跨空间泄漏、拒答准确率、Recall@10、MRR@10、citation precision、claim faithfulness、切片和不确定性区间。

报告中的 `code_commit`、dataset SHA、config version、seed、Python/机器环境和运行时间用于审计。结果是 RAGForge 自有真相源。

`tests/evidence/phase6-evaluation-promptfoo-matrix.v1.json` 只是 Promptfoo 可选 matrix/red-team 适配 schema；本任务不新增 Promptfoo 依赖、不执行第三方工具，也不把它的结果作为历史真相。

人工复核字段默认 `PENDING`。自动门禁通过不等价于人工复核完成；复核者应在受控流程中写入 reviewer、时间、标签和 notes。
