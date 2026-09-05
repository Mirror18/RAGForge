# 本机私密配置

此目录用于存放本机开发所需的敏感配置，例如数据库密码、Provider key、bootstrap token、私有 Git 凭据和本地路径。目录内容默认被 `.gitignore` 忽略，只有本 README 被纳入版本控制。

建议：

- 从 `.env.example` 复制到仓库根目录的 `.env.local`，或使用本目录下的本机配置文件。
- 不提交真实 secret、个人 Obsidian 路径、客户数据、模型凭据或生产配置。
- 不在日志、消息、测试证据和提交信息中回显 secret。
- 共享环境使用 secret manager 或 CI secret 注入，不把此目录当作配置中心。

可提交的模板放在 [`config/`](../README.md)，环境专属部署模板放在 [`deploy/`](../../deploy/README.md)。
