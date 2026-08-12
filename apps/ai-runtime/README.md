# RAGForge AI Runtime

Python 内部服务仅承担 Java 生态不适合或模型运行更方便的能力：

- OCR（按页、受资源限制）。
- Rerank（例如多语言 cross-encoder，精确模型在评估后决定）。

约束：

- 不管理用户、空间、Provider 凭据或对话。
- 不直接访问业务数据库；输入带短期授权和 trace context。
- API 使用 versioned contract、请求/响应大小限制、超时和取消。
- 模型在启动时加载，提供 readiness/capabilities，不在每次请求临时下载。
- 默认无任意外网和文件系统访问。

