<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ApiError, listAllCursorPages, type AnswerDefaults, type CurrentSession, type PlatformAdminBootstrapStatus, type ProvenanceContext, type Space, apiFetch, bootstrapPlatformAdmin, getCurrentSession, getPlatformAdminBootstrapStatus, loginUser, logoutCurrentSession, registerUser } from "./api";
import AnswerView from "./AnswerView.vue";
import ChunkStudioView from "./ChunkStudioView.vue";
import RetrievalPlaygroundView from "./RetrievalPlaygroundView.vue";
import ControlCenterView from "./ControlCenterView.vue";
import AuthView from "./AuthView.vue";
import PersonalSpaceView from "./PersonalSpaceView.vue";
import BusinessFlowView from "./BusinessFlowView.vue";
import { currentTimeZone, formatDateTime } from "./format";
import { readRoute, routeSearch, type ControlSection, type RouteState, type View } from "./router";

const initialRoute: RouteState = readRoute();
const routeContext = ref<ProvenanceContext | null>(initialRoute.provenance);
const view = ref<View>(initialRoute.view);
const controlSection = ref<ControlSection>(initialRoute.controlSection);
const answerDefaults = ref<AnswerDefaults | null>(null);
const lastRunId = ref(initialRoute.runId);
const conversationId = ref(initialRoute.conversationId);
const apiStatus = ref<"checking" | "ready" | "unavailable" | "unauthenticated">("checking");
const checkedAt = ref("");
const session = ref<CurrentSession | null>(null);
const spaces = ref<Space[]>([]);
const selectedSpaceId = ref(routeContext.value?.spaceId ?? "");
const workspaceError = ref("");
const authMode = ref<"login" | "register" | "bootstrap">("login");
const authEmail = ref("");
const authPassword = ref("");
const authDisplayName = ref("");
const bootstrapToken = ref("");
const bootstrapStatus = ref<PlatformAdminBootstrapStatus>({ required: false, available: false });
const authLoading = ref(false);
const spaceCreating = ref(false);

const query = ref("");
const indexVersionId = ref("");
const profileAId = ref("");
const profileAVersion = ref(1);
const compareProfileB = ref(false);
const profileBId = ref("");

const selectedSpace = computed(() => spaces.value.find((space) => space.spaceId === selectedSpaceId.value) ?? null);
const currentRole = computed(() => selectedSpace.value?.role ?? (session.value?.user.platformRole === "PLATFORM_ADMIN" ? "PLATFORM_ADMIN" : "未加载"));
const isViewer = computed(() => selectedSpace.value?.role === "VIEWER");
const canEdit = computed(() => !isViewer.value && Boolean(selectedSpaceId.value));
const statusText = computed(() => apiStatus.value === "ready" ? "会话与 API 可用" : apiStatus.value === "unauthenticated" ? "请登录后继续" : apiStatus.value === "unavailable" ? "API 不可用" : "正在检查会话");
const pageMeta = computed(() => {
  if (view.value === "flow") return { kicker: "Workspace / Guided flow", title: "业务闭环", description: "从本地知识库到可核验答案，一步一步完成真实服务端流程。" };
  if (view.value === "answer") return { kicker: "Workspace / Answer", title: "带引用问答", description: "在当前空间内运行问答，并保留证据、引用和 Run 追踪。" };
  if (view.value === "studio") return { kicker: "Tools / Chunk Studio", title: "Chunk Studio", description: "检查 chunk 的来源、索引状态和人工修订审计。" };
  if (view.value === "playground") return { kicker: "Tools / Retrieval", title: "Retrieval Playground", description: "以只读方式比较候选检索配置和 evidence trace。" };
  if (view.value === "control") return { kicker: "Manage / Configuration", title: "配置中心", description: "管理 Provider、模型路由、Prompt 版本和 Run。" };
  if (view.value === "profile") return { kicker: "Account / Personal space", title: "个人空间", description: "管理账号与知识空间，切换当前工作的内容边界。" };
  return { kicker: "Workspace", title: "业务闭环", description: "从本地知识库到可核验答案，一步一步完成真实服务端流程。" };
});
function persistRoute(): void {
  if (typeof window === "undefined") return;
  window.history.replaceState(null, "", `${window.location.pathname}${routeSearch({ view: view.value, spaceId: selectedSpaceId.value, controlSection: controlSection.value, conversationId: conversationId.value, runId: lastRunId.value, provenance: routeContext.value })}`);
}
function openView(nextView: View): void { view.value = nextView; if (nextView !== "studio" && nextView !== "playground") routeContext.value = null; }
function openProvenanceContext(context: ProvenanceContext): void {
  routeContext.value = context; selectedSpaceId.value = context.spaceId; view.value = context.target;
}
function describeError(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError)) return fallback;
  const problem = error.problem;
  const permission = error.status === 401 ? "请先登录。" : error.status === 403 ? "当前角色或空间权限不允许此操作；前端提示不替代 API 授权。" : error.status === 404 ? "资源不存在，或不属于当前空间。请检查 spaceId 与资源 ID。" : error.status === 409 ? "请求与当前资源版本冲突，请重新读取后再试。" : error.status === 412 ? "资源版本已变化，请重新读取并确认 expectedVersion。" : error.status === 422 ? "请求字段未通过契约校验。" : "请稍后重试。";
  const correlation = error.correlationId ? ` correlationId: ${error.correlationId}` : "";
  return `${problem?.code ? `${problem.code}：` : ""}${problem?.detail ?? error.message} ${permission}${correlation}`;
}
function openControl(section: ControlSection): void {
  controlSection.value = section;
  openView("control");
}

