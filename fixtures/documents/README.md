# Phase 0 corpus

本目录包含可提交的合成 benchmark corpus。正文由 `scripts/phase0/generate_assets.py` 生成，不含个人 Obsidian 内容、客户数据、秘密或真实敏感 prompt。

## 资产范围

- 36 份样本，覆盖 Markdown、YAML frontmatter、wikilink、标题、表格、代码块、callout、嵌套列表、长文、同名文档、版本冲突、无答案、Unicode、提示注入和 `space_id` 边界。
- `corpus/pdf/handbook.pdf` 是固定字节的可解析文本 PDF；`corpus/pdf/scan-placeholder.pdf` 是明确标注的合成扫描替代样本，不是 OCR 结果。
- DOCX 与 XLSX 是由标准库生成的最小 OOXML 文件，用于验证二进制解包和 provenance；它们不复制第三方模板。

## 合法性与版本

所有内容均为本仓库生成器内的原创 synthetic 内容，manifest 为每个样本记录 `provenance` 和 `license`，统一标记为 CC0-1.0。数据集版本为 `phase0-benchmark-1.0.0`，生成器版本为 `phase0-assets-generator-1.0.0`；`immutable_id` 和每个文件的 SHA-256 是不可变实验标识。

## 生成与校验

在仓库根目录执行：

```powershell
python scripts/phase0/generate_assets.py --output-root fixtures
python scripts/phase0/validate_assets.py --root fixtures
```

生成器固定 seed `20260812`，会重建其管理的 `documents/corpus`，并写入 `document_manifest.json`。不要把真实本地笔记复制到该目录。
