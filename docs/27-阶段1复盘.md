# Phase 1 复盘：工程与领域骨架

- 日期：2026-08-12
- 范围：Maven/Java、Vue/TypeScript、Python runtime、core Compose、OpenAPI/event contract、身份/空间/Session/CSRF、Flyway、Outbox/audit/idempotency、CI 基础门禁
- 基线：`c9b3d6c5880c58e36e6e8c3af2914502118c9cbf`

## Keep

- 先固定契约，再并行实现服务端和部署边界；契约修正通过原 Agent follow-up 完成，避免主分支临时修补。
- 每个任务使用独立 branch/worktree，合并保留任务提交和审查轨迹。
- Compose 的 project/network/volume/port 派生规则让 API 黑盒和用户已有 `gs-*` 容器隔离。
- 跨空间用例验证“不泄漏空间存在性/名称”，而不是只验证 UI 不显示。
- 本地 Python 门禁、真实 Compose 黑盒和 Java Testcontainers 测试形成互补证据，不把任一种替代另一种。

## Problem

- Testcontainers 1.21.3 与 Docker Engine 29.6.1 的 Windows npipe 兼容性曾导致 HTTP 400；升级至 1.21.4 并显式管理容器生命周期后，本地全量 Maven 集成测试已通过。
- 首次 GitHub Run 暴露 Windows 生成的 npm lockfile 缺少 Linux Rollup optional dependency；补齐跨平台 lockfile 后第二次 Run `31616214088` 全部通过，并生成 SBOM artifact `9149315317`。
- 本阶段最小 Session 骨架已使用 Valkey，但完整 service token 生命周期、限速/MFA、生产 TLS 和 secret store 仍未实现，不能提前宣称安全基线全部完成。
- 运行时镜像仍使用可读 tag；生产 immutable digest、非 root、资源限额和完整观测 profile 归入后续部署阶段。

## Try

- Phase 2 开始前先在 Linux runner 复跑 `mvn test`，将 Docker/Testcontainers 版本和镜像 tag/digest 输出为 CI artifact。
- 保持 CI Run `31616214088` 作为 Phase 1 基线；后续依赖变更继续要求 SBOM/Grype job 通过并记录 artifact。
- 把本阶段的 API smoke 保持为可重复合成测试；后续加入浏览器 E2E 时复用同一空间隔离场景和不可伪造 provenance 断言。
- 在引入 provider、connector 或 content query 前，要求 `space_id`、出境 route、审计和失败路径的 contract test 先合入。

## 质量与安全数据

- Python contract tests：14/14 通过；Phase 0 回归测试：3/3 通过；Phase 1 API smoke：3/3 通过。
- 前端 `npm ci`、类型检查、生产构建和 High 级 audit 通过。
- Java compile、UUIDv7/SecurityConfig 定向测试、worker 测试和全量 Maven Testcontainers 测试通过；全量结果为 8 tests、0 failures/errors。
- 真实 Compose 黑盒覆盖注册、登录别名、Session/current、CSRF、Valkey Session、空间 RBAC、跨空间 no-leak、幂等 409、UUIDv7 correlation；health 与 PostgreSQL backup smoke 通过。
- 未发现新 P0/P1 代码缺陷；Linux CI、SBOM/Grype、Maven Testcontainers 和 npm lockfile 均取得成功证据，Phase 1 可正式关闭。

## Phase 2 入口

Phase 2 可以基于当前 server/contract/Compose 骨架开始 Provider Registry 和 Run/Step 设计。任何新功能不得绕过现有 `space_id`、CSRF、Idempotency-Key、audit/outbox 和云端出境策略边界。
