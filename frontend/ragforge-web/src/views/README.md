# 页面对契约

页面按业务闭环分目录。授权仍由后端按 `space_id` 强制执行。

| 目录 | 页面 | 主要契约 |
|---|---|---|
| `access/` | 登录、注册、平台管理员引导 | `/api/v1/auth/*`、bootstrap |
| `space/` | 空间、成员、账号 | `/api/v1/spaces`、members、users |
| `knowledge/` | 资料导入、来源、任务、索引、证据、检索实验 | sources、jobs、indexes、chunk-studio、retrieval playground |
| `answer/` | 带引用问答与历史 | conversations、runs、answers、citations |
| `admin/` | 云端模型与运行管理 | provider-connections、model-profiles、health、audit、runs |

共享客户端在 `src/api.ts` 与 `src/answer.ts`。一套视觉变量在 `src/styles/app.css`。
