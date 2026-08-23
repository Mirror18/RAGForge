# CI Scripts

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
