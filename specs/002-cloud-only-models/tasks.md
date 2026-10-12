# Tasks: 仅云端模型

- [x] T001 前端去掉本地/云端切换，Chat 路线只接受 CLOUD
- [x] T002 管理页去掉 OLLAMA 类型和 LOCAL egress 作为 Chat 选项
- [x] T003 合成 fixture 改为 OPENAI_COMPATIBLE / CLOUD
- [x] T004 更新产品、架构、风险、追溯中所有“必须支持 Ollama”的句子
- [ ] T005 ADR-0014 Accepted 后删除 `OllamaProviderAdapter`、Compose profile 与 `-CheckOllama`
- [ ] T006 重跑出境隔离与 provider HTTP 测试
