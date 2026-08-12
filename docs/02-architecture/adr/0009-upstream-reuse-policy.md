# ADR-0009：上游项目复用政策

- Status: Accepted
- Date: 2026-08-12

## Context

GitHub 成熟项目可显著减少试错，但直接 Fork 或不记录复制来源会引入许可证、升级和学习价值问题。部分项目使用修改后的许可证或 GPL。

## Decision

优先顺序：官方依赖/API → 参考架构与测试思想 → 小范围复制允许许可证源码 → 最后才考虑 Fork。复制前记录 repo、commit SHA、原路径、许可证、修改和 Notice；CI 生成 SBOM 并执行依赖/许可证扫描。

允许候选：Apache-2.0 的 Spring AI、Spring AI Alibaba、RAGFlow；MIT 的 AnythingLLM、Promptfoo。Dify、FastGPT、Open WebUI 的附加限制代码只作参考；MaxKB GPL 代码不进入核心专有兼容仓库；Langfuse 仅使用 MIT core/API/OTel 边界。

## Consequences

- 来源、义务和升级边界清晰。
- 复用前增加一次合规检查。
- “公开可见”不再被误认为“可任意复制”。

## References

- [调研报告](../../07-research/GITHUB_BENCHMARK.md)
- [复用登记表](../../07-research/UPSTREAM_REUSE_REGISTER.md)
