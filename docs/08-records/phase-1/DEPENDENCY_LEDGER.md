# Phase 1 依赖与开源合规登记

本记录只登记本阶段实际引入的运行时/构建依赖和镜像基线。未复制第三方源码；`third_party/` 仍为空。本表不是对未来生产镜像许可证或漏洞状态的永久承诺，发布前仍须使用 SBOM 和 SCA 重新验证。

| 组件 | 固定版本/来源 | 用途 | 源码复制 | 许可证/合规证据 |
|---|---|---|---|---|
| Spring Boot | `3.5.5`，Maven Central | Java server/worker 基础 | 否 | 官方 Apache-2.0 依赖元数据；CI SBOM |
| Flyway | `11.7.2`，Maven Central | PostgreSQL 迁移 | 否 | 官方 Apache-2.0 依赖元数据；迁移只保留项目 SQL |
| Testcontainers | `1.21.3`，Maven Central | PostgreSQL/Valkey 集成测试 | 否 | 官方 Apache-2.0 依赖元数据；CI SBOM |
| PostgreSQL | `16.4-alpine` | 业务真相数据库 | 否 | PostgreSQL License；镜像扫描 |
| Qdrant | `v1.11.5` | 向量基础设施骨架 | 否 | Apache-2.0；镜像扫描 |
| RabbitMQ | `3.13-management-alpine` | 消息基础设施骨架 | 否 | 以官方发行物许可证元数据和 SBOM 为准 |
| Valkey | `8.0.1-alpine` | Session/缓存 | 否 | BSD-3-Clause；镜像扫描 |
| MinIO | `RELEASE.2024-12-18T13-15-44Z` | S3-compatible storage | 否 | 以该发行物许可证和 SBOM 为准；商业发布前复核 AGPL 义务 |
| Ollama | `0.5.4` | 可选宿主机/容器 provider 连接探针 | 否 | 以官方发行物许可证和 SBOM 为准 |
| Vue | `3.5.13`，npm lockfile | Web UI 骨架 | 否 | MIT；`apps/web/package-lock.json` |
| Vite | `6.4.3`，npm lockfile | Web 构建 | 否 | MIT；`apps/web/package-lock.json` |
| TypeScript | `5.7.3`，npm lockfile | Web 类型检查 | 否 | Apache-2.0；`apps/web/package-lock.json` |

## 闸门结果

- `python scripts/ci/dependency_inventory.py --require-lockfiles` 通过，Web 依赖具有 `package-lock.json`。
- GitHub Actions 使用 `anchore/sbom-action@v0` 和 `anchore/scan-action@v6` 生成 CycloneDX 并以 High 为失败阈值；本机未安装 syft/trivy/grype，不能把本地 SBOM 扫描冒充通过。
- 没有提交密钥、个人 Obsidian 内容、真实客户数据或未经审批的第三方源代码。
- 引入新的组件版本、镜像 digest、供应商代码或公开发布前，必须更新本表、`THIRD_PARTY_NOTICES.md`、复用登记表和风险登记。
