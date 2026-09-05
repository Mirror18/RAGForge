# RAGForge 后端

后端运行面统一位于 `backend/`，按进程职责拆成三个边界：

| 目录 | 职责 | 生命周期 |
|---|---|---|
| [`server/`](server/) | 模块化单体、同步 API、认证、空间权限、检索、回答、审计和 Flyway | 主应用进程 |
| [`ingestion-worker/`](ingestion-worker/) | 异步来源同步、解析、分块、向量和候选索引任务 | 独立 Worker 进程 |
| [`ai-runtime/`](ai-runtime/) | OCR、rerank 等窄职责模型运行时 | 可选独立 Python 服务 |

Server 与 Worker 共享根目录 Maven reactor，但 Worker 不复用 Server Controller 或 Repository；AI Runtime 不是第二业务后端。公开 API 与跨进程事件以 [`contracts/`](../contracts/) 为唯一契约源。

## 启动方式

先启动 Docker core：

```powershell
python scripts/dev/core.py up
python scripts/dev/core.py health
```

再按需启动后端进程：

```powershell
mvn -pl backend/server spring-boot:run
mvn -pl backend/ingestion-worker spring-boot:run
```

Windows 一键启动 Server、Worker、Web 和 core 使用 [`scripts/dev/start-local.bat`](../scripts/dev/start-local.bat)。应用配置通过环境变量或 [`config/private/`](../config/private/) 下的本机文件注入，真实凭据不进入 Git。

## 验证

```powershell
mvn -q -pl backend/server,backend/ingestion-worker -am test
python -m unittest discover -s backend/ai-runtime/tests -p "test_*.py" -v
```

各进程的端口、依赖和失败边界见对应子目录 README。
