# 上游复用登记表

## 1. 使用说明

状态：`CANDIDATE`、`APPROVED`、`REJECTED`、`IN_USE`、`REMOVED`。只有 `APPROVED` 后才能复制源码；正式 dependency 也要完成许可证和 SBOM 检查。

| ID | 上游 | 精确版本/Commit | 原路径/模块 | License | 计划方式 | 状态 | 进入位置 | 修改/义务 |
|---|---|---|---|---|---|---|---|---|
| UR-001 | [Spring AI](https://github.com/spring-projects/spring-ai) | 编码开始时锁定 | BOM/starters | Apache-2.0 | Maven dependency | CANDIDATE | Java adapters | 保留依赖 Notice，隔离框架类型 |
| UR-002 | [Spring AI Alibaba Extensions](https://github.com/spring-ai-alibaba/spring-ai-extensions) | 编码开始时锁定 | selected document readers | Apache-2.0 | Maven dependency | CANDIDATE | ingestion adapters | 逐模块兼容性/许可证检查 |
| UR-003 | [RAGFlow](https://github.com/infiniflow/ragflow) | Phase 0 后锁定 | parent-child / retrieval tests | Apache-2.0 | design reference; selective code only if justified | CANDIDATE | TBD | 若复制，记录每个文件与 NOTICE |
| UR-004 | [AnythingLLM](https://github.com/Mintplex-Labs/anything-llm) | Phase 0 后锁定 | workspace/provider UX | MIT | design reference | CANDIDATE | docs/backlog | 若复制片段保留 copyright |
| UR-005 | [Promptfoo](https://github.com/promptfoo/promptfoo) | CI 建设时锁定 | CLI/config | MIT | dev/CI tool | CANDIDATE | tests/evaluation | 不作为生产核心依赖 |
| UR-006 | [Langfuse](https://github.com/langfuse/langfuse) | llmops profile 时锁定 | OTel/API, MIT core only | Mixed by directory | optional external service | CANDIDATE | deploy/compose | 禁止复制 EE/商业许可代码 |
| UR-007 | [Dify](https://github.com/langgenius/dify) | N/A | product patterns | Modified Apache | reference only | REJECTED for code | docs only | 不复制源码 |
| UR-008 | [FastGPT](https://github.com/labring/FastGPT) | N/A | product patterns | Modified license | reference only | REJECTED for code | docs only | 不复制源码 |
| UR-009 | [MaxKB](https://github.com/1Panel-dev/MaxKB) | N/A | product patterns | GPL-3.0 | reference only | REJECTED for core code | docs only | 不复制源码 |
| UR-010 | [Open WebUI](https://github.com/open-webui/open-webui) | N/A | local model UX | Custom license | reference only | REJECTED for code | docs only | 不复制源码 |

## 2. 源码复制审批模板

```text
Reuse ID:
Business/technical reason:
Why dependency/API is insufficient:
Repository URL:
Commit SHA:
Original paths:
License file URL at the same commit:
Copyright/NOTICE obligations:
Target paths:
Modifications:
Upgrade/patch owner:
Security review:
Approval date:
```

## 3. 当前声明

当前所有可用项仍为 `CANDIDATE`，本仓库没有 vendored third-party source。开始编码时精确锁定版本并更新 `THIRD_PARTY_NOTICES.md`、`licenses/` 和 SBOM。
