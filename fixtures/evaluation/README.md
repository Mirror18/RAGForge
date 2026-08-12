# Phase 0 evaluation manifest

`question_manifest.json` 提供 33 个稳定 case，包含问题类型、难度、`gold_references`、1–3 relevance、reference answer、expected abstention、required/forbidden citations 和空间 fixture。`dataset_index.json` 汇总 manifest hash 与 immutable dataset id。

## 生成与校验

```powershell
python scripts/phase0/generate_assets.py --output-root fixtures
python scripts/phase0/validate_assets.py --root fixtures
```

manifest 与 corpus 使用同一固定 seed/version。`UNANSWERABLE` 和 `CONFLICTING` case 的 abstention 期望值是标注数据，不代表运行时结果；扫描 PDF case 明确要求报告 OCR unavailable，不得伪造 OCR 文本。
