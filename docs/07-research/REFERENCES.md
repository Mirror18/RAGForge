# 参考资料

> 访问日期：2026-08-12。外部资料只支持对应技术结论；版本化实现必须记录精确 release/commit。

## 1. 核心框架与扩展

- [Spring AI GitHub repository](https://github.com/spring-projects/spring-ai) — Java AI 模型、向量、工具和观测抽象；Apache-2.0。
- [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba) — Java AI 应用生态参考；Apache-2.0。
- [Spring AI Alibaba Extensions](https://github.com/spring-ai-alibaba/spring-ai-extensions) — document readers 等扩展；Apache-2.0。
- [Extensions parent POM](https://github.com/spring-ai-alibaba/spring-ai-extensions/blob/main/pom.xml) — 开始实现前用于核对 Spring Boot/Spring AI 兼容基线。
- [ObsidianDocumentReader](https://github.com/spring-ai-alibaba/spring-ai-extensions/blob/main/document-readers/spring-ai-alibaba-starter-document-reader-obsidian/src/main/java/com/alibaba/cloud/ai/reader/obsidian/ObsidianDocumentReader.java) — 基础 Obsidian reader 实现参考。
- [ObsidianResource](https://github.com/spring-ai-alibaba/spring-ai-extensions/blob/main/document-readers/spring-ai-alibaba-starter-document-reader-obsidian/src/main/java/com/alibaba/cloud/ai/reader/obsidian/ObsidianResource.java) — metadata/wikilink/PARA 等实现参考。

## 2. 成熟 RAG 产品

- [RAGFlow](https://github.com/infiniflow/ragflow) — 文档摄取、RAG 产品和测试基准；Apache-2.0。
- [RAGFlow child chunking strategy](https://github.com/infiniflow/ragflow/blob/main/docs/guides/dataset/configure_child_chunking_strategy.md) — 父子分块产品设计参考。
- [RAGFlow retrieval test](https://github.com/infiniflow/ragflow/blob/main/docs/guides/dataset/run_retrieval_test.md) — Retrieval Playground 参考。
- [AnythingLLM](https://github.com/Mintplex-Labs/anything-llm) — 本地优先和 workspace 体验；MIT。
- [Dify](https://github.com/langgenius/dify) — 工作流/模型/知识产品参考；采用含附加条件的许可。
- [FastGPT](https://github.com/labring/FastGPT) — 中文知识库和流程体验参考；引入前核对附加许可。
- [MaxKB](https://github.com/1Panel-dev/MaxKB) — 企业知识库产品参考；GPL-3.0。
- [Open WebUI](https://github.com/open-webui/open-webui) — 本地聊天 UI 参考；自定义许可边界需复核。

## 3. 评估与可观测性

- [Promptfoo](https://github.com/promptfoo/promptfoo) — prompt/model evaluation 与 red-team；MIT。
- [Langfuse](https://github.com/langfuse/langfuse) — 可选 LLM observability；仓库部分目录许可不同。
- [Langfuse: Spring AI integration](https://langfuse.com/integrations/frameworks/spring-ai) — 使用 OpenTelemetry 接入的官方说明。

## 4. 通用工程和安全

- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457) — REST error format。
- [OpenTelemetry](https://opentelemetry.io/docs/) — traces/metrics/logs 标准。
- [OWASP ASVS](https://owasp.org/www-project-application-security-verification-standard/) — Web 应用安全验证基线。
- [OWASP Top 10 for LLM Applications](https://genai.owasp.org/llm-top-10/) — LLM 风险分类。
- [CycloneDX](https://cyclonedx.org/) — SBOM 格式和工具生态。
- [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) 与 [Semantic Versioning](https://semver.org/) — 发布记录约定。

## 5. 引用纪律

- 技术事实优先引用官方仓库、官方文档、标准或论文。
- GitHub `main` 链接用于调研导航；实际复用记录必须换为包含 commit SHA 的永久链接。
- 不把 star 数、最新 release 和当前框架版本当作稳定事实；需要使用时注明查询日期。
