# RAGForge 后端

## 目标服务地图（ADR-0014 Accepted）

自己的业务进程只留四个。Nacos 只做注册和进程配置，不放权限或出境规则。

| 目录 | 一句话 | 迁入来源 |
|---|---|---|
| [`space/`](./space/) | 这个人能否进入这个空间，以及内容能否送到哪个云端模型 | `server` 的 identity、space、session、binding |
| [`knowledge/`](./knowledge/) | 资料如何变成已发布索引，以及按查询返回带 provenance 的候选 | `server` 的 source/ingestion/chunk/index；整个 `ingestion-worker` |
| [`answer/`](./answer/) | 用已发布索引生成带结构化引用的回答 | `server` 的 retrieval、prompt、answer、run、agent |
| [`gateway/`](./gateway/) | 浏览器唯一入口 | 新建 |
| [`shared/`](./shared/) | ID、错误、`space_id` 传播 | `server/common` 的窄基础 |

每个服务内部只有 `api` / `application` / `domain` / `infrastructure`。业务规则只写在 `application`。

## 当前运行进程（Maven 模块拆分完成前）

| 目录 | 职责 |
|---|---|
| [`server/`](./server/) | 现在的同步 API 与 Flyway |
| [`ingestion-worker/`](./ingestion-worker/) | 现在的异步摄取 |
| [`ai-runtime/`](./ai-runtime/) | 仍存在的 OCR/rerank 运行面，目标是并入 knowledge/answer 后删除 |

公开 API 仍以 [`contracts/`](../contracts/) 为唯一契约源。

## 启动方式

```powershell
python scripts/dev/core.py up
python scripts/dev/core.py health
mvn -pl backend/server spring-boot:run
mvn -pl backend/ingestion-worker spring-boot:run
```

一键启动：[`scripts/dev/start-local.bat`](../scripts/dev/start-local.bat)。真实凭据放 [`config/private/`](../config/private/)，不要进 Git。

## 验证

```powershell
mvn -q -pl backend/server,backend/ingestion-worker -am test
```
