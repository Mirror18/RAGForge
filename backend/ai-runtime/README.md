# RAGForge AI Runtime

源码和 Python 测试都集中在本目录。它是可选的窄职责运行时，不管理用户、空间、Provider 凭据、对话或业务数据库。

Python 内部服务仅承担 Java 生态不适合或模型运行更方便的能力：

- OCR（按页、受资源限制）。
- Rerank（例如多语言 cross-encoder，精确模型在评估后决定）。

约束：

- 不管理用户、空间、Provider 凭据或对话。
- 不直接访问业务数据库；输入带短期授权和 trace context。
- API 使用 versioned contract、请求/响应大小限制、超时和取消。
- 模型在启动时加载，提供 readiness/capabilities，不在每次请求临时下载。
- 默认无任意外网和文件系统访问。

## 本地启动与验证

```powershell
uv run --project backend/ai-runtime ragforge-ai-runtime
$env:RAGFORGE_AI_RUNTIME_SERVE = "true"
uv run --project backend/ai-runtime ragforge-ai-runtime
python -m unittest discover -s backend/ai-runtime/tests -p "test_*.py" -v
```

默认 loopback 地址为 `127.0.0.1:8090`，能力接口由当前 versioned contract 定义。只有明确设置 `RAGFORGE_AI_RUNTIME_SERVE=true` 才会启动服务模式。
