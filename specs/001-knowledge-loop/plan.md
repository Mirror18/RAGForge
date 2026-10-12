# Implementation Plan: 页面知识闭环

**Branch**: `001-knowledge-loop` | **Date**: 2026-10-12 | **Spec**: [spec.md](./spec.md)

## Summary

把 [`frontend/ragforge-web/src`](../../frontend/ragforge-web/src) 从平铺视图改成按业务闭环分目录的 SPA：一套样式、一条导航、每个步骤都能看到状态和下一步。技术专业页（内容证据、检索实验）从索引或引用进入，不再做成另一套产品。

## Technical Context

**Language/Version**: TypeScript、Vue 3、Vite

**Primary Dependencies**: 现有 `frontend/ragforge-web`；不新增 UI 框架

**Storage**: 无浏览器机密存储；Session 仍是 HttpOnly Cookie

**Testing**: Vitest 单元、Playwright `tests/e2e/web-journeys.spec.ts`

**Target Platform**: 现代浏览器，桌面优先，窄屏可用

**Project Type**: Web SPA

**Constraints**: 不改公开 API 语义；不把授权下放到前端

## Constitution Check

- 页面是验收面：通过
- `space_id` 仍由 API 强制：通过
- 不引入第二个视觉系统：通过

## Project Structure

```text
frontend/ragforge-web/src/
├── main.ts
├── App.vue
├── styles/app.css
├── api.ts
├── router.ts
└── views/
    ├── README.md
    ├── access/AuthView.vue
    ├── space/PersonalSpaceView.vue
    ├── knowledge/{BusinessFlow,SourceLibrary,TaskCenter,IndexLifecycle,ChunkStudio,RetrievalPlayground}View.vue
    ├── answer/{Answer,ConversationHistory}View.vue
    └── admin/ControlCenterView.vue
```

## Phase 0 Research

现有 `App.vue` 叠了两套互相覆盖的样式；侧栏把向导、问答、证据、实验、设置拆开。先抽样式、改导航和目录，不重写每个表单的业务逻辑。

## Phase 1 Design

- 导航 IA 对应用户故事 1–4。
- 向导页去掉本地/云端双按钮，只保留云端配置状态。
- README 说明每个页面对应的契约路径。
