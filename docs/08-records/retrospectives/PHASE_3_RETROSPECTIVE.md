# Phase 3 版本化摄取流水线阶段复盘

- 日期：2026-08-13
- 状态：`accepted`
- 基线：`14910c1ba4d8a1e12c2906a5658db05531633d9f`
- 阶段集成：`2ca3a75`

## 已完成

- SourceConnector、版本化领域 schema、V8 migration、Outbox/RabbitMQ/Worker consumer、checkpoint 安全边界已合入 main。
- 文件/本地目录/Git connector 通过全量与增量变更、重复 basename、Git provenance 和固定 synthetic manifest 验收。
- 原生 Markdown/TXT/PDF/DOCX/PPTX/XLSX 解析、Parse Report、OCR unavailable/timeout/success seam、Local/MinIO 内容寻址对象存储已实现。
- 真实 Tesseract OCR runtime 已接入：PDFBox 以 200 DPI 渲染，Tesseract 每页受限子进程执行，限制输入 25 MiB、20 页、200,000 字符和 30 秒超时；两份无文本层合成 PDF 在 Windows `5.4.0.20240606` 与 Ubuntu CI `5.3.4-1build5` 均 2/2 成功。
- 故障矩阵覆盖 parser、object upload、OCR timeout、DB active pointer 和消息发布失败；20 次 PostgreSQL 并发重复交付只产生 1 次副作用；20 次本地对象 claim 只产生 1 个对象。
- CI 已接入 Phase 3 cross-platform、parser、fault、安全和 performance gate；根 Maven reactor、contract 32/32、secret/dependency/format/link gates 通过。Run [31706823033](https://github.com/Mirror18/RAGForge/actions/runs/31706823033) 的 Linux quality 全步骤通过，并生成 JVM evidence `9183633612`、Syft SBOM `9183518984`、Grype SARIF `9183542524`。

## 阶段门禁结论

- P3-EXIT-04 已满足：原生格式 6/6、image-only PDF 2/2，真实 OCR 2/2；Parse Report 保留 source artifact、页码、引擎版本、触发原因和 `COMPLETED` 审计状态，无真实文档正文进入证据。
- P3-EXIT-01 的 Linux 证据已由 Run [31706823033](https://github.com/Mirror18/RAGForge/actions/runs/31706823033) 完成；本地 Windows 证据也已通过。
- 首次 CI Grype 扫描发现 MinIO Java SDK 8.2.1 的 `GHSA-h7rh-xfpj-hpcm`（High）和 POI 5.2.2 的 `GHSA-gmg8-593g-7mv3`（Medium）；已升级至 MinIO 8.6.0、OkHttp JVM 5.1.0 和 POI 5.4.0，worker 26/26 及后续 CI Grype 通过。
- 本地 SBOM 脚本保留仓库既有 placeholder 分支；正式发布必须以 GitHub Syft/Grype 产物和高危阈值结果为准。

## 后续入口

1. Phase 4 入口：在保持版本化 revision/artifact、space_id 和 provenance 不变量的基础上，进入 chunking/index candidate 管线；不得把 Phase 3 的原生 parser/OCR 证据扩展解释为 retrieval 或 citation 已完成。
2. 继续跟踪 `R-006` 的生产 quarantine、AV、sandbox、压缩炸弹和恶意 corpus 工作；真实 OCR runtime 门禁关闭不等于恶意文件风险关闭。
3. 发布前继续复核 Tesseract 训练数据和目标发行包的许可证、SBOM、Notice 与镜像 digest。
