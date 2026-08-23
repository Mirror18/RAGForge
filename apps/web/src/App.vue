<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ApiError, type Anchor, type ChunkOverrideResponse, type ChunkOverrideTargetState, type CreateChunkOverrideRequest, type ChunkStudioProjection, type CurrentSession, type RetrievalExperiment, type Space, type TransitionChunkOverrideRequest, apiFetch, getCurrentSession, loginUser, logoutCurrentSession, registerUser } from "./api";
import AnswerView from "./AnswerView.vue";
import ControlCenterView from "./ControlCenterView.vue";
import AuthView from "./AuthView.vue";
import PersonalSpaceView from "./PersonalSpaceView.vue";

type View = "home" | "studio" | "playground" | "answer" | "control" | "profile";
type ControlSection = "spaces" | "providers" | "models" | "prompts" | "runs";
const view = ref<View>("home");
const controlSection = ref<ControlSection>("providers");
const apiStatus = ref<"checking" | "ready" | "unavailable" | "unauthenticated">("checking");
const checkedAt = ref("");
const session = ref<CurrentSession | null>(null);
const spaces = ref<Space[]>([]);
const selectedSpaceId = ref("");
const workspaceError = ref("");
const authMode = ref<"login" | "register">("login");
const authEmail = ref("");
const authPassword = ref("");
const authDisplayName = ref("");
const authLoading = ref(false);
const spaceCreating = ref(false);

const childChunkId = ref("");
const projection = ref<ChunkStudioProjection | null>(null);
const studioLoading = ref(false);
const studioError = ref("");
const replacementContentRef = ref("");
const replacementTextHash = ref("");
const overrideReason = ref("");
const transitionReason = ref("");
const studioNotice = ref("");

const query = ref("");
const indexVersionId = ref("");
const profileAId = ref("");
const profileAVersion = ref(1);
const compareProfileB = ref(false);
const profileBId = ref("");
const profileBVersion = ref(1);
const queryVectorText = ref("");
const playgroundLoading = ref(false);
const playgroundError = ref("");
const experiment = ref<RetrievalExperiment | null>(null);

const selectedSpace = computed(() => spaces.value.find((space) => space.spaceId === selectedSpaceId.value) ?? null);
const currentRole = computed(() => selectedSpace.value?.role ?? (session.value?.user.platformRole === "PLATFORM_ADMIN" ? "PLATFORM_ADMIN" : "未加载"));
const isViewer = computed(() => selectedSpace.value?.role === "VIEWER");
const canEdit = computed(() => !isViewer.value && Boolean(selectedSpaceId.value));
const statusText = computed(() => apiStatus.value === "ready" ? "会话与 API 可用" : apiStatus.value === "unauthenticated" ? "请登录后继续" : apiStatus.value === "unavailable" ? "API 不可用" : "正在检查会话");
const traceStages = [
  { key: "dense" as const, title: "Dense" },
  { key: "bm25" as const, title: "BM25" },
  { key: "rrf" as const, title: "RRF" },
  { key: "rerank" as const, title: "Rerank" },
];

function escapePathSegment(value: string): string { return encodeURIComponent(value.trim()); }
function formatDate(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(date);
}
function formatAnchor(anchor: Anchor): string {
  const parts = [anchor.headingPath.join(" / ")];
  if (anchor.pageNumber) parts.push(`第 ${anchor.pageNumber} 页`);
  if (anchor.sheet) parts.push(`工作表 ${anchor.sheet}`);
  if (anchor.slideNumber) parts.push(`第 ${anchor.slideNumber} 张幻灯片`);
  if (anchor.lineRange) parts.push(`行 ${anchor.lineRange.startLine}–${anchor.lineRange.endLine}`);
  if (anchor.tableCell) parts.push(`单元格 ${anchor.tableCell}`);
  return parts.filter(Boolean).join(" · ") || "未提供锚点";
}
function describeError(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError)) return fallback;
  const problem = error.problem;
  const permission = error.status === 401 ? "请先登录。" : error.status === 403 ? "当前角色或空间权限不允许此操作；前端提示不替代 API 授权。" : error.status === 404 ? "资源不存在，或不属于当前空间。请检查 spaceId 与资源 ID。" : error.status === 409 ? "请求与当前资源版本冲突，请重新读取后再试。" : error.status === 412 ? "资源版本已变化，请重新读取并确认 expectedVersion。" : error.status === 422 ? "请求字段未通过契约校验。" : "请稍后重试。";
  const correlation = error.correlationId ? ` correlationId: ${error.correlationId}` : "";
  return `${problem?.code ? `${problem.code}：` : ""}${problem?.detail ?? error.message} ${permission}${correlation}`;
}
function validateReplacementContentRef(value: string): string | null {
  if (!value) return "replacement contentRef 不能为空。";
  if (value.length > 512) return "replacement contentRef 不能超过 512 个字符。";
  if (/[\s\u0000-\u001f\u007f]/.test(value)) return "replacement contentRef 不能包含空白或控制字符。";
  if (/(?:full|raw|document)[_-]?(?:text|document|content)|embedding|vector/i.test(value)) return "replacement contentRef 不能包含正文、原文、文档内容、embedding 或 vector 等敏感字段。";
  return null;
}
function validateReplacementTextHash(value: string): string | null {
  if (!value) return "replacement textHash 不能为空。";
  if (value.length !== 64 || !/^[0-9a-fA-F]{64}$/.test(value)) return "replacement textHash 必须是 64 位 SHA-256 十六进制值。";
  return null;
}
function openControl(section: ControlSection): void {
  controlSection.value = section;
  view.value = "control";
}

