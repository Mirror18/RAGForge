# ADR-0002：Java AI 版本基线

- Status: Accepted
- Date: 2026-08-12

## Context

早期设想使用 Spring Boot 4.1 / Spring AI 2.0，但调研时 Spring AI Alibaba Extensions 已发布组合仍建立在 Boot 3.5.x / Spring AI 1.1.x 上。项目重视生态成熟度和可直接利用的文档 reader。

## Decision

首个实现基线采用 Java 21、Spring Boot 3.5.x、Spring AI 1.1.x，并在编码开始时通过 BOM/依赖树选择精确 patch。Spring AI Alibaba Extensions 只引入通过兼容性测试的模块。

## Consequences

- 更容易复用成熟 starter 和官方/社区示例。
- 暂不使用 Boot 4 / Spring AI 2 的新 API。
- 所有框架对象隔离在 adapter，未来升级不会污染领域模型。

## References

- [Spring AI](https://github.com/spring-projects/spring-ai)
- [Spring AI Alibaba Extensions](https://github.com/spring-ai-alibaba/spring-ai-extensions)
- [Extensions parent POM](https://github.com/spring-ai-alibaba/spring-ai-extensions/blob/main/pom.xml)
