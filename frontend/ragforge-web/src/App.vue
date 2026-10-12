<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ApiError, listAllCursorPages, type AnswerDefaults, type CurrentSession, type PlatformAdminBootstrapStatus, type ProvenanceContext, type Space, apiFetch, bootstrapPlatformAdmin, getCurrentSession, getPlatformAdminBootstrapStatus, loginUser, logoutCurrentSession, registerUser } from "./api";
import AnswerView from "./views/answer/AnswerView.vue";
import ChunkStudioView from "./views/knowledge/ChunkStudioView.vue";
import RetrievalPlaygroundView from "./views/knowledge/RetrievalPlaygroundView.vue";
import ControlCenterView from "./views/admin/ControlCenterView.vue";
import AuthView from "./views/access/AuthView.vue";
import PersonalSpaceView from "./views/space/PersonalSpaceView.vue";
import BusinessFlowView from "./views/knowledge/BusinessFlowView.vue";
import { currentTimeZone, formatDateTime } from "./format";
import { roleLabel } from "./i18n";
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
const selectedSpaceId = ref(initialRoute.spaceId || routeContext.value?.spaceId || "");
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
const currentRoleLabel = computed(() => roleLabel(currentRole.value));
const isViewer = computed(() => selectedSpace.value?.role === "VIEWER");
const canEdit = computed(() => !isViewer.value && Boolean(selectedSpaceId.value));
const statusText = computed(() => apiStatus.value === "ready" ? "会话与 API 可用" : apiStatus.value === "unauthenticated" ? "请登录后继续" : apiStatus.value === "unavailable" ? "API 不可用" : "正在检查会话");
const modelSections = ["providers", "models", "prompts"] as const;
const isModelControl = computed(() => modelSections.includes(controlSection.value as typeof modelSections[number]));
const pageMeta = computed(() => {
  if (view.value === "profile") return { kicker: "空间", title: "知识空间", description: "空间是隔离边界。先选定空间，再配置成员、模型和资料。" };
  if (view.value === "control" && isModelControl.value) return { kicker: "模型", title: "云端模型", description: "只登记已授权的 OpenAI 兼容云端连接；未授权不得把内容送出。" };
  if (view.value === "flow") return { kicker: "知识库", title: "资料与索引", description: "导入资料、跟踪任务，验证通过后发布当前索引。" };
  if (view.value === "answer") return { kicker: "问答", title: "带引用问答", description: "基于已发布索引提问，引用必须来自结构化证据。" };
  if (view.value === "studio") return { kicker: "证据", title: "内容证据", description: "从来源、引用或检索命中打开记录，不手填内部编号。" };
  if (view.value === "playground") return { kicker: "检索", title: "检索实验", description: "只读比较检索候选，不会改变正在使用的索引版本。" };
  if (view.value === "control") return { kicker: "运行", title: "运行与管理", description: "查看健康、成本、审计和一次回答的运行记录。" };
  return { kicker: "工作台", title: "知识闭环", description: "在当前空间内完成资料、索引和可核验回答。" };
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
    const [current, visibleSpaces] = await Promise.all([getCurrentSession(), listAllCursorPages<Space>("/api/v1/spaces", { limit: 10 })]);
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
        <button type="button" class="brand-lockup" aria-label="返回带引用问答" @click="openView('answer')"><span class="brand-symbol">R</span><span><strong>RAGForge</strong><small>可核验知识工作台</small></span></button>
        <div class="sidebar-space">
          <label class="sidebar-label" for="sidebar-space-select">当前空间</label>
          <select id="sidebar-space-select" v-model="selectedSpaceId" :disabled="apiStatus !== 'ready' || spaces.length === 0">
            <option value="">请选择空间</option>
            <option v-for="space in spaces" :key="space.spaceId" :value="space.spaceId">{{ space.name }}</option>
          </select>
        </div>
        <div class="sidebar-section"><span class="sidebar-label">工作</span>
          <button id="answer-tab" type="button" class="side-nav-item" :class="{ active: view === 'answer' }" :aria-current="view === 'answer' ? 'page' : undefined" @click="openView('answer')"><span class="nav-icon">问</span><span><strong>带引用问答</strong><small>提问并核验来源</small></span></button>
          <button id="flow-tab" type="button" class="side-nav-item" :class="{ active: view === 'flow' }" :aria-current="view === 'flow' ? 'page' : undefined" @click="openView('flow')"><span class="nav-icon">库</span><span><strong>资料与索引</strong><small>导入、任务、发布</small></span></button>
        </div>
        <div class="sidebar-section"><span class="sidebar-label">知识工程</span>
          <button id="chunk-studio-tab" type="button" class="side-nav-item" :class="{ active: view === 'studio' }" :aria-current="view === 'studio' ? 'page' : undefined" @click="openView('studio')"><span class="nav-icon">证</span><span><strong>内容证据</strong><small>来源与修订</small></span></button>
          <button id="retrieval-playground-tab" type="button" class="side-nav-item" :class="{ active: view === 'playground' }" :aria-current="view === 'playground' ? 'page' : undefined" @click="openView('playground')"><span class="nav-icon">检</span><span><strong>检索实验</strong><small>只读比较</small></span></button>
        </div>
        <div class="sidebar-section"><span class="sidebar-label">配置</span>
          <button id="personal-space-tab" type="button" class="side-nav-item" :class="{ active: view === 'profile' }" :aria-current="view === 'profile' ? 'page' : undefined" @click="openView('profile')"><span class="nav-icon">空</span><span><strong>知识空间</strong><small>成员与隔离边界</small></span></button>
          <button id="control-model-tab" type="button" class="side-nav-item" :class="{ active: view === 'control' && isModelControl }" @click="openControl('providers')"><span class="nav-icon">模</span><span><strong>云端模型</strong><small>授权后才能调用</small></span></button>
        </div>
        <div class="sidebar-section"><span class="sidebar-label">治理</span>
          <button id="control-center-tab" type="button" class="side-nav-item" :class="{ active: view === 'control' && !isModelControl }" @click="openControl('runs')"><span class="nav-icon">运</span><span><strong>运行与管理</strong><small>健康、成本、审计</small></span></button>
        </div>
        <div class="sidebar-footer"><div class="sidebar-user"><span class="user-avatar">{{ session.user.displayName.slice(0, 1).toUpperCase() }}</span><span><strong>{{ session.user.displayName }}</strong><small>{{ currentRoleLabel }}</small></span></div><button type="button" class="sidebar-logout" @click="logout">退出登录</button></div>
      </aside>

      <section class="app-main">
        <header class="topbar"><div class="page-heading"><span class="eyebrow">{{ pageMeta.kicker }}</span><h1>{{ pageMeta.title }}</h1><p>{{ pageMeta.description }}</p></div><div class="topbar-actions"><div class="status-chip" :class="apiStatus" aria-live="polite"><span class="status-dot" aria-hidden="true"></span>{{ statusText }}</div><button type="button" class="icon-button" :disabled="apiStatus === 'checking'" aria-label="刷新工作区状态" title="刷新工作区状态" @click="checkApiHealth">↻</button></div></header>
        <section class="workspace-context" aria-label="当前工作空间上下文"><div class="space-switcher"><span class="context-label">当前空间</span><select id="space-select" v-model="selectedSpaceId" :disabled="apiStatus !== 'ready' || spaces.length === 0"><option value="">请选择空间</option><option v-for="space in spaces" :key="space.spaceId" :value="space.spaceId">{{ space.name }}</option></select><small class="timezone-note">时间显示：{{ currentTimeZone() }}</small></div><div class="context-stat"><span class="context-label">当前角色</span><strong>{{ currentRoleLabel }}</strong></div><div class="context-stat"><span class="context-label">空间版本</span><strong>v{{ selectedSpace?.version ?? "—" }}</strong></div><div class="context-id"><span class="context-label">空间标识</span><code>{{ selectedSpaceId || "尚未创建空间" }}</code></div><button type="button" class="secondary-button context-profile" @click="openView('profile')">管理空间</button></section>
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
