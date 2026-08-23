# 前端业务闭环增量验收记录

- 日期：2026-08-23
- 分支：`codex/business-loop-integration`
- 基线：`049a3e36e892cca62d84ce4773b90a0c4843441c`
- 范围：登录后的空间配置、Markdown 上传、Outbox/RabbitMQ、Worker、Parse Report、active index、引用问答入口。
- 数据边界：仅使用隔离本地 Compose、测试账号和本地 Ollama；不包含 Secret、个人内容或云端出境。

## 已取得的真实证据

1. Server API 已提交一次 Markdown 上传并生成 `ingestion_job`、attempt、checkpoint 和 `ingestion.job.requested.v1` Outbox 事件。
2. Worker 通过 RabbitMQ 消费该事件，使用 MinIO 读取 content-addressed artifact，完成 Markdown parse、text artifact、Parse Report、chunk、768 维 Ollama embedding、Qdrant candidate index 验证和 active pointer 切换。
3. 成功任务的状态为 `SUCCEEDED`，五个步骤 `DISCOVER/FETCH/PARSE/PERSIST/PUBLISH` 均为 `SUCCEEDED`；Parse Report 的错误为空，`NO_FRONTMATTER` 仅作为 warning。
4. active index 返回 `ACTIVE`、768 维、`sampleRetrievalPassed=true`、`spaceFilterPassed=true` 和服务端计算的 dataset hash。
5. 前端已增加真实 API 入口：本地 Ollama 配置初始化、Markdown multipart 上传、任务轮询、Parse Report 展示和 active index 解锁；问答 Run 与 Answer 共用服务端返回的 correlation ID。

## 自动化验证

- `mvn -pl apps/server,apps/ingestion-worker -am test`：Server 210 tests，0 failures，0 errors，1 skipped；Worker 首轮暴露 1 个测试数据契约和 1 个禁用摄取 bean 条件问题，修复后 `mvn -pl apps/ingestion-worker -am test` 为 28/28 通过。
- `python scripts/ci/contract_test.py`：52/52 contract tests，21 artifacts，通过。
- `apps/web/npm run format:check`：通过。
- `apps/web/npm run build`：通过。
- `git diff --check`：通过。

## 尚未宣称的证据

- 浏览器视觉/交互验收仍需在本地登录会话中执行；本记录在用户确认输入本地测试账号密码前不把浏览器登录、初始化按钮点击和最终 SSE 引用回答写成已完成。
- 本记录不替代 Phase 6 已完成的人工评审豁免记录，也不把本地单 fixture 证据外推到云端、生产容量或真实模型质量。
