# GitHub Actions

`quality.yml` 是全量质量门禁，覆盖：

- Python/JSON 格式与语法检查、Compose 配置和模块化架构边界；
- Markdown 相对链接、tracked-file secret scan；
- Phase 0 Python unit、`tests/contract` unittest discover 和 contract artifact 检查；
- JDK 21、Maven 缓存、Node.js 22，以及 `scripts/ci/preflight.py --json --strict` 环境前置检查；
- 根目录全量 Maven reactor 的 compile/test 回归（覆盖 `apps/server` 与 `apps/ingestion-worker`），以及 `apps/web` 基于 `package-lock.json` 的 `npm ci`、format/build/test；
- `anchore/sbom-action` 生成 CycloneDX SBOM，`anchore/scan-action` 执行真实漏洞扫描；
- 本地 dependency inventory 和 SBOM/dependency 命令的可审计退出码；
- 每次运行将 preflight、contract、Maven、Phase 门禁和 npm 长日志连同结构化 `quality-summary.json` 上传为 `ragforge-quality-logs-*` artifact（保留 14 天）。

## Job 与前置条件

当前 workflow 使用一个 `quality` job，在 `ubuntu-latest` 上按顺序执行环境检查、静态/契约门禁、依赖安全扫描、Maven 回归、Phase 验收和可选 Web 门禁。
CI 会显式安装 Temurin JDK 21 与 Node.js 22；runner 还必须提供 Maven、npm、Python 3.12、Docker daemon 和可执行的 Tesseract OCR 安装权限。
`preflight.py --strict` 在质量门禁前失败时，优先查看同一运行的 `preflight.json`。

## 本地等价命令

```text
python scripts/ci/preflight.py --json --strict
python scripts/ci/format_check.py
python scripts/ci/validate_compose.py
python scripts/ci/architecture_check.py
python scripts/ci/secret_scan.py
python scripts/ci/contract_test.py
mvn --batch-mode --no-transfer-progress test
```

失败排查时，先按 job step 名称定位命令，再下载 `ragforge-quality-logs-*` artifact；其中 `maven-compile.log`、`maven-test.log`、各 Phase/contract 日志和 surefire reports 用于复现。日志只记录工具输出与固定路径，不写入 secrets、原始 prompt 或生产数据。

当前没有 contract test module 时，discover 会报告空骨架并按 workflow 的显式 placeholder 规则处理；引入业务代码后，`apps/web/package-lock.json` 和具体 contract tests 必须一并提交。
