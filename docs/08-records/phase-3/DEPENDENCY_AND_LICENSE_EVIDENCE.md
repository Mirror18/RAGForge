# Phase 3 依赖与许可证证据

状态：`phase3-acceptance`。本记录只登记通过 Maven 官方 artifact 引入的依赖；本阶段没有复制第三方源码、没有新增 `third_party/` 内容，也没有把真实文档或 OCR 输出提交到仓库。

| 组件 | 固定版本 | 使用边界 | SPDX/许可证 | 证据与发布前复核 |
|---|---:|---|---|---|
| Apache PDFBox | `2.0.30` | Worker 原生 PDF 文本提取 | Apache-2.0 | Maven POM 与官方发布元数据；发布前由 CI SBOM/SCA 复核传递依赖 |
| Apache POI OOXML | `5.2.2` | Worker DOCX/PPTX/XLSX 结构化文本提取 | Apache-2.0 | Maven POM 与官方发布元数据；未 vendored |
| MinIO Java SDK | `8.2.1` | Worker S3-compatible object-store adapter；不包含 MinIO 服务端 | Apache-2.0 | Maven POM 与官方发布元数据；MinIO 容器只用于合成 Testcontainers 验收 |
| MinIO test image | `RELEASE.2024-12-18T13-15-44Z` | 仅测试环境的 S3-compatible endpoint | 以镜像发布元数据为准 | 不进入生产镜像；CI SBOM/SCA 必须重新扫描 |

## 复用与合规结论

- 以上均为官方依赖/API 使用，不是上游源代码复制；`docs/07-research/UPSTREAM_REUSE_REGISTER.md` 不新增源码复用批准项。
- 本地仓库的 `scripts/ci/sbom_dependency_scan.py` 明确保留 placeholder 分支，不能作为 SBOM 已完成的证据；GitHub Actions 的 Syft/Grype 步骤是发布前有效门禁。
- 新依赖的版本、许可证、传递依赖和镜像 digest 在正式商业发布前必须由 CI 生成的 CycloneDX SBOM 与 SCA 结果再次锁定；若许可证或高危漏洞门禁失败，不得发布。