async function checkApiHealth(): Promise<void> {
  apiStatus.value = "checking";
  workspaceError.value = "";
  try {
    const [current, visibleSpaces] = await Promise.all([getCurrentSession(), listAllCursorPages<Space>("/api/v1/spaces", { limit: 20 })]);
    session.value = current;
    spaces.value = visibleSpaces;
    if (!selectedSpaceId.value || !spaces.value.some((space) => space.spaceId === selectedSpaceId.value)) selectedSpaceId.value = spaces.value[0]?.spaceId ?? "";
    apiStatus.value = "ready";
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      session.value = null;
      spaces.value = [];
      selectedSpaceId.value = "";
      apiStatus.value = "unauthenticated";
      workspaceError.value = "";
      try {
        bootstrapStatus.value = await getPlatformAdminBootstrapStatus();
        if (bootstrapStatus.value.available) authMode.value = "bootstrap";
      } catch { bootstrapStatus.value = { required: false, available: false }; }
      return;
    }
    apiStatus.value = "unavailable";
    workspaceError.value = describeError(error, "无法读取当前会话或可见空间。请确认服务已启动并已登录。");
  } finally {
    checkedAt.value = formatDateTime(new Date().toISOString());
  }
}

async function submitAuth(): Promise<void> {
  workspaceError.value = "";
  if (!authEmail.value.trim() || !authPassword.value) {
    workspaceError.value = "请填写邮箱和密码。";
    return;
  }
  if (authMode.value !== "login" && !authDisplayName.value.trim()) {
    workspaceError.value = "需要填写显示名称。";
    return;
  }
  if (authMode.value === "bootstrap" && bootstrapToken.value.length < 32) {
    workspaceError.value = "Bootstrap Secret 至少需要 32 位。";
    return;
  }
  authLoading.value = true;
  try {
    if (authMode.value === "bootstrap") {
      await bootstrapPlatformAdmin({ email: authEmail.value.trim(), password: authPassword.value,
        displayName: authDisplayName.value.trim(), token: bootstrapToken.value });
      bootstrapToken.value = "";
      bootstrapStatus.value = { required: false, available: false };
    } else if (authMode.value === "register") {
      await registerUser({ email: authEmail.value.trim(), password: authPassword.value, displayName: authDisplayName.value.trim() });
    }
    await loginUser({ email: authEmail.value.trim(), password: authPassword.value });
    authPassword.value = "";
    await checkApiHealth();
  } catch (error) {
    workspaceError.value = describeError(error, authMode.value === "bootstrap" ? "平台管理员初始化失败。" : authMode.value === "register" ? "注册或登录失败。" : "登录失败。");
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
    spaces.value = [...spaces.value, created]; selectedSpaceId.value = created.spaceId; openView("profile");
  } catch (error) { workspaceError.value = describeError(error, "知识空间创建失败。"); }
  finally { spaceCreating.value = false; }
}
function openPersonalAction(action: "home" | "providers" | "models" | "prompts" | "runs"): void {
  if (action === "home") { openView("flow"); return; }
  openControl(action);
}

watch([view, selectedSpaceId, routeContext], persistRoute, { deep: true });
watch(selectedSpaceId, (next, previous) => {
  if (next !== previous) { conversationId.value = ""; lastRunId.value = ""; if (routeContext.value && routeContext.value.spaceId !== next) routeContext.value = null; }
});
onMounted(checkApiHealth);
</script>

