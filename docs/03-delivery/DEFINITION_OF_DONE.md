# 完成定义（Definition of Done）

## 1. 任何变更

- 需求/Issue 有明确验收条件和范围外说明。
- 代码可读、格式化、无新的静态检查错误。
- 单元和相关集成测试通过，失败路径被覆盖。
- 没有密钥、真实客户内容和不必要的个人信息进入仓库或日志。
- 用户可见、运维可见、接口或行为变化已更新文档。
- 新依赖完成许可证、漏洞和维护活跃度检查。

## 2. API 或事件变更

- OpenAPI/Schema 先更新并通过 lint。
- breaking change 检查和兼容/迁移说明完成。
- producer/consumer contract tests 通过。
- 错误、幂等、分页、权限和审计行为有测试。

## 3. 数据模型变更

- Flyway migration 可在生产规模估算内执行。
- 说明锁、回填、兼容窗口、备份和回滚策略。
- 新旧应用并行窗口经过测试。
- 敏感数据分类、保留和删除路径已定义。

## 4. RAG 行为变更

适用于 parser、chunker、embedding、index、retrieval、reranker、prompt、model 或 tool：

- 配置产生新不可变版本。
- 评估集上有 baseline/candidate 对照和显著退化说明。
- 引用、拒答、空间隔离和提示注入回归测试通过。
- latency、token 和估算成本变化已记录。
- 可从 Run 重现所用版本，不依赖未记录环境状态。

## 5. 可部署能力

- 镜像可重复构建并锁定基础镜像。
- 健康、就绪、资源限额、优雅关闭和日志已验证。
- Dashboard/alert/Runbook 覆盖主要故障。
- 配置、Secret、升级、回滚、备份恢复文档可执行。
- staging 冒烟、E2E、性能和安全门槛通过。

## 6. Phase 完成

- 路线图退出条件逐项有证据链接。
- 风险表和追溯矩阵更新。
- 新 ADR 完成，旧 ADR 必要时标记 Superseded。
- Retrospective 记录 Keep / Problem / Try、质量数据和下一阶段改进。
- 没有未说明的 P0/P1 问题进入下一阶段。

