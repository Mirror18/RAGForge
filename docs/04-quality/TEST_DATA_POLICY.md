# 测试数据政策

## 1. 数据类别

- Public synthetic：可提交和公开，默认测试数据。
- Licensed sample：许可证允许存储和再分发，保留来源与许可。
- Private local：个人 Obsidian 等，仅本地只读使用，不提交、不上传 CI。
- Customer-like sensitive：脱敏后才可进入受控 staging，不进入公开 artifact。

## 2. 规则

- `fixtures/` 只放 Public synthetic 或明确可再分发的数据。
- 评估答案不得意外包含真实密钥、地址、账号或个人隐私。
- CI 日志和失败快照对文档内容限长、脱敏。
- 云模型实验逐数据集登记出境批准，默认禁止 Private local。
- 删除测试数据时，同时清理 PostgreSQL、对象、Qdrant、缓存、Trace 和 CI artifacts。

## 3. 可复现性

大数据集不直接进 Git 时，提供固定 seed 的生成器、manifest、文件 hash、大小和版本。每个 Evaluation Run 保存 dataset manifest hash。