async function checkApiHealth(): Promise<void> {
  apiStatus.value = "checking";
  workspaceError.value = "";
  try {
    const [current, page] = await Promise.all([getCurrentSession(), apiFetch<{ items: Space[]; nextCursor: string | null }>("/api/v1/spaces?limit=100")]);
    session.value = current;
    spaces.value = page.items;
    if (!selectedSpaceId.value || !spaces.value.some((space) => space.spaceId === selectedSpaceId.value)) selectedSpaceId.value = spaces.value[0]?.spaceId ?? "";
    apiStatus.value = "ready";
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      session.value = null;
      spaces.value = [];
      selectedSpaceId.value = "";
      apiStatus.value = "unauthenticated";
      workspaceError.value = "";
      return;
    }
    apiStatus.value = "unavailable";
    workspaceError.value = describeError(error, "无法读取当前会话或可见空间。请确认服务已启动并已登录。");
  } finally {
    checkedAt.value = new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false }).format(new Date());
  }
}

async function submitAuth(): Promise<void> {
  workspaceError.value = "";
  if (!authEmail.value.trim() || !authPassword.value) {
    workspaceError.value = "请填写邮箱和密码。";
    return;
  }
  if (authMode.value === "register" && !authDisplayName.value.trim()) {
    workspaceError.value = "注册时需要填写显示名称。";
    return;
  }
  authLoading.value = true;
  try {
    if (authMode.value === "register") {
      await registerUser({ email: authEmail.value.trim(), password: authPassword.value, displayName: authDisplayName.value.trim() });
    }
    await loginUser({ email: authEmail.value.trim(), password: authPassword.value });
    authPassword.value = "";
    await checkApiHealth();
  } catch (error) {
    workspaceError.value = describeError(error, authMode.value === "register" ? "注册或登录失败。" : "登录失败。");
  } finally {
    authLoading.value = false;
  }
}

async function logout(): Promise<void> {
  workspaceError.value = "";
  try { await logoutCurrentSession(); }
  catch (error) { workspaceError.value = describeError(error, "退出失败。"); return; }
  session.value = null; spaces.value = []; selectedSpaceId.value = ""; apiStatus.value = "unauthenticated";
}

async function createSpace(payload: { name: string; description: string }): Promise<void> {
  workspaceError.value = "";
  spaceCreating.value = true;
  try {
    const created = await apiFetch<Space>("/api/v1/spaces", { method: "POST", body: payload });
    spaces.value = [...spaces.value, created]; selectedSpaceId.value = created.spaceId; view.value = "profile";
  } catch (error) { workspaceError.value = describeError(error, "知识空间创建失败。"); }
  finally { spaceCreating.value = false; }
}
function openPersonalAction(action: "home" | "providers" | "models" | "prompts" | "runs"): void {
  if (action === "home") { view.value = "home"; return; }
  openControl(action);
}

async function loadChunk(): Promise<void> {
  studioNotice.value = ""; studioError.value = "";
  if (!selectedSpaceId.value || !childChunkId.value.trim()) { studioError.value = "需要先选择当前空间并填写 childChunkId。"; return; }
  studioLoading.value = true;
  try {
    projection.value = await apiFetch<ChunkStudioProjection>(`/api/v1/spaces/${escapePathSegment(selectedSpaceId.value)}/chunk-studio/children/${escapePathSegment(childChunkId.value)}`);
    replacementContentRef.value = projection.value.contentRef;
    replacementTextHash.value = projection.value.textHash;
    overrideReason.value = ""; transitionReason.value = "";
  } catch (error) { projection.value = null; studioError.value = describeError(error, "Chunk Studio projection 加载失败。"); }
  finally { studioLoading.value = false; }
}
function applyOverrideResponse(response: ChunkOverrideResponse): void {
  if (projection.value) projection.value = { ...projection.value, contentRef: response.contentRef, textHash: response.textHash, override: response.override };
  replacementContentRef.value = response.contentRef;
  replacementTextHash.value = response.textHash;
}
async function createOverride(): Promise<void> {
  studioNotice.value = ""; studioError.value = "";
  if (!projection.value || !canEdit.value) { studioError.value = isViewer.value ? "当前空间角色为 VIEWER，override 操作已禁用。" : "请先读取一个 child projection。"; return; }
  const contentRefError = validateReplacementContentRef(replacementContentRef.value);
  if (contentRefError) { studioError.value = contentRefError; return; }
  const textHashError = validateReplacementTextHash(replacementTextHash.value);
  if (textHashError) { studioError.value = textHashError; return; }
  if (!overrideReason.value.trim()) { studioError.value = "请填写 override reason。内容通过服务端 contentRef/hash 标识，不在此输入正文。"; return; }
  const body: CreateChunkOverrideRequest = { documentRevisionId: projection.value.documentRevisionId, contentRef: replacementContentRef.value, textHash: replacementTextHash.value, reason: overrideReason.value.trim() };
  studioLoading.value = true;
  try {
    const response = await apiFetch<ChunkOverrideResponse>(`/api/v1/spaces/${escapePathSegment(selectedSpaceId.value)}/chunk-studio/children/${escapePathSegment(projection.value.childChunkId)}/overrides`, { method: "POST", body });
    applyOverrideResponse(response); studioNotice.value = "override 已创建。state、createdBy 与时间戳由服务端审计。"; overrideReason.value = "";
  } catch (error) { studioError.value = describeError(error, "override 创建失败。"); }
  finally { studioLoading.value = false; }
}
async function transitionOverride(targetState: ChunkOverrideTargetState): Promise<void> {
  studioNotice.value = ""; studioError.value = "";
  const current = projection.value?.override;
  if (!projection.value || !current?.overrideId || !canEdit.value) { studioError.value = isViewer.value ? "当前空间角色为 VIEWER，状态流转已禁用。" : "当前没有可流转的 override。"; return; }
  if (!transitionReason.value.trim()) { studioError.value = "状态流转需要填写审计 reason。"; return; }
  const body: TransitionChunkOverrideRequest = { targetState, expectedVersion: current.version, reason: transitionReason.value.trim() };
  studioLoading.value = true;
  try {
    const response = await apiFetch<ChunkOverrideResponse>(`/api/v1/spaces/${escapePathSegment(selectedSpaceId.value)}/chunk-studio/children/${escapePathSegment(projection.value.childChunkId)}/overrides/${escapePathSegment(current.overrideId)}/transitions`, { method: "POST", body });
    applyOverrideResponse(response); transitionReason.value = ""; studioNotice.value = `override 已迁移为 ${targetState}。`;
  } catch (error) { studioError.value = describeError(error, "override 状态流转失败。"); }
  finally { studioLoading.value = false; }
}
function parseQueryVector(): number[] | undefined {
  if (!queryVectorText.value.trim()) return undefined;
  const values = queryVectorText.value.trim().split(/[\s,]+/).map(Number);
  if (values.length > 4096 || values.some((value) => !Number.isFinite(value))) throw new Error("queryVector 必须是最多 4096 个以空格或逗号分隔的有限数字。");
  return values;
}
async function runExperiment(): Promise<void> {
  playgroundError.value = ""; experiment.value = null;
  const validVersion = (value: number): boolean => Number.isInteger(value) && value >= 1;
  if (!selectedSpaceId.value || !query.value.trim() || !indexVersionId.value.trim() || !profileAId.value.trim() || !validVersion(profileAVersion.value)) { playgroundError.value = "需要填写当前空间、query、indexVersionId、profile A ID 和有效版本号。"; return; }
  if (compareProfileB.value && (!profileBId.value.trim() || !validVersion(profileBVersion.value))) { playgroundError.value = "启用 profile B 后，需要填写 profile B ID 和有效版本号。"; return; }
  let queryVector: number[] | undefined;
  try { queryVector = parseQueryVector(); } catch (error) { playgroundError.value = error instanceof Error ? error.message : "queryVector 格式无效。"; return; }
  playgroundLoading.value = true;
  try {
    experiment.value = await apiFetch<RetrievalExperiment>(`/api/v1/spaces/${escapePathSegment(selectedSpaceId.value)}/retrieval-playground/experiments`, { method: "POST", body: { query: query.value.trim(), indexVersionId: indexVersionId.value.trim(), profileA: { profileId: profileAId.value.trim(), version: profileAVersion.value, candidateOnly: true }, profileB: compareProfileB.value ? { profileId: profileBId.value.trim(), version: profileBVersion.value, candidateOnly: true } : null, ...(queryVector ? { queryVector } : {}) } });
    queryVectorText.value = "";
  } catch (error) { playgroundError.value = describeError(error, "Retrieval Playground 实验失败。"); }
  finally { playgroundLoading.value = false; }
}
onMounted(checkApiHealth);
</script>

