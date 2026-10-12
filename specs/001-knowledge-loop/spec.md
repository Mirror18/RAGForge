# Feature Specification: 页面知识闭环

**Feature Branch**: `001-knowledge-loop`

**Created**: 2026-10-12

**Status**: Active

**Input**: 前端页面成为能走完整个产品的验收面；导航按业务顺序，而不是按技术工具拆开。

## User Scenarios & Testing

### User Story 1 - 登录并进入自己的空间 (Priority: P1)

使用者用本地账号登录或注册。没有空间时，页面引导创建第一个空间。有空间时，顶部始终显示当前空间名称、角色和隔离提示。

**Why this priority**: 没有空间边界，后续导入和问答都无法验收。

**Independent Test**: 未登录看到登录页；登录后可创建并切换空间；刷新后仍停在同一空间。

**Acceptance Scenarios**:

1. **Given** 未登录，**When** 打开应用，**Then** 只看到登录/注册/引导，看不到其他空间的资料。
2. **Given** 已登录且没有空间，**When** 创建空间，**Then** 进入该空间工作台。
3. **Given** 已登录且有多个空间，**When** 切换空间，**Then** 来源、索引和问答都换成新空间的数据。

---

### User Story 2 - 配置云端模型后再导入资料 (Priority: P1)

空间管理员在页面上登记 OpenAI 兼容云端连接、发布模型和提示词，并显式打开出境。普通编辑者随后导入文件、查看任务、发布索引。

**Why this priority**: 当前向导把模型、上传、任务、索引进同一个长页，使用者无法判断缺哪一步。

**Independent Test**: 未配置云端模型时，导入页明确提示去模型页；配置完成后，选择文件可提交摄取任务并看到每页 10 条状态。

**Acceptance Scenarios**:

1. **Given** 空间没有已发布的云端 Chat 路线，**When** 打开资料页，**Then** 页面阻止提问并给出“先完成模型配置”的下一步。
2. **Given** 已有云端配置，**When** 选择 Markdown/PDF 等受支持文件，**Then** 出现摄取任务，失败可看到原因。
3. **Given** 候选索引验证通过，**When** 发布当前版本，**Then** 页面显示已发布索引，才能进入问答。

---

### User Story 3 - 带引用提问并打开来源 (Priority: P1)

使用者在已发布索引上提问，看到流式回答、结构化引用、历史会话和反馈。点击引用进入内容证据，而不是手填内部编号。

**Why this priority**: 产品成功标准是可核验回答，不是一段无来源文本。

**Independent Test**: 合成数据下提交问题后出现 citation；打开引用进入证据页且 `space_id` 一致。

**Acceptance Scenarios**:

1. **Given** 当前空间有已发布索引和云端模型，**When** 提问，**Then** 回答带来源、hash 和位置，不出现模型编造的引用句子。
2. **Given** 一条 citation，**When** 打开来源，**Then** 进入内容证据页，仍绑定同一 `space_id`。
3. **Given** 证据不足，**When** 模型应拒答，**Then** 页面展示拒答而不是空引用。

---

### User Story 4 - 管理健康、成本和一次运行 (Priority: P2)

管理员在管理页查看健康、成本、审计和一次 run 的追踪。这些入口从问答或侧栏可达，不再叫“高级设置”藏起来。

**Why this priority**: 运维闭环是产品的一部分，但不应打断首次使用者。

**Independent Test**: 平台管理员能打开健康与审计；浏览者看不到平台用户管理。

**Acceptance Scenarios**:

1. **Given** 平台管理员，**When** 打开管理页，**Then** 看到健康、成本、审计和运行记录。
2. **Given** 浏览者，**When** 尝试打开平台用户管理，**Then** 页面不提供该入口，API 仍拒绝。

### Edge Cases

- 无权限角色看到禁用按钮和原因，而不是空白页。
- API 不可用时，顶栏状态芯片显示不可用，页面不假装任务已完成。
- 窄屏下侧栏收成横向图标，主路径仍可走完。

## Requirements

- 默认导航顺序：登录 → 空间 → 云端模型 → 资料 → 任务/索引 → 问答 → 证据/检索实验 → 管理。
- 只有一套视觉变量：画布、表面、文字、线条、主色、间距、成功/警告/失败。
- 页面不存储正文、embedding、secret 或自由文本 citation。
- 前端角色只控制导航；授权以服务端 `space_id` 为准。

## Success Criteria

- 新使用者可以只靠页面文案，不读内部 UUID，完成一次带引用问答。
- 桌面与窄屏都能走完主路径。
- 现有 Web 旅程测试在选择器更新后仍然覆盖登录、空间、导入、索引、问答。
