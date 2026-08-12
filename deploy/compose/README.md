# Docker Compose Profiles

计划文件：基础 `compose.yaml`，以及 local/staging/production overrides。逻辑 profiles：`core`、`observability`、`llmops`。

Phase 1 只实现 core；观测能力随纵向切片逐步加入；Langfuse llmops 在资源评估后可选加入。所有镜像锁定版本/digest，volume/port/network/health/resource 限制显式定义。

