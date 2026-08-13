# Phase 3 版本化摄取流水线阶段复盘

- 日期：2026-08-13
- 状态：`blocked_on_external_ocr_runtime`
- 基线：`14910c1ba4d8a1e12c2906a5658db05531633d9f`
- 阶段集成：`ad91c515fa83ec62627903a8a39a65a8f21f3b0d`

## 已完成

- SourceConnector、版本化领域 schema、V8 migration、Outbox/RabbitMQ/Worker consumer、checkpoint 安全边界已合入 main。
- 文件/本地目录/Git connector 通过全量与增量变更、重复 basename、Git provenance 和固定 synthetic manifest 验收。
- 原生 Markdown/TXT/PDF/DOCX/PPTX/XLSX 解析、Parse Report、OCR unavailable/timeout/success seam、Local/MinIO 内容寻址对象存储已实现。
- 故障矩阵覆盖 parser、object upload、OCR timeout、DB active pointer 和消息发布失败；20 次 PostgreSQL 并发重复交付只产生 1 次副作用；20 次本地对象 claim 只产生 1 个对象。
- CI 已接入 Phase 3 cross-platform、parser、fault、安全和 performance gate；全仓库 Maven 26/26、contract 7/7、secret/dependency/format/link gates 通过。Run [31678077203](https://github.com/Mirror18/RAGForge/actions/runs/31678077203) 的 Linux quality 全步骤通过，并生成 JVM evidence `9172406251`、Syft SBOM `9172324275`、Grype SARIF `9172342555`。

## 真实阻塞

- 本机没有可执行 Tesseract 或其他真实 OCR runtime。注入式 `OcrEngine` 成功路径已测试，但不能替代真实 OCR 2/2 质量门槛，因此 P3-EXIT-04 保持未勾选，阶段不能声明完全闭环。
- P3-EXIT-01 的 Linux 证据已由 Run [31678077203](https://github.com/Mirror18/RAGForge/actions/runs/31678077203) 完成；本地 Windows 证据也已通过。
- 首次 CI Grype 扫描发现 MinIO Java SDK 8.2.1 的 `GHSA-h7rh-xfpj-hpcm`（High）和 POI 5.2.2 的 `GHSA-gmg8-593g-7mv3`（Medium）；已升级至 MinIO 8.6.0、OkHttp JVM 5.1.0 和 POI 5.4.0，worker 26/26 及后续 CI Grype 通过。
- 本地 SBOM 脚本保留仓库既有 placeholder 分支；正式发布必须以 GitHub Syft/Grype 产物和高危阈值结果为准。

## 后续入口

1. 在具备已批准 OCR runtime 的 Linux runner 或隔离测试环境重新执行 `test_phase3_parser_quality.py`，取得 2/2 真实 OCR Parse Report 和固定版本/许可证证据。
2. 已记录 GitHub Actions Run URL、Linux acceptance/JVM artifact、Syft SBOM 和 Grype 结果。
3. 只有 P3-EXIT-04 补齐后，才勾选 checklist 全部退出条件并创建 Phase 3 closure commit；在此之前下一阶段入口保持冻结。
