# GitHub Actions

`quality.yml` 是 Phase 1 的最小可执行质量门禁，覆盖：

- Python/JSON 格式与语法检查、Compose 配置和模块化架构边界；
- Markdown 相对链接、tracked-file secret scan；
- Phase 0 Python unit、`tests/contract` unittest discover 和 contract artifact 检查；
- Python、Maven 和 `apps/web` npm 依赖缓存；
- 条件化 Maven compile/test，以及 `apps/web` 基于 `package-lock.json` 的 `npm ci`、format/build/test；
- `anchore/sbom-action` 生成 CycloneDX SBOM，`anchore/scan-action` 执行真实漏洞扫描；
- 本地 dependency inventory 和 SBOM/dependency 命令的可审计退出码。

当前没有业务 Maven/npm manifest 或 contract test module 时，条件步骤/测试 discover 会明确跳过或报告空骨架；
不会调用不存在的业务脚本。引入业务代码后，`apps/web/package-lock.json` 和具体 contract tests 必须一并提交。
