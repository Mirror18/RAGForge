# Phase 2 Provider、Prompt 与 Run Checklist

状态：`phase2-contract`。本清单定义 Phase 2 的验收入口；所有条目初始未勾选，不声明对应运行时功能已经上线。

## 一、契约切片门禁

- [ ] P2-CONTRACT-01 REST 资源覆盖 ProviderConnection、ModelProfile、ModelRoute、SpaceBinding、PromptVersion、Run、Step、ModelInvocation、UsageLedger，并保留 `spaceId`、稳定 ID、版本和 correlation。
  - 证据缺口：Java server 尚未消费本契约，需补 provider/consumer contract test 与集成证据。
  - 验收命令：`python -m unittest tests/contract/test_phase2_contracts.py -v`
- [ ] P2-CONTRACT-02 Provider、Route、SpaceBinding 明确版本化、cloud egress 默认关闭、显式授权和同出境等级 failover 约束。
  - 证据缺口：尚无真实 cloud/mock route 拒绝和授权演练。
  - 验收命令：`python -m unittest tests/contract/test_phase2_contracts.py -k cloud -v`
- [ ] P2-CONTRACT-03 PromptVersion 发布后不可变，Run 记录实际 Prompt、Route、Model Profile 版本。
  - 证据缺口：尚无发布后写入拒绝和 Run 重现集成证据。
  - 验收命令：`python -m unittest tests/contract/test_phase2_contracts.py -k prompt -v`
- [ ] P2-CONTRACT-04 Run/Step/ModelInvocation/UsageLedger 的状态、错误分类、usage source、dedupe key 和更新语义可验证。
  - 证据缺口：尚无重试、取消、provider report 重复上报的运行时验证。
  - 验收命令：`python -m unittest tests/contract/test_phase2_contracts.py -k usage -v`
- [ ] P2-CONTRACT-05 SSE 事件包含 `id`、`sequence`、`runId`、`spaceId`、`correlationId`、`occurredAt`、`type`、`version`，并定义 Last-Event-ID、快照恢复和取消边界。
  - 证据缺口：尚无真实 SSE 断线重连、过期 cursor 快照和 cancel 演练。
  - 验收命令：`python -m unittest tests/contract/test_phase2_contracts.py -k sse -v`
- [ ] P2-CONTRACT-06 Phase 2 REST/event schema 可解析，错误、权限、幂等、限流、超时、取消、重试和敏感字段约束具备契约测试。
  - 证据缺口：尚无实现侧错误映射、RBAC、rate limit 和 secret redaction 集成证据。
  - 验收命令：`python -m json.tool contracts/openapi/ragforge-api-v1.yaml`；`python -m unittest discover -s tests/contract -p "test_*.py" -v`

## 二、ROADMAP 阶段退出条件

- [ ] P2-EXIT-01 本地 `qwen3.5:9b` 完成功能验收。
  - 证据缺口：需要本地 Ollama 模型、真实 Provider adapter、无 RAG 对话、Run/Step/usage 运行证据。
  - 验收命令：`python -m unittest discover -s tests/integration -p "test_phase2_*.py" -v`；运行本地模型验收脚本并保存脱敏报告。
- [ ] P2-EXIT-02 Mock 云端支持 20 条链路的协议与并发测试。
  - 证据缺口：需要 mock cloud provider、20 条并发链路、超时/限流/错误映射和 cloud egress 授权证据。
  - 验收命令：`python -m unittest discover -s tests/integration -p "test_phase2_cloud_*.py" -v`；`python -m unittest discover -s tests/performance -p "test_phase2_*.py" -v`
- [ ] P2-EXIT-03 断流、重连、取消、超时、重试不重复 usage。
  - 证据缺口：需要真实 SSE replay/cancel 测试、attempt 状态机、UsageLedger 幂等写入和故障日志/trace。
  - 验收命令：`python -m unittest discover -s tests/integration -p "test_phase2_run_*.py" -v`；`python -m unittest discover -s tests/security -p "test_phase2_*.py" -v`
- [ ] P2-EXIT-04 安全测试证明未授权空间无法调用 cloud route。
  - 证据缺口：需要双空间 RBAC、关闭/开启 egress binding、跨空间 route 访问和静默 failover 拒绝证据。
  - 验收命令：`python -m unittest discover -s tests/security -p "test_phase2_egress_*.py" -v`

## 三、实现状态约束

- 本文件与 `contracts/openapi/ragforge-api-v1.yaml`、`contracts/events/` 只定义 Phase 2 合同，不声称 endpoint、事件 producer 或 adapter 已实现。
- 未勾选项必须在对应实现、测试、CI/演练证据进入主分支后由主 Agent 更新；契约 worker 不修改 `PROJECT_STATUS.md`、`RISK_REGISTER.md` 或 `TRACEABILITY_MATRIX.md`。
- 所有后续实现必须保持 `spaceId` 服务端校验、显式 cloud egress、稳定 provenance/usage 记录和结构化错误分类。