<template>
  <main class="shell">
    <header class="app-header"><div><p class="eyebrow">RAGForge · Phase 4-G</p><h1>知识空间工程控制台</h1><p class="intro">面向编辑与检索实验的薄客户端。空间隔离、权限校验和审计最终由 API 强制执行。</p></div><div class="status-chip" :class="apiStatus" aria-live="polite"><span class="status-dot" aria-hidden="true"></span>{{ statusText }}</div></header>
    <section v-if="session" class="workspace-bar" aria-label="当前工作空间上下文"><div class="context-field"><label for="space-select">当前空间</label><select id="space-select" v-model="selectedSpaceId" :disabled="apiStatus !== 'ready' || spaces.length === 0"><option value="">请选择空间</option><option v-for="space in spaces" :key="space.spaceId" :value="space.spaceId">{{ space.name }} · {{ space.spaceId }}</option></select></div><div><span class="context-label">空间版本</span><strong>{{ selectedSpace?.version ?? "—" }}</strong></div><div><span class="context-label">当前角色</span><strong>{{ currentRole }}</strong></div><div><span class="context-label">会话用户</span><strong>{{ session.user.displayName }}</strong></div><div class="button-row"><button type="button" class="quiet-button" :disabled="apiStatus === 'checking'" @click="checkApiHealth">{{ apiStatus === "checking" ? "检查中…" : "刷新上下文" }}</button><button type="button" class="secondary-button" @click="view = 'profile'">个人空间</button></div></section>
    <p v-if="workspaceError && session" class="alert error" role="alert">{{ workspaceError }}</p>
    <AuthView v-if="!session" v-model:mode="authMode" v-model:email="authEmail" v-model:password="authPassword" v-model:display-name="authDisplayName" :loading="authLoading" :error="workspaceError" @submit="submitAuth" />
    <PersonalSpaceView v-if="session && (spaces.length === 0 || view === 'profile')" :session="session" :spaces="spaces" :selected-space-id="selectedSpaceId" :current-role="currentRole" :space-creating="spaceCreating" @select-space="(spaceId) => { selectedSpaceId = spaceId; view = 'home'; }" @create-space="createSpace" @open-action="openPersonalAction" @logout="logout" @refresh="checkApiHealth" />
    <nav v-if="session && spaces.length > 0" class="main-nav" aria-label="主要导航" role="tablist"><button id="home-tab" type="button" role="tab" :aria-selected="view === 'home'" :class="{ active: view === 'home' }" @click="view = 'home'">功能入口</button><button id="personal-space-tab" type="button" role="tab" :aria-selected="view === 'profile'" :class="{ active: view === 'profile' }" @click="view = 'profile'">个人空间</button><button id="chunk-studio-tab" type="button" role="tab" :aria-selected="view === 'studio'" :class="{ active: view === 'studio' }" @click="view = 'studio'">Chunk Studio</button><button id="retrieval-playground-tab" type="button" role="tab" :aria-selected="view === 'playground'" :class="{ active: view === 'playground' }" @click="view = 'playground'">Retrieval Playground</button><button id="answer-tab" type="button" role="tab" :aria-selected="view === 'answer'" :class="{ active: view === 'answer' }" @click="view = 'answer'">带引用问答</button><button id="control-center-tab" type="button" role="tab" :aria-selected="view === 'control'" :class="{ active: view === 'control' }" @click="openControl('providers')">配置与运维</button></nav>

    <section v-if="session && spaces.length > 0 && view === 'home'" class="view-section entry-home" aria-labelledby="entry-home-heading">
      <div class="section-heading"><div><p class="eyebrow">00 · Workspace map</p><h2 id="entry-home-heading">功能入口</h2><p>从当前空间进入内容编辑、检索实验、引用问答和平台配置。每个入口都使用当前 spaceId，服务端继续执行权限与隔离校验。</p></div><div class="read-only-note">当前角色：{{ currentRole }}</div></div>
      <div class="entry-grid"><button type="button" class="card entry-link" @click="view = 'studio'"><span class="card-label">内容编辑</span><h3>Chunk Studio</h3><p>查看 child chunk 的 provenance、锚点、索引状态和 manual override。</p><span class="entry-action">进入工作区 →</span></button><button type="button" class="card entry-link" @click="view = 'playground'"><span class="card-label">检索评估</span><h3>Retrieval Playground</h3><p>运行 profile A/B candidate 实验，查看 Dense、BM25、RRF、Rerank 和 evidence trace。</p><span class="entry-action">开始实验 →</span></button><button type="button" class="card entry-link" @click="view = 'answer'"><span class="card-label">生成式问答</span><h3>带引用问答</h3><p>执行带 provenance 与 evidence 的问答流程，保留拒答和引用边界。</p><span class="entry-action">打开问答 →</span></button><button type="button" class="card entry-link" @click="openControl('providers')"><span class="card-label">模型基础设施</span><h3>Provider 连接</h3><p>登记本地 Ollama 或受策略控制的云端 Provider connection。</p><span class="entry-action">管理连接 →</span></button><button type="button" class="card entry-link" @click="openControl('models')"><span class="card-label">路由配置</span><h3>模型与路由</h3><p>创建 Model Profile、Model Route 和同出境等级的 failover 策略。</p><span class="entry-action">管理路由 →</span></button><button type="button" class="card entry-link" @click="openControl('prompts')"><span class="card-label">提示词资产</span><h3>Prompt 模板</h3><p>管理版本化 Prompt 模板，并沿用服务端的版本发布约束。</p><span class="entry-action">管理模板 →</span></button><button type="button" class="card entry-link" @click="openControl('runs')"><span class="card-label">执行可观测性</span><h3>Run / Step 追踪</h3><p>按 Run ID 查看状态、步骤、错误 correlationId，并在允许时发起重试。</p><span class="entry-action">追踪执行 →</span></button><button type="button" class="card entry-link" @click="openControl('spaces')"><span class="card-label">空间运维</span><h3>空间与健康</h3><p>确认当前空间边界，打开本地 API 健康检查，并识别尚未接入的运维能力。</p><span class="entry-action">查看运行上下文 →</span></button></div>
      <div class="entry-gap-note"><strong>尚未提供 Web API 的能力</strong><span>数据源/同步管理、审计导出暂不显示可点击的伪入口；待后端 controller 和契约准备后再接入。</span></div>
    </section>

    <section v-if="session && spaces.length > 0 && view === 'studio'" role="tabpanel" aria-labelledby="chunk-studio-tab" class="view-section">
      <div class="section-heading"><div><p class="eyebrow">01 · Chunk projection</p><h2>Chunk Studio</h2><p>读取单个 child chunk 的可审计元数据；本页不渲染正文、原文或向量。</p></div><div class="read-only-note" :class="{ warning: isViewer }">{{ isViewer ? "VIEWER：只读模式，写操作已禁用" : "写操作由 API 权限最终裁决" }}</div></div>
      <form class="card lookup-form" @submit.prevent="loadChunk"><div class="field wide"><label for="child-chunk-id">childChunkId</label><input id="child-chunk-id" v-model="childChunkId" autocomplete="off" placeholder="UUIDv7" /></div><button type="submit" :disabled="studioLoading || !selectedSpaceId">{{ studioLoading ? "读取中…" : "读取 projection" }}</button></form><p class="helper">请求路径固定包含当前 spaceId；跨空间资源由服务端拒绝，不通过前端筛选绕过。</p>
      <p v-if="studioError" class="alert error" role="alert">{{ studioError }}</p><p v-if="studioNotice" class="alert success" role="status">{{ studioNotice }}</p>
      <template v-if="projection">
        <div class="identity-grid"><article class="card"><span class="card-label">空间 / revision</span><code>{{ projection.spaceId }}</code><code>{{ projection.documentRevisionId }}</code></article><article class="card"><span class="card-label">chunk references</span><span>child <code>{{ projection.childChunkId }}</code></span><span>parent <code>{{ projection.parentChunkId }}</code></span></article><article class="card"><span class="card-label">content reference</span><code>{{ projection.contentRef }}</code><span class="muted">textHash：{{ projection.textHash }}</span></article></div>
        <div class="two-column"><article class="card detail-card"><div class="card-title"><h3>Provenance 与 parent-child</h3><span class="tag">只读元数据</span></div><dl class="details"><dt>sourceId</dt><dd><code>{{ projection.provenance.sourceId }}</code></dd><dt>documentId</dt><dd><code>{{ projection.provenance.documentId }}</code></dd><dt>sourcePath</dt><dd>{{ projection.provenance.sourcePath }}</dd><dt>revisionVersion</dt><dd>{{ projection.provenance.revisionVersion }}</dd><dt>relationship</dt><dd>{{ projection.parentChild.relationship }} · child index {{ projection.parentChild.childIndex }}</dd><dt>parentContentRef</dt><dd><code>{{ projection.parentChild.parentContentRef }}</code></dd></dl></article><article class="card detail-card"><div class="card-title"><h3>Citation anchor</h3><span class="tag">allow-listed</span></div><p class="anchor-value">{{ formatAnchor(projection.anchor) }}</p><p class="muted">锚点只表示位置，不包含引用摘录。</p></article></div>
        <div class="two-column"><article class="card detail-card"><div class="card-title"><h3>Vector / index status</h3><span class="state-pill" :class="projection.vectorStatus.state.toLowerCase()">{{ projection.vectorStatus.state }}</span></div><dl class="details"><dt>indexVersionId</dt><dd><code>{{ projection.vectorStatus.indexVersionId ?? "—" }}</code></dd><dt>vectorDimension</dt><dd>{{ projection.vectorStatus.vectorDimension ?? "—" }} <span class="muted">（维度元数据，不显示向量）</span></dd><dt>updatedAt</dt><dd>{{ formatDate(projection.vectorStatus.updatedAt) }}</dd></dl></article><article class="card detail-card"><div class="card-title"><h3>Override audit summary</h3><span class="state-pill" :class="projection.override.state.toLowerCase()">{{ projection.override.state }}</span></div><dl class="details"><dt>overrideId</dt><dd><code>{{ projection.override.overrideId ?? "—" }}</code></dd><dt>version</dt><dd>{{ projection.override.version }}</dd><dt>reason</dt><dd>{{ projection.override.reason ?? "—" }}</dd><dt>createdBy</dt><dd><code>{{ projection.override.createdBy ?? "—" }}</code></dd><dt>createdAt / updatedAt</dt><dd>{{ formatDate(projection.override.createdAt) }} / {{ formatDate(projection.override.updatedAt) }}</dd></dl></article></div>
        <div class="two-column action-grid"><form class="card action-card" @submit.prevent="createOverride"><div class="card-title"><h3>创建 manual override</h3><span class="tag">服务端生成审计字段</span></div><p class="muted">客户端只提交外部已存储内容的 opaque contentRef、SHA-256 textHash 和 reason；正文不进入本客户端，也不提交 state、createdBy 或时间戳。</p><div class="field"><label for="replacement-content-ref">replacement contentRef</label><input id="replacement-content-ref" v-model="replacementContentRef" autocomplete="off" maxlength="512" spellcheck="false" required placeholder="opaque://已存储内容/版本" /><p class="field-hint">这是外部已存储内容的 opaque reference，不是正文；读取 projection 时初始化为当前值，你可以替换它。</p></div><div class="field"><label for="replacement-text-hash">replacement textHash</label><input id="replacement-text-hash" v-model="replacementTextHash" autocomplete="off" maxlength="64" minlength="64" spellcheck="false" required placeholder="64 位 SHA-256 十六进制值" /><p class="field-hint">这是外部内容的 SHA-256 hash，不是正文；读取 projection 时初始化为当前值，你可以替换它。</p></div><div class="field"><label for="override-reason">创建 reason</label><textarea id="override-reason" v-model="overrideReason" rows="3" maxlength="2000" placeholder="说明为何需要人工 override"></textarea></div><button type="submit" :disabled="studioLoading || !canEdit">{{ studioLoading ? "提交中…" : "创建 override" }}</button><p v-if="isViewer" class="permission-hint">VIEWER 无写权限，创建按钮已禁用；状态与 API 权限仍由服务端最终裁决。</p></form><div class="card action-card"><div class="card-title"><h3>冲突与状态流转</h3><span v-if="projection.override.state === 'NEEDS_REVIEW'" class="conflict-badge">需要复核</span></div><p class="muted">按钮严格对应 API 允许的流转：ACTIVE → NEEDS_REVIEW → ACTIVE / DISCARDED；DISCARDED 为终态。</p><div class="field"><label for="transition-reason">流转 reason</label><textarea id="transition-reason" v-model="transitionReason" rows="3" maxlength="2000" placeholder="记录复核结论"></textarea></div><div class="button-row"><button type="button" :disabled="studioLoading || !canEdit || projection.override.state !== 'NEEDS_REVIEW'" @click="transitionOverride('ACTIVE')">恢复 ACTIVE</button><button type="button" class="danger-button" :disabled="studioLoading || !canEdit || projection.override.state !== 'NEEDS_REVIEW'" @click="transitionOverride('DISCARDED')">DISCARDED</button><button type="button" class="secondary-button" :disabled="studioLoading || !canEdit || projection.override.state !== 'ACTIVE'" @click="transitionOverride('NEEDS_REVIEW')">标记复核</button></div><p v-if="isViewer" class="permission-hint">VIEWER 无写权限，状态流转按钮已禁用；API 仍是最终权限边界。</p><p v-if="projection.override.state === 'DISCARDED'" class="permission-hint">DISCARDED 是终态，契约不允许恢复。</p></div></div>
      </template><div v-else class="empty-state card"><strong>尚未读取 projection</strong><span>输入 childChunkId 后开始。这里只显示契约允许的引用、hash、位置和审计元数据。</span></div>
    </section>

    <section v-else-if="session && spaces.length > 0 && view === 'playground'" role="tabpanel" aria-labelledby="retrieval-playground-tab" class="view-section">
      <div class="section-heading"><div><p class="eyebrow">02 · Read-only experiment</p><h2>Retrieval Playground</h2><p>比较 profile A/B 候选版本的结构化 trace；实验不会改变 active profile。</p></div><div class="read-only-note">A/B 均为 candidate，只读</div></div>
      <form class="card experiment-form" @submit.prevent="runExperiment"><div class="field wide"><label for="query">query</label><textarea id="query" v-model="query" rows="3" maxlength="10000" placeholder="输入实验 query；不会写入 URL 或浏览器存储"></textarea></div><div class="form-grid"><div class="field"><label for="index-version">indexVersionId</label><input id="index-version" v-model="indexVersionId" autocomplete="off" placeholder="UUIDv7" /></div><div class="field"><label for="profile-a-id">profile A ID</label><input id="profile-a-id" v-model="profileAId" autocomplete="off" placeholder="UUIDv7" /></div><div class="field"><label for="profile-a-version">profile A version</label><input id="profile-a-version" v-model.number="profileAVersion" type="number" min="1" step="1" /></div></div><label class="check-row"><input v-model="compareProfileB" type="checkbox" />比较 profile B candidate</label><div v-if="compareProfileB" class="form-grid nested-fields"><div class="field"><label for="profile-b-id">profile B ID</label><input id="profile-b-id" v-model="profileBId" autocomplete="off" placeholder="UUIDv7" /></div><div class="field"><label for="profile-b-version">profile B version</label><input id="profile-b-version" v-model.number="profileBVersion" type="number" min="1" step="1" /></div></div><details class="test-seam"><summary>内部测试 queryVector（可选）</summary><p>仅作为契约 write-only synthetic-test seam 发送；不显示、不保存、不进入 URL 或日志。生产 UI 不需要此字段。</p><label for="query-vector">queryVector 数字序列</label><textarea id="query-vector" v-model="queryVectorText" rows="2" placeholder="例如：0.12, 0.03, -0.2"></textarea></details><div class="form-actions"><button type="submit" :disabled="playgroundLoading || !selectedSpaceId">{{ playgroundLoading ? "实验运行中…" : "提交只读实验" }}</button><span class="muted">spaceId 由当前空间路径提供，body 不重复提交。</span></div></form>
      <p v-if="playgroundError" class="alert error" role="alert">{{ playgroundError }}</p>
      <div v-if="experiment" class="results" aria-live="polite"><div class="result-summary card"><div><span class="card-label">实验完成</span><code>{{ experiment.experimentId }}</code><p>query：{{ experiment.query }}</p><p class="muted">normalizedQuery：{{ experiment.normalizedQuery }}</p></div><div class="safe-result"><span>activeProfileUnchanged</span><strong>{{ experiment.activeProfileUnchanged ? "true · 未改变" : "false" }}</strong></div></div><div class="side-grid"><template v-for="(side, sideName) in { A: experiment.profileA, B: experiment.profileB }" :key="sideName"><article v-if="side" class="side-panel"><div class="side-header"><h3>Profile {{ sideName }} candidate</h3><span>{{ side.profile.profileId }} · v{{ side.profile.version }}</span></div><div v-for="stage in traceStages" :key="stage.key" class="trace-block"><div class="trace-title"><h4>{{ stage.title }}</h4><span>{{ side.trace[stage.key].metrics.candidateCount }} candidates · {{ side.trace[stage.key].metrics.latencyMs }} ms</span></div><div v-for="hit in side.trace[stage.key].items" :key="`${stage.key}-${hit.childChunkId}-${hit.rank}`" class="hit-row"><span class="rank">#{{ hit.rank }}</span><div><code>{{ hit.childChunkId }}</code><small>score {{ hit.score }} · {{ hit.contentRef }} · hash {{ hit.textHash }}</small></div></div></div><div class="trace-block context-block"><div class="trace-title"><h4>Context</h4><span>{{ side.trace.context.totalTokens }} / {{ side.trace.context.maxContextTokens }} tokens</span></div><p>child chunks: {{ side.trace.context.childChunkIds.join("、") || "—" }}</p><p>{{ side.trace.context.truncated ? "context 已截断" : "context 未截断" }}</p></div><div class="trace-block evidence-block"><div class="trace-title"><h4>Evidence</h4><span>allow-list {{ side.trace.evidence.allowListVersion }}</span></div><div v-for="item in side.trace.evidence.items" :key="item.evidenceId" class="evidence-row"><strong>{{ item.evidenceId }}</strong><code>{{ item.contentRef }} · {{ item.textHash }}</code><span>child {{ item.childChunkId }} · anchor {{ formatAnchor(item.anchor) }}</span><small>citationAllowed: {{ item.citationAllowed }}</small></div></div><div class="abstention" :class="{ 'is-abstained': (experiment.abstention[sideName === 'A' ? 'profileA' : 'profileB'])?.abstained }">Abstention：{{ (experiment.abstention[sideName === 'A' ? 'profileA' : 'profileB'])?.abstained ? "是" : "否" }}{{ (experiment.abstention[sideName === 'A' ? 'profileA' : 'profileB'])?.reasonCode ? ` · ${(experiment.abstention[sideName === 'A' ? 'profileA' : 'profileB'])?.reasonCode}` : "" }}</div></article></template></div></div>
      <div v-else class="empty-state card"><strong>尚未运行实验</strong><span>填写 query、index/profile 版本后提交。结果只展示候选引用和 allow-listed evidence metadata。</span></div>
    </section>
    <ControlCenterView v-else-if="session && spaces.length > 0 && view === 'control'" role="tabpanel" aria-labelledby="control-center-tab" :selected-space-id="selectedSpaceId" :current-role="currentRole" :initial-section="controlSection" />
    <AnswerView v-else-if="session && spaces.length > 0 && view === 'answer'" role="tabpanel" aria-labelledby="answer-tab" :selected-space-id="selectedSpaceId" />
  </main>
