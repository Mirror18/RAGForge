# 开源软件合规

## 1. 引入闸门

每个新依赖、容器镜像或复制源码在合并前回答：

1. 上游仓库、发布版本/commit 和原始路径是什么？
2. SPDX license 及附加条款是什么？
3. 以依赖、独立服务、修改源码、复制片段还是仅参考使用？
4. 当前私有部署、未来二进制/源码分发和可能 SaaS 是否允许？
5. 需要保留哪些 copyright、LICENSE、NOTICE、源码或修改说明？
6. 维护状态、漏洞、替代方案和升级成本如何？

## 2. 许可策略

- Apache-2.0/MIT/BSD：通常可用，但必须履行 Notice/copyright 等条件。
- Weak copyleft：逐个评估链接和分发边界。
- GPL/AGPL：默认不进入希望保留宽松商业分发选择的核心进程；需要独立法律/架构决策。
- 修改后的“开源”许可证、品牌/多租户/SaaS 限制：只作参考，除非明确接受其商业限制。
- 仓库内不同目录可能不同许可证，不能只看根目录 badge。

## 3. 记录

- 依赖：lock/BOM + SBOM + dependency report。
- 复制源码：`third_party/`、`licenses/`、`THIRD_PARTY_NOTICES.md`、复用登记表四处一致。
- 修改文件保留上游 header，增加项目修改说明；不要删除作者信息。
- 精确 commit SHA 不用 `main` 链接代替。

## 4. 当前边界

- Spring AI、Spring AI Alibaba/Extensions：优先依赖方式使用 Apache-2.0 组件。
- RAGFlow：可参考/选择性复用 Apache-2.0 算法与测试，需登记和归属。
- AnythingLLM、Promptfoo：MIT，可使用模式或依赖，保留 copyright。
- Dify、FastGPT、Open WebUI：存在附加限制，源码不复制。
- MaxKB：GPL-3.0，只参考产品和公开行为。
- Langfuse：仅 MIT core/API/OTel 边界；避开标记 EE/商业许可目录。

许可证和仓库内容会变化，真正引入时必须重新核对上游的精确版本；本文件不是法律意见。

