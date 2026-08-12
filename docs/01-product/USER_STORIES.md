# 用户故事与验收示例

## 1. Platform Admin

### US-PA-01 登记模型提供商

作为平台管理员，我希望登记 Ollama 或 OpenAI-compatible 端点并检查能力，以便空间管理员只能选择实际可用的模型。

验收：

- 凭据加密保存且 API 不回显。
- 系统分别测试鉴权、chat、stream、tool、embedding 和 usage。
- 测试结果包含时间、错误分类和脱敏响应摘要。
- 模型声明能力与实测不符时不可发布 Profile。

### US-PA-02 审计一次回答

作为平台管理员，我希望通过 correlation ID 看到一次回答的所有阶段，以便排障和核算成本。

验收：Run、Steps、retrieval profile、index、prompt、model、tool、token、cost、retry 和 cancel 均可关联。

## 2. Space Admin

### US-SA-01 控制云端出境

作为空间管理员，我希望默认禁止空间内容发送到云端，并在明确确认风险后允许指定 route。

验收：

- 开关默认关闭，有权限和审计要求。
- 云 route 必须在允许名单且能力兼容。
- 关闭后，已有会话下一次调用也必须被服务端阻止。
- 不发生静默 local-to-cloud failover。

### US-SA-02 管理成员

验收：Viewer 不能修改数据源；Editor 不能变更空间成员或出境策略；Space Admin 不能获取平台级管理权限。

## 3. Editor

### US-ED-01 同步 Obsidian Git 仓库

验收：

- 保存远程、分支、commit checkpoint 和同步时间。
- 只读拉取，不向源仓库 push。
- YAML、标题、wikilink、相对路径、删除和重命名可追溯。
- include/exclude 支持 Windows 与 Linux 路径规范化。

### US-ED-02 审核分块

验收：可查看 parent-child 结构、token、引用锚点和 vector 状态；人工编辑形成 override，源更新时提示复审，不静默覆盖。

## 4. Viewer

### US-VW-01 带引用问答

验收：

- 回答引用只来自当前空间已发布索引。
- 点击引用可定位文档版本和标题/页码。
- 无可靠证据时明确说明未知。
- Viewer 无法通过修改请求参数读取其他空间内容。

### US-VW-02 取消流式回答

验收：取消后状态为 `CANCELLED`，上游调用尽快终止，已显示文本保留 cancelled 标识，usage ledger 不重复计费。

## 5. Developer / Operator

### US-OP-01 恢复索引任务

验收：消息重投不产生重复文档或向量；超过重试次数进入 DLQ；修复后可按 job 重放；每次尝试可观察。

### US-OP-02 恢复备份

验收：在隔离环境按 Runbook 恢复 PostgreSQL、对象和 Qdrant，验证引用一致性，并记录实际 RPO/RTO。
