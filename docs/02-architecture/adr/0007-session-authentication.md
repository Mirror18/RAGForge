# ADR-0007：浏览器使用服务端 Session

- Status: Accepted
- Date: 2026-08-12

## Context

企业内部 Web 需要立即撤销、权限变更即时生效和简单安全边界。浏览器长期 JWT 会增加吊销、刷新和泄漏治理复杂度。

## Decision

本地账号使用服务端 Session，Session 数据放 Valkey，浏览器只保存 HttpOnly/Secure/SameSite Cookie，并实施 CSRF 防护。机器访问使用独立的可吊销 service token。

## Consequences

- 权限撤销直接可靠。
- 多实例依赖共享 Session store。
- API token 和 Web Session 必须走不同认证链与测试集。