</template>

<style>
:root { color: #17233c; background: #f4f7fb; font-family: Inter, "Microsoft YaHei", sans-serif; font-synthesis: none; } * { box-sizing: border-box; } body { margin: 0; min-width: 320px; } button, input, select, textarea { font: inherit; } button { border: 0; border-radius: 10px; padding: 11px 16px; background: #1d4f98; color: #fff; font-weight: 700; cursor: pointer; } button:disabled { cursor: not-allowed; opacity: .52; } button:focus-visible, input:focus-visible, select:focus-visible, textarea:focus-visible, summary:focus-visible { outline: 3px solid #83adf7; outline-offset: 2px; }
.shell { width: min(1220px, calc(100% - 40px)); margin: 0 auto; padding: 48px 0 80px; } .app-header, .section-heading, .workspace-bar, .card-title, .side-header, .form-actions, .button-row { display: flex; align-items: center; justify-content: space-between; gap: 20px; } .app-header { align-items: flex-start; margin-bottom: 26px; } .eyebrow, .card-label, .context-label { margin: 0; color: #60718f; font-size: .74rem; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; } h1, h2, h3, h4, p { margin-top: 0; } h1 { margin-bottom: 9px; color: #14284f; font-size: clamp(2rem, 4vw, 3.5rem); letter-spacing: -.045em; } h2 { margin: 4px 0 8px; color: #173363; font-size: 2rem; letter-spacing: -.03em; } h3 { margin-bottom: 0; color: #24385e; font-size: 1.08rem; } .intro, .section-heading p:not(.eyebrow), .muted, .helper { color: #687893; line-height: 1.6; } .intro { max-width: 690px; margin-bottom: 0; }
.status-chip { display: flex; align-items: center; gap: 8px; padding: 11px 14px; border-radius: 999px; background: #fff4da; color: #94600d; font-weight: 700; white-space: nowrap; } .status-chip.ready { background: #e5f6ee; color: #15734e; } .status-chip.unavailable { background: #ffebeb; color: #a52d38; } .status-dot { width: 9px; height: 9px; border-radius: 50%; background: currentColor; } .workspace-bar { align-items: flex-end; flex-wrap: wrap; padding: 18px; border: 1px solid #dce5f2; border-radius: 16px; background: #fff; box-shadow: 0 8px 25px #243c6410; } .context-field { min-width: min(100%, 320px); } .context-label { display: block; margin-bottom: 4px; } .workspace-bar strong { display: block; color: #233c6a; font-size: .92rem; } .quiet-button, .secondary-button { background: #e7eef9; color: #28518f; } select, input, textarea { width: 100%; border: 1px solid #cbd7e8; border-radius: 9px; padding: 10px 12px; background: #fff; color: #17233c; } textarea { resize: vertical; }
.main-nav { display: flex; gap: 7px; margin: 30px 0 26px; padding-bottom: 7px; border-bottom: 1px solid #d6e0ed; } .main-nav button { border-radius: 9px 9px 0 0; background: transparent; color: #60718f; } .main-nav button.active { background: #173b74; color: #fff; } .view-section { min-width: 0; } .section-heading { align-items: flex-start; margin-bottom: 20px; } .section-heading > div:first-child { max-width: 770px; } .section-heading p { margin-bottom: 0; } .read-only-note { padding: 9px 12px; border: 1px solid #b8d6f6; border-radius: 9px; background: #edf6ff; color: #27619d; font-size: .84rem; font-weight: 700; } .read-only-note.warning { border-color: #f2c7a1; background: #fff3e6; color: #a05a14; }
.entry-home { margin-top: 20px; } .entry-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 15px; } .entry-link { display: flex; min-height: 190px; flex-direction: column; align-items: flex-start; width: 100%; background: #fff; color: #17233c; text-align: left; transition: border-color .15s ease, transform .15s ease, box-shadow .15s ease; } .entry-link:hover { border-color: #8eb0e0; box-shadow: 0 12px 28px #243c6417; transform: translateY(-2px); } .entry-link h3 { margin-bottom: 8px; } .entry-link p { flex: 1; margin-bottom: 15px; color: #687893; font-size: .87rem; font-weight: 400; line-height: 1.55; } .entry-action { color: #1d4f98; font-size: .82rem; font-weight: 800; } .entry-gap-note { display: flex; gap: 10px; margin-top: 15px; padding: 13px 15px; border: 1px dashed #cbd7e8; border-radius: 12px; background: #f8faff; color: #687893; font-size: .82rem; line-height: 1.5; } .entry-gap-note strong { color: #405a82; }
.card { padding: 20px; border: 1px solid #dce5f2; border-radius: 15px; background: #fff; box-shadow: 0 8px 24px #243c6408; } .lookup-form, .experiment-form { display: flex; align-items: flex-end; gap: 14px; } .field { flex: 1; min-width: 0; } .field label, .test-seam > label { display: block; margin-bottom: 6px; color: #314b77; font-size: .86rem; font-weight: 700; } .field-hint { margin: 7px 2px 0; color: #687893; font-size: .78rem; line-height: 1.5; } .helper { margin: 8px 2px 18px; font-size: .82rem; } .alert { margin: 14px 0; padding: 12px 14px; border-radius: 10px; line-height: 1.5; } .alert.error { background: #ffeded; color: #a22f38; } .alert.success { background: #e6f6ed; color: #176b4b; } .identity-grid, .two-column, .side-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 15px; margin-top: 15px; } .two-column { grid-template-columns: repeat(2, minmax(0, 1fr)); } .card-label { display: block; margin-bottom: 10px; } .identity-grid code, .details code { display: block; overflow-wrap: anywhere; color: #274f91; font-family: "Cascadia Code", Consolas, monospace; font-size: .82rem; } .identity-grid span:not(.card-label) { display: block; margin-top: 7px; } .details { display: grid; grid-template-columns: minmax(110px, .45fr) 1fr; gap: 9px 15px; margin: 18px 0 0; } .details dt { color: #73829a; font-size: .85rem; } .details dd { margin: 0; overflow-wrap: anywhere; color: #2e3e5f; font-size: .87rem; } .tag, .state-pill, .conflict-badge { padding: 5px 8px; border-radius: 999px; background: #eef3fa; color: #52709e; font-size: .72rem; font-weight: 800; } .state-pill { background: #eaf5ed; color: #20734a; } .state-pill.needs_review { background: #fff0dc; color: #a15c16; } .state-pill.discarded, .state-pill.failed { background: #ffebeb; color: #a22f38; } .state-pill.pending, .state-pill.stale { background: #fff4da; color: #94600d; } .conflict-badge { background: #fff0dc; color: #a15c16; } .anchor-value { margin: 24px 0 10px; color: #2e4d7f; line-height: 1.7; } .detail-card { min-height: 190px; } .action-grid { margin-top: 15px; } .action-card { min-height: 290px; } .action-card .muted { min-height: 50px; font-size: .84rem; } .action-card .field { margin: 16px 0; } .permission-hint { margin: 9px 0 0; color: #a05a14; font-size: .79rem; } .button-row { justify-content: flex-start; flex-wrap: wrap; } .danger-button { background: #a73943; } .empty-state { display: flex; flex-direction: column; gap: 7px; margin-top: 15px; color: #647691; } .empty-state strong { color: #284572; }
.form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; } .experiment-form { display: block; } .experiment-form > .wide { margin-bottom: 14px; } .check-row { display: flex; align-items: center; gap: 8px; margin: 15px 0; color: #35517e; font-weight: 700; } .check-row input { width: auto; } .nested-fields { max-width: 66.6%; margin-bottom: 15px; } .test-seam { margin: 15px 0; padding: 13px 15px; border: 1px solid #dce5f2; border-radius: 10px; background: #fafcff; } .test-seam summary { color: #345582; cursor: pointer; font-weight: 700; } .test-seam p { margin: 9px 0; color: #6e7d96; font-size: .82rem; line-height: 1.5; } .test-seam textarea { margin-top: 6px; } .form-actions { justify-content: flex-start; margin-top: 16px; } .results { margin-top: 18px; } .result-summary { display: flex; justify-content: space-between; gap: 24px; } .result-summary p { margin: 8px 0 0; } .safe-result { display: flex; flex-direction: column; gap: 4px; align-items: flex-end; color: #637793; font-size: .8rem; } .safe-result strong { color: #17744e; } .side-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); margin-top: 15px; } .side-panel { min-width: 0; padding: 18px; border: 1px solid #dce5f2; border-radius: 15px; background: #fff; } .side-header { align-items: flex-start; padding-bottom: 14px; border-bottom: 1px solid #e4eaf3; } .side-header span { max-width: 55%; overflow-wrap: anywhere; color: #52709e; font-family: "Cascadia Code", Consolas, monospace; font-size: .76rem; text-align: right; } .trace-block { margin-top: 14px; padding-top: 14px; border-top: 1px solid #edf1f6; } .trace-title { display: flex; justify-content: space-between; gap: 12px; } .trace-title h4 { margin: 0 0 8px; color: #2c4d80; } .trace-title span { color: #71809a; font-size: .75rem; } .hit-row, .evidence-row { display: flex; align-items: flex-start; gap: 9px; margin-top: 7px; padding: 8px; border-radius: 8px; background: #f7faff; } .hit-row code, .evidence-row code, .evidence-row strong { overflow-wrap: anywhere; font-family: "Cascadia Code", Consolas, monospace; font-size: .75rem; } .hit-row small, .evidence-row span, .evidence-row small { display: block; margin-top: 4px; color: #6d7c93; line-height: 1.4; } .rank { flex: 0 0 25px; color: #2e65ac; font-weight: 800; } .context-block p { margin: 7px 0 0; color: #657590; font-size: .82rem; overflow-wrap: anywhere; } .evidence-row { flex-direction: column; } .evidence-row strong { color: #284c87; } .abstention { margin-top: 14px; padding: 10px; border-radius: 8px; background: #e7f7ee; color: #19714c; font-weight: 700; font-size: .84rem; } .abstention.is-abstained { background: #fff0dc; color: #a15c16; }
.onboarding-card { display: grid; grid-template-columns: minmax(240px, .8fr) minmax(320px, 1.2fr); gap: 30px; margin-top: 22px; } .auth-form { display: grid; gap: 14px; }
@media (max-width: 1000px) { .entry-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } } @media (max-width: 850px) { .shell { width: min(100% - 28px, 1220px); padding-top: 32px; } .app-header, .section-heading, .workspace-bar, .lookup-form { flex-direction: column; align-items: stretch; } .status-chip { align-self: flex-start; } .identity-grid, .two-column, .side-grid, .form-grid, .onboarding-card { grid-template-columns: 1fr; } .nested-fields { max-width: none; } .result-summary { flex-direction: column; } .safe-result { align-items: flex-start; } } @media (max-width: 520px) { .shell { width: min(100% - 20px, 1220px); } .entry-grid { grid-template-columns: 1fr; } .entry-gap-note { flex-direction: column; } .main-nav { overflow-x: auto; } .main-nav button { white-space: nowrap; } .form-actions { flex-direction: column; align-items: flex-start; } .details { grid-template-columns: 1fr; gap: 4px; } .details dd { margin-bottom: 5px; } }
</style>
