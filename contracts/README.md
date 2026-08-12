# Contracts

跨进程和对外契约的事实来源：

- `openapi/`：REST/SSE snapshot、error schemas、service token scopes。
- `events/`：RabbitMQ event envelope 与 versioned payload schemas。

契约先于实现修改；CI 检查 lint、breaking changes 和生成代码一致性。内部 Java 模块的普通方法不需要被错误地建成远程契约。
