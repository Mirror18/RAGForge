# Cross-Application Tests

单元测试随应用/模块保存。这里放需要跨进程、跨契约或独立运行器的测试：

- `contract/`：OpenAPI、event、connector/provider/tool contracts。
- `integration/`：完整基础设施和跨应用事务/消息行为。
- `e2e/`：浏览器/API 用户旅程。
- `evaluation/`：RAG dataset runner、assertions、Promptfoo 配置和结果摘要。
- `performance/`：load/soak/capacity scripts。
- `security/`：RBAC/SSRF/upload/prompt-injection/secret probes。

测试数据遵守 [TEST_DATA_POLICY.md](../docs/04-quality/TEST_DATA_POLICY.md)。

