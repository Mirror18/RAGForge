# Versioned Configuration Samples

这里只放非敏感、可评审的配置模板。真实 Provider key、数据库密码、private repository credential 和个人数据路径通过环境/secret reference 注入。

- `model-profiles/`：能力和参数样例，不保存 API key。
- `prompts/`：开发种子模板；运行时发布后由数据库保存不可变版本。

环境专属部署配置放 `deploy/`，不能把本目录变成未治理的全局参数集合。
