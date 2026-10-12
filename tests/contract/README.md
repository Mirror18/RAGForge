# Contract Tests

覆盖 REST、events、SSE、SourceConnector、DocumentParser、Provider、AI Runtime 和 ReadOnlyTool。每个 contract 固定正常、永久错误、临时错误、timeout、cancel 和 schema compatibility 行为。

契约测试按接口拆开：`test_api_contracts.py`、`test_provider_contracts.py`、`test_ingestion_contracts.py`、`test_retrieval_contracts.py`、`test_answer_contracts.py`。只使用 Python 标准库：

```text
python -m unittest discover -s tests/contract -p "test_*.py" -v
```

测试验证 OpenAPI path/header/error 形状、UUIDv7/cursor、`spaceId` 约束、RFC 9457 Problem Details，以及首批 Outbox/ingestion event 的有效/非法实例和敏感完整载荷拒绝。
