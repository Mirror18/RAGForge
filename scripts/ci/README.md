# CI Scripts

## Preflight

统一环境检查可在 Windows PowerShell、Windows cmd 或 Linux shell 中调用：

```text
python scripts/ci/preflight.py
python scripts/ci/preflight.py --json
python scripts/ci/preflight.py --json --strict
```

检查项固定为 `JAVA_HOME`、Java、Maven、Node、npm 和 Docker daemon。命令只在本机执行
版本查询与 `docker info`，不会下载依赖、启动服务或修改仓库。`--json` 输出稳定的
`passed`、`checks`、`tool_versions`、`remediation` 四个字段；`tool_versions` 固定包含
Java、Maven、Node、npm、Docker 的版本键（不可用时为 `null`）；每个失败项含稳定的
`result_code`，修复提示不包含环境变量值、命令输出或可疑凭证。默认模式即使检查失败也
返回 0，便于诊断；`--strict` 在任一必需检查失败时返回非零。

单元测试运行方式：

```text
python -m unittest scripts.ci.test_preflight -v
```

执行证据应保存于 `tests/evidence/P7Q-01-worker-summary.v1.json`，供 CI/任务验收引用。

当前 CI 辅助命令均为可执行门禁：

```text
python scripts/ci/check_markdown_links.py
python -m unittest discover -s scripts/ci -p "test_*.py" -v
python scripts/ci/format_check.py
python scripts/ci/secret_scan.py
python scripts/ci/validate_compose.py --project-name ragforge-p1-orch-check --env-file deploy/compose/env.example
python scripts/ci/architecture_check.py
python scripts/ci/path_index_check.py
python scripts/ci/contract_test.py
python -m unittest discover -s tests/contract -p "test_*.py" -v
python scripts/ci/dependency_inventory.py --require-lockfiles
python scripts/ci/sbom_dependency_scan.py --mode required
```

`contract_test.py` 会实际执行 `tests/contract` 的 unittest discover，再检查 JSON/YAML
contract artifact；当前没有测试模块时保留 unittest 的原始退出码 5 并明确转换为空骨架
placeholder。只要出现 `test_*.py`，任何非零退出码都会失败，不会调用不存在的业务脚本。

`dependency_inventory.py` 检查 `package.json` 与同目录 `package-lock.json` 的配对并输出
JSON 清单。`sbom_dependency_scan.py --mode required` 在本地没有 `syft`/`trivy` 时返回非零
退出码；GitHub Actions 使用真实 SBOM/漏洞扫描 action，placeholder 不是唯一门禁。
