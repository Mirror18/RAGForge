# 项目工作约定

## 1. 单一事实来源

- 产品范围：`docs/01-product/PRD.md`
- 架构决策：`docs/02-architecture/adr/`
- API 与事件：`contracts/`
- 路线和里程碑：`docs/03-delivery/ROADMAP.md`
- 质量阈值：`docs/04-quality/`
- 风险：`docs/08-records/RISK_REGISTER.md`
- 上游代码复用：`THIRD_PARTY_NOTICES.md` 与复用登记表

聊天记录、临时草稿和口头结论不能代替以上资产。

## 2. 决策状态

- `Proposed`：建议，尚不可作为实现依据。
- `Accepted`：已经接受，实施应遵守。
- `Deprecated`：仍可见但不再推荐。
- `Superseded`：由新 ADR 替代，保留历史。

## 3. 版本化对象

以下对象修改后必须产生新版本，不在原记录上静默覆盖：

- Pipeline Definition、Parser Profile、Chunking Profile。
- Retrieval Profile、Prompt Template、Prompt Version。
- Model Profile、Space Binding、Index Version。
- Evaluation Dataset、Evaluation Run。

## 4. 里程碑评审

每个 Phase 结束至少回答：

1. 可演示的用户结果是什么？
2. 验收证据放在哪里？
3. 哪些假设被证实或推翻？
4. 安全、性能、成本和运维债务新增了什么？
5. 是否触发架构或产品范围更新？
6. 下一阶段进入条件是否满足？

## 5. 环境原则

- Local：允许样本数据、本地 Ollama 和宽松调试，仍禁止提交秘密。
- CI：使用固定样本、Mock/小模型，结果可复现。
- Staging：模拟生产拓扑和安全策略，使用合成或脱敏数据。
- Production：最小权限、备份、告警、审计和变更审批全部启用。
