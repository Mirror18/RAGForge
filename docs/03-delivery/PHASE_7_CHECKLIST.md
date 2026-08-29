# Phase 7 Checklist：Linux 交付与可公开准备

- 状态：`in-progress`
- 基线日期：2026-08-29
- 当前功能基线：`f6b016840e946ea314cdaf4812c196dcea8ca491`
- 目标环境：干净 Ubuntu 24.04、Docker Engine + Compose plugin
- 执行计划：[`PHASE_7_EXECUTION_PLAN.md`](../08-records/phase-7/PHASE_7_EXECUTION_PLAN.md)

历史 Phase 6 CI 只能证明历史提交，不能替代当前 Phase 7 头提交。所有验收仅使用公开合成数据，不读取个人 notes，不启用未经空间授权的云端出境，也不对生产数据库执行迁移或回滚。

## 已进入主线的实现

- [x] `app` profile 统一构建 Server、Worker、Web；worker 镜像具备可执行启动入口。
- [x] 首次使用入口可引导空间完成 RAG 配置，避免依赖手工预置资源。
- [x] candidate index 按 `space_id` 汇总当前空间 active 文档，不以单文档覆盖空间索引。
- [x] 长文 child chunk 在 embedding 前继续受控拆分，避免模型输入上限导致整批摄取失败。
- [x] Linux 关键词查询的拒答与证据相关性使用真实 evidence material 校验，并有定向回归测试。

以上勾选仅表示实现已进入 `main`，不等于 Phase 7 交付验收完成。

## 必须完成的交付门槛

- [ ] P7-CI-01：当前候选提交在 Linux CI 通过格式、架构、契约、Maven、Web、Compose、secret、Markdown link、SBOM 和 Grype 门禁。
- [ ] P7-DEPLOY-01：从干净 Ubuntu 24.04 按文档构建并启动 core + `app` profile，全部服务达到 health/readiness。
- [ ] P7-SMOKE-01：使用公共合成 fixture 完成注册/登录、建空间、LOCAL_ONLY 配置、导入、异步摄取、candidate 验证/发布、带结构化引用问答、历史/归档与跨空间拒绝。
- [ ] P7-OBS-01：叠加 observability profile 后，Dashboard、日志/trace 脱敏和规定告警可由 Runbook 定位。
- [ ] P7-SEC-01：应用容器以非 root、最小 capability、受控写路径和资源限额运行；Secret 不进入镜像、Compose 展开结果、日志或证据。
- [ ] P7-SUPPLY-01：发布候选应用及基础镜像固定到不可变 digest，生成目标镜像 SBOM，严重漏洞均已修复或有明确接受记录；Notice/reuse register 一致。
- [ ] P7-UPGRADE-01：以合成数据从上一兼容基线升级，验证 schema/对象/Qdrant/引用一致性；在兼容矩阵允许范围内完成应用回滚和数据恢复演练。
- [ ] P7-DOC-01：安装、配置、Secret、备份、恢复、升级、回滚、故障处理命令由第二执行者按原文复现，无隐含本机路径或个人数据依赖。
- [ ] P7-PUBLIC-01：secret、个人信息、Obsidian 内容、生产数据、raw prompt、许可证、Notice、仓库历史与大文件检查通过；根级许可证仍需单独人类决定。

## 退出条件

只有以上门槛全部有仓库内不可变证据，且相关 CI 对同一候选 SHA 全绿，Phase 7 才能标记完成。创建 release、接受根级许可证、执行生产迁移仍需用户显式批准；完成清单本身不授权这些高风险动作。
