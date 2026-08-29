# 安全基线

## 1. 安全目标

- 只有获授权用户和服务能访问对应空间内容。
- 本地数据不会未经明确批准发送给云端模型或网页工具。
- 上传文档、Git/Web 数据源和提示词不能获得代码执行或内网访问能力。
- 关键管理、出境、模型、数据和 Agent 行为可审计。
- 密钥、Session 和 service token 可轮换、吊销且不出现在日志。

## 2. 身份与访问

- 密码使用现代自适应 hash（实现时选择 Argon2id/bcrypt 参数并压测）。
- 首个平台管理员初始化默认关闭，必须使用独立的高熵一次性 Secret；禁止把“首个注册用户”自动提升为管理员。检查与创建必须在数据库事务锁内完成，完成后 fail-closed，Secret 不进入浏览器存储、响应、日志或审计。
- 登录限速、失败审计、Session rotation、退出/禁用立即吊销。
- Cookie：HttpOnly、Secure、SameSite；写操作 CSRF 防护。
- RBAC deny-by-default；管理接口不靠 UI 隐藏。
- service token 只存 hash，具有 scope、过期、吊销和 last-used。
- 高风险管理动作可要求重新认证，生产化后评估 MFA/OIDC。

## 3. 数据保护

- TLS 终止和内部链路策略在部署阶段明确；生产凭据不得明文穿越不可信网络。
- Provider/API/数据源凭据使用 envelope encryption 或外部 secret store，主密钥不进 DB。
- Provider connection 登记和实测仅平台管理员可执行；测试只使用固定合成样本。云端探测逐次显式确认并限制为 HTTPS 公网地址，本地探测限制为本地/私网地址；DNS、地址类别和出境等级在每次探测前重新校验，结果不保存请求/响应正文、Header 或 credential reference。
- 对象 key 使用不可猜 ID，不使用用户文件名作为授权依据。
- 日志、Trace、错误和审计对 prompt/document/headers 脱敏和限长。
- 数据分类决定云端 route、保留、导出和删除策略。

## 4. 上传与解析

- 文件扩展名、MIME sniff、magic bytes、大小、解压后大小和嵌套深度同时校验。
- 上传先隔离，病毒扫描通过后才进入解析。
- parser 在非 root、最小文件/网络权限和资源限额下运行。
- 防 zip bomb、XML 外部实体、路径穿越、公式/宏和恶意媒体。
- 解析失败不回显栈、内部路径或文档敏感片段。

## 5. Web/Agent 安全

- URL 白名单和 DNS 解析后 IP 检查；阻止 loopback、link-local、RFC1918、metadata endpoints。
- 每次 redirect 重新校验；限制 scheme、port、MIME、bytes、timeout 和 redirect count。
- 文档和网页中的指令始终是不可信数据，不能覆盖 system/tool policy。
- Tool 采用 schema、权限、空间、参数和输出验证；无任意 Shell/SQL/network。

## 6. 供应链

- 依赖锁定/BOM、Dependabot 或同类更新、SCA、SAST、secret scan、container scan。
- 生成 CycloneDX/SPDX SBOM，镜像与 release 绑定 commit/digest。
- 第三方源码复制遵守 [开源合规](OSS_COMPLIANCE.md)。
- CI 权限最小化，PR 代码不能读取生产 Secret。

## 7. 安全发布门槛

- 跨空间泄漏与未经授权云端出境测试 0 失败。
- 无未接受的 Critical/High 可利用漏洞；例外有 owner、期限和补偿控制。
- 备份恢复、密钥轮换、账号禁用、service token 吊销完成演练。
- 风险较高的 prompt injection/SSRF/upload 用例进入持续回归。

设计参考后续实现时应核对最新 [OWASP ASVS](https://owasp.org/www-project-application-security-verification-standard/)、[OWASP Top 10 for LLM Applications](https://genai.owasp.org/llm-top-10/) 和所用框架官方安全指南。
