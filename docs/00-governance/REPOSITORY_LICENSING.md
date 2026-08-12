# RAGForge 仓库许可策略

## 1. 当前状态

仓库先作为私有学习/工程项目维护，当前未授予外部复制、修改或分发 RAGForge 自有代码和文档的许可。没有根 `LICENSE` 不是开源许可。

## 2. 未来公开前的闸门

1. 扫描并删除 Secret、个人 Obsidian 内容、真实 prompts、内部地址和身份信息。
2. 确认所有 dependency、vendored source、图片、样本和文档引用许可。
3. `THIRD_PARTY_NOTICES.md`、`licenses/`、SBOM 和复用登记一致。
4. 选择 RAGForge 自有内容的许可证，并明确代码、文档、商标/品牌是否分开。
5. 在全新 clone 中完成 build/test/deploy，不依赖未提交本地文件。
6. 完成安全报告渠道、贡献指南和 release provenance。

## 3. 与上游许可的区别

选择 RAGForge 自有许可证不能改变第三方组件的义务。Apache/MIT 源码的 attribution、GPL/修改许可的限制、Langfuse EE 等目录边界仍按上游精确版本处理。具体流程见 [开源合规](../06-security-compliance/OSS_COMPLIANCE.md)。

正式公开前应根据实际商业模式进行法律复核；本文件是工程治理基线，不是法律意见。

