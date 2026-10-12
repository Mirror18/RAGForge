# Implementation Plan: 按业务收拢的服务

**Branch**: `003-business-services` | **Date**: 2026-10-12 | **Spec**: [spec.md](./spec.md)

## Summary

用 ADR-0014 替代 ADR-0001 的进程划分。先目录和文档，再在现有 `backend/server` 内收包，再拆 Maven 模块，最后接 Nacos 与 gateway。Java 21 保持不变。Spring Boot 从自行钉死的 3.5.14 收到 SCA 2025.0.0.0 声明的 3.5.0，并引入 `spring-cloud-alibaba-dependencies`。

## Technical Context

**Language/Version**: Java 21、Spring Boot 3.5.0、Spring Cloud 2025.0.0、Spring Cloud Alibaba 2025.0.0.0

**Storage**: PostgreSQL schemas `space`/`knowledge`/`answer`；Qdrant 与对象存储仅 knowledge 写

**Testing**: ArchUnit/Modulith、现有 Maven 模块测试、契约测试

**Constraints**: ADR-0014 已 Accepted。本轮仍保持 server + worker 可运行；不把 Nacos 接到现有 server 的默认启动路径。可先改 BOM、README 与包归属。

## Target Layout

```text
backend/
├── README.md
├── shared/
├── gateway/
├── space/
├── knowledge/          # 含可选 job 启动角色
└── answer/
```

当前代码仍在 `backend/server` 与 `backend/ingestion-worker`，README 标明迁入目标。

## Implementation Sequence

1. ADR-0014 已 Accepted，并更新架构/技术栈文档与 AGENTS.md。
2. 根 POM 引入 SCA BOM，Boot 版本改为 3.5.0；现有安全补丁覆盖保留。
3. 包级收拢后再拆模块。
4. 后续卡片：Nacos、gateway、删除 ai-runtime 独立部署。
