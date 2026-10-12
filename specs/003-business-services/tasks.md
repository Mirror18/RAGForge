# Tasks: 按业务收拢的服务

- [x] T001 撰写 ADR-0014（已 Accepted），并更新架构/技术栈文档
- [x] T002 根 `pom.xml` 引入 Spring Cloud Alibaba 2025.0.0.0，Boot 收到 3.5.0
- [x] T003 为 `backend/shared|gateway|space|knowledge|answer` 建立 README 服务地图
- [ ] T004 ADR Accepted 后在 server 内按四层收包
- [ ] T005 拆 Maven 模块并用架构测试锁跨业务 Repository
- [ ] T006 接入 Nacos 与 gateway；浏览器只访问 gateway
- [ ] T007 删除独立 `backend/ai-runtime` 部署面，能力并入 knowledge/answer infrastructure
