# MiMo 与本地 notes 业务闭环证据

- 日期：2026-08-23
- 工作树：`codex/business-loop-integration`
- 基线：`049a3e36e892cca62d84ce4773b90a0c4843441c`
- 证据范围：本地开发环境、单测试空间、无个人 notes 内容入库或出现在记录中。

## 实现

- MiMo 复用既有 Provider Registry、OpenAI-compatible adapter、space binding、typed authorization context 和 revision/artifact material service。
- MiMo 凭据仅由 ignored `.env.local` 的环境变量引用注入；版本化 `.env.example` 只保留空值和说明。
- 前端可在本地 Ollama 与 MiMo Chat 之间切换。MiMo Chat 显式授权时，Embedding/Rerank 仍使用本地路由；切回本地后界面显示 `LOCAL_ONLY`。
- 前端增加常用本地 notes 文件夹入口，筛选 Markdown、排除 `.obsidian`，并提交文件夹相对路径；服务端路径策略拒绝绝对路径、遍历和控制字符。
- RAG prompt 初始化要求 `claim_text` 是 `answer_text` 的精确连续子串；服务端对无效可选范围执行安全回退，citation UUID 仍执行 allow-list 校验。

## 真实运行证据

| 场景 | Run | 结果 |
|---|---|---|
| MiMo Chat + 本地 embedding/rerank | `1e58f763-10a6-4665-a9c2-1445f921b5d2` | SSE 序列 10，1 条结构化 citation，回答完成；correlation `01a02f10-0d9b-73e7-8ce3-74a2ab95d049` |
| Ollama `qwen3.5:9b` LOCAL_ONLY | `9aa79e04-f5ff-4a35-b055-fc4471ed52de` | SSE 序列 9，1 条结构化 citation，回答完成；correlation `01a02f13-0b19-7636-bb87-ba447596280e` |

两条运行均在同一测试空间内完成，回答包含 `space_id` 隔离相关证据；服务端未记录对应运行的 WARN/ERROR。此前 `qwen3.5:0.8b` 的 citation range 被服务端拒绝，故默认验收模型切换为 `qwen3.5:9b`。

## 验证命令

- `npm run format:check`：通过。
- `npm run build`：通过，TypeScript/Vite 构建成功。
- `mvn -pl apps/server -Dproject.build.sourceEncoding=UTF-8 -Dtest=BusinessIngestionPathPolicyTest,ProviderAdapterHttpTest,Phase5ProviderIntegrationTest,V11RagPromptPortTest test`：通过，41 tests，0 failures，0 errors，0 skipped。
- `git diff --check`：通过。

## 明确限制

自动化浏览器工具无法伪造操作系统文件选择器，因此本记录只证明 notes 文件夹入口、过滤和路径安全策略，未声称已经读取 `D:\project\learning\notes` 的真实个人内容。用户在浏览器中选择该目录后，应再记录不含原文的摄取 Job、Parse Report、候选索引验证和 active index 发布证据。个人 notes 不进入 Git、CI、长期 evidence，也不应在 MiMo 云 Chat 授权前提交到云端。