<template>
  <main class="shell">
    <AuthView v-if="!session" v-model:mode="authMode" v-model:email="authEmail" v-model:password="authPassword" v-model:display-name="authDisplayName" v-model:bootstrap-token="bootstrapToken" :bootstrap-required="bootstrapStatus.required" :bootstrap-available="bootstrapStatus.available" :loading="authLoading" :error="workspaceError" @submit="submitAuth" />

    <div v-else class="app-frame">
      <aside class="app-sidebar" aria-label="产品导航">
        <button type="button" class="brand-lockup" aria-label="返回业务闭环" @click="openView('flow')"><span class="brand-symbol">R</span><span><strong>RAGForge</strong><small>Knowledge workspace</small></span></button>
        <div class="sidebar-section"><span class="sidebar-label">工作台</span><button id="flow-tab" type="button" class="side-nav-item" :class="{ active: view === 'flow' }" :aria-current="view === 'flow' ? 'page' : undefined" @click="openView('flow')"><span class="nav-icon">⌂</span><span><strong>业务闭环</strong><small>知识库 → 答案</small></span></button><button id="answer-tab" type="button" class="side-nav-item" :class="{ active: view === 'answer' }" :aria-current="view === 'answer' ? 'page' : undefined" @click="openView('answer')"><span class="nav-icon">✦</span><span><strong>带引用问答</strong><small>运行一次可核验回答</small></span></button></div>
        <div class="sidebar-section"><span class="sidebar-label">工具</span><button id="chunk-studio-tab" type="button" class="side-nav-item" :class="{ active: view === 'studio' }" :aria-current="view === 'studio' ? 'page' : undefined" @click="openView('studio')"><span class="nav-icon">▦</span><span><strong>Chunk Studio</strong><small>内容与 provenance</small></span></button><button id="retrieval-playground-tab" type="button" class="side-nav-item" :class="{ active: view === 'playground' }" :aria-current="view === 'playground' ? 'page' : undefined" @click="openView('playground')"><span class="nav-icon">⌁</span><span><strong>Retrieval Playground</strong><small>只读检索实验</small></span></button></div>
        <div class="sidebar-section"><span class="sidebar-label">管理</span><button id="control-center-tab" type="button" class="side-nav-item" :class="{ active: view === 'control' }" :aria-current="view === 'control' ? 'page' : undefined" @click="openControl('spaces')"><span class="nav-icon">⚙</span><span><strong>配置中心</strong><small>Provider · Prompt · Run</small></span></button><button id="personal-space-tab" type="button" class="side-nav-item" :class="{ active: view === 'profile' }" :aria-current="view === 'profile' ? 'page' : undefined" @click="openView('profile')"><span class="nav-icon">◎</span><span><strong>个人空间</strong><small>账号与空间</small></span></button></div>
        <div class="sidebar-footer"><div class="sidebar-user"><span class="user-avatar">{{ session.user.displayName.slice(0, 1).toUpperCase() }}</span><span><strong>{{ session.user.displayName }}</strong><small>{{ currentRole }}</small></span></div><button type="button" class="sidebar-logout" @click="logout">退出登录</button></div>
      </aside>

      <section class="app-main">
        <header class="topbar"><div class="page-heading"><span class="eyebrow">{{ pageMeta.kicker }}</span><h1>{{ pageMeta.title }}</h1><p>{{ pageMeta.description }}</p></div><div class="topbar-actions"><div class="status-chip" :class="apiStatus" aria-live="polite"><span class="status-dot" aria-hidden="true"></span>{{ statusText }}</div><button type="button" class="icon-button" :disabled="apiStatus === 'checking'" aria-label="刷新工作区状态" title="刷新工作区状态" @click="checkApiHealth">↻</button></div></header>
        <section class="workspace-context" aria-label="当前工作空间上下文"><div class="space-switcher"><span class="context-label">当前空间</span><select id="space-select" v-model="selectedSpaceId" :disabled="apiStatus !== 'ready' || spaces.length === 0"><option value="">请选择空间</option><option v-for="space in spaces" :key="space.spaceId" :value="space.spaceId">{{ space.name }}</option></select><small class="timezone-note">时间显示：{{ currentTimeZone() }}</small></div><div class="context-stat"><span class="context-label">角色</span><strong>{{ currentRole }}</strong></div><div class="context-stat"><span class="context-label">版本</span><strong>v{{ selectedSpace?.version ?? "—" }}</strong></div><div class="context-id"><span class="context-label">space_id</span><code>{{ selectedSpaceId || "尚未创建空间" }}</code></div><button type="button" class="secondary-button context-profile" @click="openView('profile')">管理空间</button></section>
        <p v-if="workspaceError" class="alert error" role="alert">{{ workspaceError }}</p>
        <PersonalSpaceView v-if="spaces.length === 0 || view === 'profile'" :session="session" :spaces="spaces" :selected-space-id="selectedSpaceId" :current-role="currentRole" :space-creating="spaceCreating" @select-space="(spaceId) => { selectedSpaceId = spaceId; openView('flow'); }" @create-space="createSpace" @open-action="openPersonalAction" @logout="logout" @refresh="checkApiHealth" />
        <BusinessFlowView v-else-if="view === 'flow'" :selected-space-id="selectedSpaceId" :current-role="currentRole" :current-user-id="session.user.userId" @start-answer="(config) => { answerDefaults = config; openView('answer'); }" @open-control="openControl" />

        <ChunkStudioView v-else-if="view === 'studio'" :selected-space-id="selectedSpaceId" :current-role="currentRole" :initial-context="routeContext" />
        <RetrievalPlaygroundView v-else-if="view === 'playground'" :selected-space-id="selectedSpaceId" :initial-context="routeContext" @open-context="openProvenanceContext" />
        <ControlCenterView v-else-if="view === 'control'" role="tabpanel" aria-labelledby="control-center-tab" :selected-space-id="selectedSpaceId" :current-role="currentRole" :platform-role="session.user.platformRole" :initial-section="controlSection" :initial-run-id="lastRunId" />
        <AnswerView v-else-if="view === 'answer'" role="tabpanel" aria-labelledby="answer-tab" :selected-space-id="selectedSpaceId" :defaults="answerDefaults" :initial-conversation-id="conversationId" :initial-run-id="lastRunId" @conversation-created="conversationId = $event" @run-created="lastRunId = $event" @open-context="openProvenanceContext" />
      </section>
    </div>
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
.shell { width: 100%; max-width: none; min-height: 100vh; padding: 0; background: #f5f7fb; }
.app-frame { display: grid; min-height: 100vh; grid-template-columns: 252px minmax(0, 1fr); }
.app-sidebar { display: flex; min-height: 100vh; flex-direction: column; padding: 25px 15px 18px; border-right: 1px solid #e4e8f0; background: #fff; }
.brand-lockup { display: flex; align-items: center; gap: 11px; width: 100%; margin: 0 8px 38px; padding: 0; background: transparent; color: #182b4d; text-align: left; }
.brand-symbol { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 11px; background: #1f4e8c; color: #fff; font-size: 1.1rem; font-weight: 900; }
.brand-lockup strong, .brand-lockup small { display: block; }.brand-lockup strong { font-size: 1rem; letter-spacing: -.02em; }.brand-lockup small { margin-top: 3px; color: #93a0b4; font-size: .64rem; font-weight: 600; letter-spacing: .03em; }
.sidebar-section { display: grid; gap: 4px; margin-bottom: 24px; }.sidebar-label { display: block; margin: 0 11px 8px; color: #a0aabe; font-size: .64rem; font-weight: 900; letter-spacing: .1em; text-transform: uppercase; }
.side-nav-item { display: flex; align-items: center; gap: 11px; width: 100%; padding: 10px 11px; border: 1px solid transparent; border-radius: 11px; background: transparent; color: #65738a; text-align: left; }.side-nav-item:hover { background: #f4f7fc; color: #234c84; }.side-nav-item.active { border-color: #dce8f8; background: #eef5ff; color: #1f4f8f; }.side-nav-item strong, .side-nav-item small { display: block; }.side-nav-item strong { color: inherit; font-size: .8rem; }.side-nav-item small { margin-top: 3px; color: #95a1b2; font-size: .65rem; font-weight: 500; }.side-nav-item.active small { color: #6686b0; }
.nav-icon { display: grid; width: 25px; height: 25px; flex: 0 0 25px; place-items: center; border-radius: 8px; background: #f0f3f8; color: #71839f; font-size: .85rem; font-weight: 800; }.side-nav-item.active .nav-icon { background: #d9e9ff; color: #205797; }
.sidebar-footer { display: grid; gap: 13px; margin-top: auto; padding: 16px 8px 0; border-top: 1px solid #edf0f5; }.sidebar-user { display: flex; align-items: center; gap: 9px; min-width: 0; }.sidebar-user strong, .sidebar-user small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.sidebar-user strong { color: #344765; font-size: .77rem; }.sidebar-user small { margin-top: 3px; color: #96a2b3; font-size: .65rem; }.user-avatar { display: grid; width: 30px; height: 30px; flex: 0 0 30px; place-items: center; border-radius: 10px; background: #e5f0ff; color: #26558e; font-size: .76rem; font-weight: 800; }.sidebar-logout { padding: 8px 10px; background: transparent; color: #8b98aa; font-size: .72rem; text-align: left; }.sidebar-logout:hover { background: #fff3f3; color: #a94750; }
.app-main { min-width: 0; padding: 38px clamp(22px, 4vw, 58px) 72px; }.topbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 30px; margin-bottom: 28px; }.page-heading h1 { margin: 5px 0 6px; color: #172c51; font-size: clamp(1.8rem, 3vw, 2.65rem); letter-spacing: -.05em; }.page-heading p { max-width: 590px; margin: 0; color: #7a879a; font-size: .84rem; line-height: 1.55; }.topbar-actions { display: flex; align-items: center; gap: 9px; padding-top: 7px; }.icon-button { display: grid; width: 37px; height: 37px; place-items: center; padding: 0; border: 1px solid #dce4ef; border-radius: 11px; background: #fff; color: #4c6588; font-size: 1.1rem; }.icon-button:hover { border-color: #9bb7de; background: #f7faff; }.topbar .status-chip { padding: 9px 11px; font-size: .73rem; }
.workspace-context { display: grid; grid-template-columns: minmax(220px, 1.25fr) .55fr .45fr minmax(180px, 1fr) auto; align-items: end; gap: 18px; margin-bottom: 30px; padding: 13px 16px; border: 1px solid #e4e9f1; border-radius: 14px; background: #fff; box-shadow: 0 8px 24px #28466c08; }.space-switcher { min-width: 0; }.space-switcher select { height: 38px; margin-top: 4px; border-color: #d6e0ec; background: #fbfcfe; font-size: .8rem; font-weight: 700; }.timezone-note { display: block; margin-top: 4px; color: #8b99ac; font-size: .66rem; }.context-stat { min-width: 0; padding-left: 18px; border-left: 1px solid #edf0f4; }.context-stat strong { display: block; margin-top: 5px; color: #30486d; font-size: .78rem; }.context-id { min-width: 0; padding-left: 18px; border-left: 1px solid #edf0f4; }.context-id code { display: block; max-width: 100%; margin-top: 5px; overflow: hidden; color: #6a7890; font-family: "Cascadia Code", Consolas, monospace; font-size: .66rem; text-overflow: ellipsis; white-space: nowrap; }.context-profile { padding: 9px 12px; font-size: .72rem; white-space: nowrap; }
.app-main > .view-section, .app-main > .business-flow { animation: content-in .2s ease-out; }.app-main > .personal-page { margin-top: 0; } @keyframes content-in { from { opacity: .3; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 1100px) { .app-frame { grid-template-columns: 218px minmax(0, 1fr); }.workspace-context { grid-template-columns: minmax(190px, 1.2fr) .55fr .45fr auto; }.context-id { display: none; } }
@media (max-width: 820px) { .app-frame { display: block; }.app-sidebar { position: sticky; z-index: 10; top: 0; min-height: auto; flex-direction: row; align-items: center; padding: 12px 15px; border-right: 0; border-bottom: 1px solid #e4e8f0; }.brand-lockup { width: auto; margin: 0 auto 0 0; }.brand-lockup small, .sidebar-label, .sidebar-footer, .side-nav-item small { display: none; }.sidebar-section { display: flex; gap: 4px; margin: 0; }.side-nav-item { width: auto; padding: 9px; }.side-nav-item strong { display: none; }.nav-icon { width: 30px; height: 30px; }.app-main { padding: 28px 20px 55px; }.topbar { gap: 18px; }.workspace-context { grid-template-columns: minmax(190px, 1fr) .5fr .5fr; }.context-profile { grid-column: 1 / -1; width: 100%; }.context-stat { padding-left: 12px; }.context-id { display: none; } }
@media (max-width: 560px) { .app-sidebar { overflow-x: auto; }.brand-lockup { flex: 0 0 auto; margin-right: 12px; }.sidebar-section { flex: 0 0 auto; }.topbar { flex-direction: column; }.topbar-actions { width: 100%; justify-content: space-between; }.topbar-actions .status-chip { flex: 1; }.workspace-context { grid-template-columns: 1fr 1fr; gap: 12px; }.space-switcher { grid-column: 1 / -1; }.context-stat { border-left: 0; padding-left: 0; }.app-main { padding: 24px 13px 45px; } }
</style>
