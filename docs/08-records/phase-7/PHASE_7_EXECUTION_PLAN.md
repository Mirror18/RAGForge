# Phase 7 执行计划：Linux 交付与可公开准备

- Version: `phase7-plan.v1`
- Status: `in-progress`
- Baseline: `f6b016840e946ea314cdaf4812c196dcea8ca491`
- Checklist: [`PHASE_7_CHECKLIST.md`](../../03-delivery/PHASE_7_CHECKLIST.md)

## 目标与边界

本阶段把现有模块化单体、独立 ingestion worker 和 Web 应用交付为可复现的 Ubuntu 24.04 Compose 部署，并形成升级、回滚、供应链和公共化证据。不拆分新微服务，不默认启用云端 provider，不读取个人 notes，不创建 release，也不接受根级许可证。

## 执行顺序

| 工作包 | 状态 | 产物 | 验收要点 | 依赖 |
|---|---|---|---|---|
| P7-A 基线冻结 | in-progress | checklist、候选 SHA、环境清单 | 本地/远程 SHA 和 CI 一一对应；历史证据不冒充当前证据 | 无 |
| P7-B 镜像与 Compose 加固 | pending | Dockerfile/Compose、镜像清单 | non-root、digest、资源限额、health/readiness、Secret 注入、`space_id`/egress 边界不变 | P7-A |
| P7-C 干净环境部署 | pending | Ubuntu 部署记录、命令输出摘要 | core + app 可从文档启动；无本机绝对路径和隐式状态 | P7-B |
| P7-D 业务与观测验收 | pending | smoke、隔离、安全、Dashboard/Runbook 证据 | 合成数据闭环；结构化 citation/provenance；跨空间与未授权出境为零 | P7-C |
| P7-E 升级与回滚 | pending | 兼容矩阵、演练记录 | 备份可恢复；Flyway 前向迁移后只回滚兼容应用；不触碰生产 | P7-C |
| P7-F 供应链与公共化 | pending | SBOM、Grype、Notice/secret/public audit | 目标镜像和同一候选 SHA 可追溯；根许可证保留人类决策 | P7-B |
| P7-G 阶段闭环 | pending | retrospective、状态/风险/追溯更新 | Definition of Done 和 checklist 全满足；release 仍需单独批准 | P7-D/P7-E/P7-F |

## 当前已知实现增量

`00432a9` 修复 worker 容器启动；`dd6902f` 修复首次使用入口；`0d399c0` 将 candidate index 构建修正为空间级；`012010c`、`7e23ae5`、`12eaf94` 分别修复 Linux 查询误拒/无关证据、长文 embedding 超限和 material-backed 相关性校验。它们降低业务 smoke 失败概率，但尚未替代同一候选 SHA 上的 Linux 全量验收。

## 证据规则

- 每份 runtime 证据记录候选 SHA、镜像 digest、Ubuntu/Docker/Compose 版本、fixture 版本、开始/结束时间和结果。
- 证据只保存 hash、ID、计数、状态、耗时和脱敏错误，不保存 Secret、Cookie、raw prompt、文档正文或个人路径。
- 失败证据不得删除或改写为成功；修复后生成新版本并保留关联。
- 阶段闭环时同步 `PROJECT_STATUS.md`、`RISK_REGISTER.md`、`TRACEABILITY_MATRIX.md` 和 Phase 7 retrospective。
