# Scripts

- `dev/`：本地环境检查、启动和样本导入。
- `ci/`：链接、契约、SBOM、评估等 CI helpers。
- `ops/`：备份、恢复、健康诊断和受控维护。

脚本必须幂等或明确副作用，支持 `--help`/dry-run（涉及数据时），不回显 Secret。Windows 本地入口和 Linux 正式脚本需要对应行为测试。
