# Feature Specification: 仅云端模型

**Feature Branch**: `002-cloud-only-models`

**Created**: 2026-10-12

**Status**: Active

**Input**: 去掉 Ollama；Chat 与 Embedding 只走空间已授权的 OpenAI 兼容云端 API。

## User Scenarios & Testing

### User Story 1 - 管理员只登记云端连接 (Priority: P1)

平台管理员登记 OpenAI 兼容或 MiMo 端点、凭据引用和能力探测。页面不再提供“本地模型服务”选项。

**Why this priority**: 本地 9B 模型不能承担目标并发，继续保留双栈会让出境策略含糊。

**Independent Test**: 创建连接表单没有 Ollama；提交 LOCAL egress 的 Chat 连接被拒绝或不可选。

**Acceptance Scenarios**:

1. **Given** 平台管理员打开模型页，**When** 查看类型，**Then** 只有云端 OpenAI 兼容/MiMo。
2. **Given** 空间未授权出境，**When** 提问或请求 embedding，**Then** 请求失败并说明需要授权，不改走其他端点。

---

### User Story 2 - 空间显式授权后才能把内容送出 (Priority: P1)

空间管理员打开云端出境并选择已发布路线。授权过期或关闭后，进行中的新请求失败。

**Independent Test**: 关闭出境后再提问，API 在调用 provider 之前拒绝。

**Acceptance Scenarios**:

1. **Given** 已授权的空间，**When** 提问，**Then** 只调用该空间绑定的云端 Chat。
2. **Given** 授权关闭，**When** 再次提问，**Then** 失败，且没有第二条云端或本地调用。

### Edge Cases

- Embedding 与 Chat 都需要云端授权；不得假设 embedding 仍走本机。
- 网页抓取仍单独要求出境确认和域名白名单。
- 现有 Ollama 测试改为云端契约测试或删除，不能用假本地成功冒充验收。

## Requirements

- 产品默认不再包含 Ollama runtime、Compose `ollama` profile、启动脚本 `-CheckOllama`。
- 空间没有有效云端绑定则 fail-closed。
- 章程、ADR-0005 的本地默认、追溯矩阵 RF-PRV-001 一并更新，避免文档仍要求 Ollama。

## Success Criteria

- 仓库中用户路径不再出现“选择本地或云端”。
- 未授权出境的安全测试仍然失败关闭。
- 本地启动不再把 Ollama 列为前置条件。
