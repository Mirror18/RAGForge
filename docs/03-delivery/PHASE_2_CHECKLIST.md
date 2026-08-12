# Phase 2 Provider、Prompt 与 Run Checklist

状态：`completed`（2026-08-13）。本清单的每个条目均已由主分支中的实现、测试、CI 或运行证据闭环。

## 一、契约切片门禁

- [x] P2-CONTRACT-01 REST 资源覆盖 ProviderConnection、ModelProfile、ModelRoute、SpaceBinding、PromptVersion、Run、Step、ModelInvocation、UsageLedger，并保留 `spaceId`、稳定 ID、版本和 correlation。
  - 证据：OpenAPI/event contract、Provider/Prompt/Run/SpaceBinding Java 集成测试；完整 Maven 84/84 通过。提交：`9f8095f`、`e41a51e`、`790ede1`、`c03ef34`、`eb8524a`、`f158910`。
  - 验收命令：`python scripts/ci/contract_test.py`；`python -m unittest discover -s tests/contract -p "test_*.py" -v`
- [x] P2-CONTRACT-02 Provider、Route、SpaceBinding 明确版本化、cloud egress 默认关闭、显式授权和同出境等级 failover 约束。
  - 证据：[`SpaceBindingApiIntegrationTest`](../../apps/server/src/test/java/com/ragforge/server/provider/SpaceBindingApiIntegrationTest.java) 8/8、[`test_phase2_egress_isolation.py`](../../tests/security/test_phase2_egress_isolation.py) 5/5、[`test_phase2_cloud_protocol.py`](../../tests/integration/test_phase2_cloud_protocol.py) 4/4。
  - 验收命令：`python -m unittest discover -s tests/security -p "test_phase2_egress_*.py" -v`
- [x] P2-CONTRACT-03 PromptVersion 发布后不可变，Run 记录实际 Prompt、Route、Model Profile 版本。
  - 证据：[`PromptPublicationStateIntegrationTest`](../../apps/server/src/test/java/com/ragforge/server/prompt/PromptPublicationStateIntegrationTest.java)、Run 全链路记录断言和 [`phase2-local-ollama-run.json`](../../tests/evidence/phase2-local-ollama-run.json)。
  - 验收命令：`python -m unittest discover -s tests/contract -p "test_*.py" -v`；`mvn --batch-mode --no-transfer-progress test`
- [x] P2-CONTRACT-04 Run/Step/ModelInvocation/UsageLedger 的状态、错误分类、usage source、dedupe key 和更新语义可验证。
  - 证据：[`RunExecutionControllerIntegrationTest`](../../apps/server/src/test/java/com/ragforge/server/run/RunExecutionControllerIntegrationTest.java) 覆盖成功、错误、取消、超时、重试、usage 去重；完整 Maven 84/84 通过；本地 Ollama usage ledger 1 行且 `PROVIDER_REPORTED`。
  - 验收命令：`mvn --batch-mode --no-transfer-progress test`
- [x] P2-CONTRACT-05 SSE 事件包含 `id`、`sequence`、`runId`、`spaceId`、`correlationId`、`occurredAt`、`type`、`version`，并定义 Last-Event-ID、快照恢复和取消边界。
  - 证据：[`RunEventControllerTest`](../../apps/server/src/test/java/com/ragforge/server/run/RunEventControllerTest.java)、Run replay/cancel 集成测试和 Phase 2 SSE contract test；完整 Maven 84/84 通过。
  - 验收命令：`python -m unittest tests/contract/test_phase2_contracts.py -v`；`mvn --batch-mode --no-transfer-progress test`
- [x] P2-CONTRACT-06 Phase 2 REST/event schema 可解析，错误、权限、幂等、限流、超时、取消、重试和敏感字段约束具备契约测试。
  - 证据：Phase 1+2 contract tests 25/25、Java RBAC/error/cancel/retry tests、adapter 429/timeout/error mapping tests、secret scan 通过；CI workflow 已接入云协议、并发和出境门禁。Phase 2 不宣称生产级全局限流，限流语义由错误/契约边界保留给后续平台阶段。
  - 验收命令：`python -m json.tool contracts/openapi/ragforge-api-v1.yaml`；`python -m unittest discover -s tests/contract -p "test_*.py" -v`

## 二、ROADMAP 阶段退出条件

- [x] P2-EXIT-01 本地 `qwen3.5:9b` 完成功能验收。
  - 证据：[`LocalOllamaRunAcceptanceTest`](../../apps/server/src/test/java/com/ragforge/server/run/LocalOllamaRunAcceptanceTest.java) 1/1；[`phase2-local-ollama-run.json`](../../tests/evidence/phase2-local-ollama-run.json)；模型 digest `6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7`；Run/Step/Invocation/Usage 均成功，token `22/128/150`，无 raw prompt/output/secret。
  - 验收命令：`$env:JAVA_HOME='C:\Program Files\Java\jdk-21'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; mvn -pl apps/server -Dtest=LocalOllamaRunAcceptanceTest test`
- [x] P2-EXIT-02 Mock 云端支持 20 条链路的协议与并发测试。
  - 证据：[`test_phase2_cloud_protocol.py`](../../tests/integration/test_phase2_cloud_protocol.py) 4/4；[`test_phase2_cloud_concurrency.py`](../../tests/performance/test_phase2_cloud_concurrency.py) 1/1（20 条链路）；CI workflow 已接入同样三组 Phase 2 deterministic gates。
  - 验收命令：`python -m unittest discover -s tests/integration -p "test_phase2_cloud_*.py" -v`；`python -m unittest discover -s tests/performance -p "test_phase2_*.py" -v`
- [x] P2-EXIT-03 断流、重连、取消、超时、重试不重复 usage。
  - 证据：Run SSE replay/snapshot/disconnect、cancel、timeout/retry 和 usage dedupe 集成测试；完整 Maven 84/84 通过；出境/错误安全回归 5/5。
  - 验收命令：`mvn --batch-mode --no-transfer-progress test`；`python -m unittest discover -s tests/security -p "test_phase2_*.py" -v`
- [x] P2-EXIT-04 安全测试证明未授权空间无法调用 cloud route。
  - 证据：[`SpaceBindingApiIntegrationTest`](../../apps/server/src/test/java/com/ragforge/server/provider/SpaceBindingApiIntegrationTest.java) 8/8、Run binding enforcement 8/8、[`test_phase2_egress_isolation.py`](../../tests/security/test_phase2_egress_isolation.py) 5/5，覆盖跨空间、未授权、local-only 和静默 failover 拒绝。
  - 验收命令：`python -m unittest discover -s tests/security -p "test_phase2_egress_*.py" -v`

## 三、实现状态约束

- 本文件与 `contracts/openapi/ragforge-api-v1.yaml`、`contracts/events/` 现已由主分支实现、测试和证据支持；Phase 2 的范围仍仅是 Provider、Prompt、Run no-RAG 纵向切片，不声称后续 RAG ingestion/retrieval 能力已经上线。
- 本阶段所有勾选项均对应主分支提交和可复现命令；本地 Ollama 证据依赖已安装模型和运行中的 `127.0.0.1:11434`，不作为无外部依赖的 GitHub CI 步骤。
- 所有后续实现必须保持 `spaceId` 服务端校验、显式 cloud egress、稳定 provenance/usage 记录和结构化错误分类。
