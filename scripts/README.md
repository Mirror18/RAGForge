# Scripts

- `dev/`：本地环境检查、启动和样本导入。
- `ci/`：链接、契约、SBOM、评估等 CI helpers。
- `ops/`：备份、恢复、健康诊断和受控维护。
- `phase4/`：Phase 4 检索 30 问质量基准、1M 参考规模探针和可选 Qdrant 1M 容量演练。

Phase 4 质量基准使用固定的 `fixtures/` 合成语料，不提交生产数据。常规 CI 运行确定性质量与参考规模门禁；Qdrant 1M 演练需要本地 Docker，命令为 `python scripts/phase4/qdrant_scale_benchmark.py`，会使用独立容器并在结束时清理。

脚本必须幂等或明确副作用，支持 `--help`/dry-run（涉及数据时），不回显 Secret。Windows 本地入口和 Linux 正式脚本需要对应行为测试。
