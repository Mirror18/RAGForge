---
id: doc-1
title: 当前代码
type: guide
created_date: '2026-10-12 03:09'
updated_date: '2026-10-14 10:00'
---
# 当前代码

本文按仓库里正在运行的代码编写。ADR-0014 的四个业务库模块（`shared` / `space` / `knowledge` / `answer`）已拆为 Maven jar。运行进程为 `gateway` + `server`（模块化单体）+ `ingestion-worker`。浏览器只打到 gateway（RF-9）；Nacos 为 opt-in，默认静态上游。

## 进程

| 目录 | 现在做什么 |
|---|---|
| `frontend/ragforge-web` | Vue 产品面。页面在 `src/views/{access,space,knowledge,answer,admin}`。云端模型页完成连接、三种用途、默认回答规则和空间绑定/出境；资料页只做导入与发布；看来源/试检索从引用进入并预填活跃索引。观测是运行观测（`ObservabilityView`）。样式只有 `src/styles/app.css`。Vite 默认代理到 gateway `25080` |
| `backend/gateway` | Spring Cloud Gateway。默认路由 `/api/**` → `RAGFORGE_GATEWAY_UPSTREAM`；`-Pnacos` + profile `nacos` 才启用发现 |
| `backend/server` | Boot 壳：Flyway、配置、主类；装配四业务库模块。本地 `25082`；Compose 仅内网 expose |
| `backend/shared` `backend/space` `backend/knowledge` `backend/answer` | Maven 库模块。包名 `com.ragforge.server.{…}`。跨业务持久化端口在 owning 的 `application`；ArchUnit 锁定不得依赖外业务 `*.infrastructure.*Repository` |
| `backend/ingestion-worker` | 异步摄取：连接器、解析、对象存储、消息、流水线 |
| `backend/ai-runtime` | Python rerank。`scripts/ci/architecture_check.py` 仍要求这个目录存在 |

每个业务根仅含 `api` / `application` / `domain` / `infrastructure`。解析在 worker，不单独成服务。

`ingestion-worker` 的包：`common`、`config`、`connector`、`messaging`、`objectstore`、`parser`、`pipeline`、`sync`。

## 技术栈

- Java 21，Spring Boot 3.5.16，Spring Cloud 2025.0.0，Spring Cloud Alibaba 2025.0.0.0。版本在 `backend/pom.xml`，默认启动不连 Nacos
- 数据：PostgreSQL、对象存储、Qdrant；本地编排见 `deploy/compose/compose.yaml`（服务名 gateway、server、worker、web）
- 模型：仅已授权空间的 OpenAI 兼容或 MiMo 云端 API。Chat 与 Embedding 未授权时 fail-closed。没有 Ollama 产品路径

## 仍在使用的根目录

`backend`（含 `backend/contracts` 接口定义）、`frontend`、`deploy`、`scripts`、`specs`、`backlog`、`tests`（含 `tests/fixtures` 合成样本）。任务板在 `backlog/tasks`，规格在 `specs/`，说明在 `backlog/docs/`。speckit 任务用 `scripts/dev/import_speckit_tasks.py` 入板。演示截图在 `tests/evidence/business-demo/`。

m-1 产品闭环已完成。m-2 进行中：Maven 库模块（RF-8）与 gateway/Nacos opt-in（RF-9）已落地；下一步为 ai-runtime 并入（RF-10）。

## 不变量

- 碰租户内容的查询和写入都带并校验 `space_id`
- 引用是结构化 provenance，不是模型自由文本
- 云端出境按空间显式授权，禁止未授权空间静默改走云端
