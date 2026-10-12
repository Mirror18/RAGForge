# Feature Specification: 按业务收拢的服务

**Feature Branch**: `003-business-services`

**Created**: 2026-10-12

**Status**: Active

**Input**: 后端按最小业务边界分成 space、knowledge、answer、gateway，用 Nacos 注册；不按 parser/OCR/rerank 拆服务。

## User Scenarios & Testing

### User Story 1 - 浏览器只访问一个入口 (Priority: P1)

使用者打开 Web，所有 API 都打到 gateway。登录、空间、资料、问答看起来仍是一个产品。

**Independent Test**: Web 只配置一个 API 源；网关把 `/api/v1/spaces` 与问答路径转到对应服务。

---

### User Story 2 - 资料能力完整属于 knowledge (Priority: P1)

编辑者导入来源、查看解析、发布索引、检索候选，全部由 knowledge 完成。不需要理解独立 Worker 或 Python AI Runtime 是另一个产品。

**Independent Test**: 没有 ingestion-worker 作为独立业务名出现在用户文档；批量任务可以是 knowledge 的第二个进程。

---

### User Story 3 - 问答不拥有文件和索引 (Priority: P1)

提问时 answer 向 knowledge 取已发布索引上的候选片段，再生成带引用回答。answer 没有对象存储凭据。

**Independent Test**: 架构测试禁止 answer 模块直接写 Qdrant 或对象存储。

### Edge Cases

- knowledge 批量作业卡住时，在线检索进程仍可回答已发布索引。
- Nacos 不可用时，本地开发可退回静态地址，但生产不得把业务规则写进 Nacos。
- ADR-0014 已 Accepted；Maven 模块与 Nacos/gateway 拆分完成前，代码仍可运行在现有 server + worker 进程里，目录先按业务标明归属。

## Requirements

- 四个业务进程：gateway、space、knowledge、answer。
- 每个服务内部只有 api / application / domain / infrastructure。
- PostgreSQL 一个实例、三个 schema；禁止跨 schema 写表。
- Spring Boot 对齐 Spring Cloud Alibaba 2025.0.0.0 声明的 3.5.0。
- `backend/ai-runtime` 不再作为可部署业务。

## Success Criteria

- 新人打开 `backend/README.md` 能说出四个服务各自负责什么、不负责什么。
- 没有“检索服务”“OCR 服务”“rerank 服务”作为独立部署单元。
