---
id: doc-1
title: 当前代码
type: guide
created_date: '2026-10-12 03:09'
updated_date: '2026-10-12 16:20'
---
# 当前代码

本文按仓库里正在运行的代码编写。ADR-0014 的四个业务进程（space、knowledge、answer、gateway）尚未拆成独立 Maven 模块。浏览器现在打到 `backend/server`。

## 进程

| 目录 | 现在做什么 |
|---|---|
| `frontend/ragforge-web` | Vue 产品面。页面在 `src/views/{access,space,knowledge,answer,admin}`，样式只有 `src/styles/app.css` |
| `backend/server` | 同步 API、Flyway、身份、空间、云端模型、资料、分块、索引、检索、生成、引用、run |
| `backend/ingestion-worker` | 异步摄取：连接器、解析、对象存储、消息、流水线 |
| `backend/ai-runtime` | Python rerank。`scripts/ci/architecture_check.py` 仍要求这个目录存在 |

`server` 的 Java 包：`agent`、`answer`、`audit`、`chunk`、`common`、`config`、`embedding`、`identity`、`index`、`ingestion`、`ops`、`prompt`、`provider`、`retrieval`、`run`、`space`、`studio`。解析在 worker，不单独成服务。

`ingestion-worker` 的包：`common`、`config`、`connector`、`messaging`、`objectstore`、`parser`、`pipeline`、`sync`。

## 技术栈

- Java 21，Spring Boot 3.5.0，Spring Cloud 2025.0.0，Spring Cloud Alibaba 2025.0.0.0。版本在 `backend/pom.xml`，默认启动不连 Nacos
- 数据：PostgreSQL、对象存储、Qdrant；本地编排见 `deploy/compose/compose.yaml`（服务名 server、worker、web）
- 模型：仅已授权空间的 OpenAI 兼容或 MiMo 云端 API。Chat 与 Embedding 未授权时 fail-closed。没有 Ollama 产品路径

## 仍在使用的根目录

`backend`（含 `backend/contracts` 接口定义）、`frontend`、`deploy`、`scripts`、`specs`、`backlog`、`tests`（含 `tests/fixtures` 合成样本）。任务板在 `backlog/tasks`，规格在 `specs/`，说明在 `backlog/docs/`。speckit 任务用 `scripts/dev/import_speckit_tasks.py` 入板。演示截图在 `tests/evidence/business-demo/`。

下一步不先拆四进程：见 [业务闭环优化方向](./doc-4%20-%20业务闭环优化方向.md)，执行卡在里程碑 `m-1`。

## 不变量

- 碰租户内容的查询和写入都带并校验 `space_id`
- 引用是结构化 provenance，不是模型自由文本
- 云端出境按空间显式授权，禁止未授权空间静默改走云端
