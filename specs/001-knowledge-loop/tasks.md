# Tasks: 页面知识闭环

**Input**: [plan.md](./plan.md), [spec.md](./spec.md)

## Phase 1: Setup

- [x] T001 将 Vue 视图按 `views/access|space|knowledge|answer|admin` 移动并修正相对导入
- [x] T002 [P] 新增 `frontend/ragforge-web/src/views/README.md`
- [x] T003 把 `App.vue` 两套样式收成 `styles/app.css`，由 `main.ts` 引入

## Phase 2: Foundational

- [x] T004 侧栏改为业务顺序：空间、模型、资料、问答、证据、检索、管理
- [x] T005 页面标题与说明改成对应步骤的下一步提示

## Phase 3: User Story 1–4

- [x] T006 [US1] 无空间时仍进入空间页；切换空间清空问答上下文
- [x] T007 [US2] 向导去掉 Ollama/本地切换，未配置云端时给出明确下一步
- [x] T008 [US3] 问答与引用跳转保持 `space_id` 上下文
- [x] T009 [US4] 管理入口从“高级设置”改为“运行与管理”
- [x] T010 更新 Playwright 选择器与合成 fixture 中的云端 provider
- [ ] T011 在浏览器走登录到带引用回答；记录桌面与窄屏结果

**Checkpoint**: 主路径可演示；单元与 E2E 选择器与新导航一致
