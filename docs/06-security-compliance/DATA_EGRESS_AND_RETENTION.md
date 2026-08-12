# 数据出境、保留与删除

## 1. 默认策略

每个知识空间默认 `LOCAL_ONLY`。任何原文、chunk、query、context、prompt、tool output 或 embedding 输入发送到外部 Provider 前，必须通过服务端 Data Egress Policy。

## 2. 出境决策输入

- space cloud egress setting 和批准的 model route。
- 数据分类、Provider/region、用途和能力。
- 调用类型：chat、embedding、rerank、OCR、web fetch。
- 本次 evidence/tool 来源和敏感标记。
- 当前策略版本、用户权限和审计上下文。

决策结果为 ALLOW/DENY，携带 reason code 和 policy version。DENY 不可由重试或 fallback 绕过。

## 3. 管理操作

启用/修改云端出境仅 Space Admin 可操作，界面展示 Provider、预计发送内容、保留/训练政策由谁承担以及风险提示。操作写安全审计。禁用后新调用立即阻止；运行中的下一模型/工具 Step 重新检查。

## 4. 保留基线

| 数据 | 默认保留 |
|---|---:|
| Conversation / messages | 90 天 |
| 原始 debug prompt/response | 7 天，可关闭 |
| Audit events | 365 天建议值 |
| Session | 到期/吊销即失效 |
| 失败临时文件 | 尽快清理，最长周期另定 |
| 已退休 index | 至少 24 小时，之后按引用/回滚政策 |

业务记录中的 hash、版本、token、成本可比原始文本保留更久，但仍受隐私和审计政策约束。

## 5. 删除工作流

删除不是单表操作。Deletion Job 追踪 PostgreSQL、Object Storage、Qdrant、Valkey、搜索 projection、Trace/日志和备份例外。每个目标记录状态、attempt 和证据；完成后生成不含敏感内容的 deletion report。

备份中无法立即物理删除的数据，在恢复时重新应用 tombstone/delete ledger，且访问被立即撤销。

## 6. Obsidian 特别规则

个人 Obsidian 仓库属于 Private local：默认不允许云出境，不进入 GitHub、CI fixtures 或长期调试 artifacts。若以后批准某个脱敏空间使用云模型，必须以空间级清单和评估数据记录范围。

