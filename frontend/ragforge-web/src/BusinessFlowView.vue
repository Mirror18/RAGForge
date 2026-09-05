<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ApiError, getActiveIndex, getIngestionJob, getParseReport, ingestWebSource, listAllCursorPages, publishIndex, rollbackIndex, retireIndex, uploadMarkdown, type ActiveIndexView, type AnswerDefaults, type IndexView, type IngestionJobView, type ModelProfile, type ModelRoute, type ParseReportView, type PlatformRole, type PromptTemplate, type PromptVersion, type ProviderConnection, type SpaceBinding, type SpaceRole, apiFetch } from "./api";
import SourceLibraryView from "./SourceLibraryView.vue";
import TaskCenterView from "./TaskCenterView.vue";
import IndexLifecycleView from "./IndexLifecycleView.vue";

const props = defineProps<{
  selectedSpaceId: string;
  currentRole: SpaceRole | PlatformRole | string;
  currentUserId: string;
}>();

const emit = defineEmits<{
  "start-answer": [config: AnswerDefaults];
  "open-control": [section: "providers" | "models" | "prompts" | "runs"];
}>();

const loading = ref(false);
const error = ref("");
const notice = ref("");
const providerConnections = ref<ProviderConnection[]>([]);
const modelProfiles = ref<ModelProfile[]>([]);
const modelRoutes = ref<ModelRoute[]>([]);
const promptTemplates = ref<PromptTemplate[]>([]);
const promptVersion = ref<PromptVersion | null>(null);
const selectedRouteId = ref("");
const selectedProfileId = ref("");
const selectedPromptTemplateId = ref("");
const model = ref("mimo-v2.5");
const indexVersionId = ref("");
const datasetHash = ref("");
const selectedFiles = ref<File[]>([]);
const uploadBusy = ref(false);
const jobs = ref<IngestionJobView[]>([]);
const indexes = ref<IndexView[]>([]);
const activeIndex = ref<ActiveIndexView | null>(null);
const parseReport = ref<ParseReportView | null>(null);
const publishBusy = ref("");
const webUrl = ref("");
const webEgressApproved = ref(false);

const selectedRoute = computed(() => modelRoutes.value.find((item) => item.modelRouteId === selectedRouteId.value) ?? null);
const selectedProfile = computed(() => modelProfiles.value.find((item) => item.modelProfileId === selectedProfileId.value) ?? null);
const selectedProvider = computed(() => providerConnections.value.find((item) => item.providerConnectionId === selectedProfile.value?.providerConnectionId) ?? null);
const selectedTemplate = computed(() => promptTemplates.value.find((item) => item.promptTemplateId === selectedPromptTemplateId.value) ?? null);
const chatRoutes = computed(() => modelRoutes.value.filter((item) => item.purpose === "CHAT" && item.status === "ACTIVE"));
const runtimeMode = ref<"LOCAL" | "MIMO">("MIMO");
const selectedRouteCandidate = computed(() => selectedRoute.value?.candidates.find((candidate) => candidate.modelProfileId === selectedProfileId.value) ?? null);
const hasPublishedModel = computed(() => selectedRoute.value?.purpose === "CHAT"
  && selectedRoute.value.status === "ACTIVE"
  && selectedRouteCandidate.value?.egressClass === selectedRoute.value.egressClass
  && selectedProfile.value?.status === "PUBLISHED"
  && selectedProvider.value?.status === "ACTIVE");
const hasPublishedPrompt = computed(() => Boolean(promptVersion.value?.promptVersionId
  && promptVersion.value.promptTemplateId === selectedPromptTemplateId.value
  && promptVersion.value.state === "PUBLISHED"));
const hasIndexIdentity = computed(() => Boolean(activeIndex.value?.pointer.activeIndexVersionId
  && activeIndex.value.datasetHash && /^[0-9a-f]{64}$/i.test(activeIndex.value.datasetHash)));
const canStart = computed(() => Boolean(props.selectedSpaceId && hasPublishedModel.value && hasPublishedPrompt.value && hasIndexIdentity.value));
const canManage = computed(() => props.currentRole === "SPACE_ADMIN" || props.currentRole === "PLATFORM_ADMIN");
const needsRuntimeSetup = computed(() => Boolean(props.selectedSpaceId && (!hasPublishedModel.value || !hasPublishedPrompt.value)));

function apiPath(suffix: string): string {
  return `/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}${suffix}`;
}

async function applyChatBinding(route: ModelRoute): Promise<void> {
  const binding = await apiFetch<SpaceBinding>(apiPath("/space-bindings"));
  const cloud = route.egressClass === "CLOUD";
  const approvedAt = new Date();
  const expiresAt = new Date(approvedAt.getTime() + 24 * 60 * 60 * 1000);
  await apiFetch<SpaceBinding>(apiPath("/space-bindings"), {
    method: "PUT",
    headers: { "If-Match": `"${binding.version}"` },
    body: {
      version: binding.version,
      chatRouteId: route.modelRouteId,
      embeddingRouteId: binding.embeddingRouteId,
      rerankRouteId: binding.rerankRouteId,
      promptVersionId: binding.promptVersionId,
      cloudEgressEnabled: cloud,
      cloudEgressAuthorization: cloud ? {
        approvalId: crypto.randomUUID(),
        approvedBy: props.currentUserId,
        approvedAt: approvedAt.toISOString(),
        expiresAt: expiresAt.toISOString(),
        scope: "CHAT",
      } : null,
    },
  });
  notice.value = cloud
    ? "已切换到 MiMo：云端 Chat 采用本次显式授权，Embedding/Rerank 保持本地。"
    : "已切换到本地 Ollama：空间绑定已关闭云端出境。";
}

async function selectRuntimeMode(): Promise<void> {
  const previousMode = runtimeMode.value;
  const providerType = runtimeMode.value === "MIMO" ? "MIMO" : "OLLAMA";
  const route = chatRoutes.value.find((item) => {
    const profileId = item.candidates[0]?.modelProfileId;
    const profile = modelProfiles.value.find((candidate) => candidate.modelProfileId === profileId);
    const provider = providerConnections.value.find((connection) => connection.providerConnectionId === profile?.providerConnectionId);
    return provider?.providerType === providerType;
  });
  if (!route) {
    error.value = runtimeMode.value === "MIMO" ? "当前空间还没有已发布的 MiMo Chat route，请先在配置中心初始化。" : "当前空间还没有可用的本地 Ollama Chat route。";
    return;
  }
  selectedRouteId.value = route.modelRouteId;
  const profile = modelProfiles.value.find((candidate) => candidate.modelProfileId === route.candidates[0]?.modelProfileId);
  selectedProfileId.value = profile?.modelProfileId ?? "";
  model.value = profile?.modelName ?? model.value;
  try {
    await loadPromptVersion(selectedTemplate.value);
    await applyChatBinding(route);
  } catch (value) {
    runtimeMode.value = previousMode;
    error.value = describeError(value);
  }
}

function describeError(value: unknown): string {
  if (!(value instanceof ApiError)) return "业务闭环配置加载失败，请稍后重试。";
  if (value.status === 403) return "当前角色无权读取或操作此空间配置。";
  if (value.status === 404) return "配置资源不存在，或不属于当前空间。";
  return value.problem?.detail ?? value.message;
}

function selectRoute(): void {
  const candidate = selectedRoute.value?.candidates[0];
  selectedProfileId.value = candidate?.modelProfileId ?? "";
  const profile = modelProfiles.value.find((item) => item.modelProfileId === selectedProfileId.value);
  if (profile) model.value = profile.modelName;
}

async function readWithTimeout<T>(label: string, task: Promise<T>, fallback: T, failures: string[]): Promise<T> {
  try {
    return await Promise.race([
      task,
      new Promise<T>((_, reject) => window.setTimeout(() => reject(new Error(`${label} 读取超时`)), 8000)),
    ]);
  } catch (value) {
    failures.push(`${label}：${describeError(value)}`);
    return fallback;
  }
}

async function loadPromptVersion(template: PromptTemplate | undefined): Promise<void> {
  promptVersion.value = null;
  if (!template?.currentVersion) return;
  promptVersion.value = await readWithTimeout(
    "Prompt version",
    apiFetch<PromptVersion>(`/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}/prompt-templates/${encodeURIComponent(template.promptTemplateId)}/versions/${template.currentVersion}`),
    null,
    [],
  );
}

async function loadFlow(): Promise<void> {
  if (!props.selectedSpaceId) return;
  loading.value = true; error.value = ""; notice.value = "";
  try {
    const failures: string[] = [];
    const [providers, profiles, routes, prompts, active, currentJobs, indexPage] = await Promise.all([
      readWithTimeout("Provider connection", listAllCursorPages<ProviderConnection>(`/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}/provider-connections`, { limit: 10 }), [], failures),
      readWithTimeout("Model profile", listAllCursorPages<ModelProfile>(`/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}/model-profiles`, { limit: 10 }), [], failures),
      readWithTimeout("Model route", listAllCursorPages<ModelRoute>(`/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}/model-routes`, { limit: 10 }), [], failures),
      readWithTimeout("Prompt template", listAllCursorPages<PromptTemplate>(`/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}/prompt-templates`, { limit: 10 }), [], failures),
      readWithTimeout("Active index", getActiveIndex(props.selectedSpaceId), null, failures),
      readWithTimeout("Ingestion jobs", listAllCursorPages<IngestionJobView>(`/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}/jobs`, { limit: 10 }), [], failures),
      readWithTimeout("Index versions", listAllCursorPages<IndexView>(`/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}/indexes`, { limit: 10 }), [], failures),
    ]);
    providerConnections.value = providers;
    modelProfiles.value = profiles;
    modelRoutes.value = routes;
    promptTemplates.value = prompts;
    activeIndex.value = active;
    jobs.value = currentJobs;
    indexes.value = indexPage;
    indexVersionId.value = active?.pointer.activeIndexVersionId ?? "";
    datasetHash.value = active?.datasetHash ?? "";
    if (!selectedRouteId.value || !routes.some((item) => item.modelRouteId === selectedRouteId.value)) selectedRouteId.value = routes.find((item) => item.purpose === "CHAT" && item.status === "ACTIVE" && item.egressClass === "CLOUD")?.modelRouteId ?? routes.find((item) => item.purpose === "CHAT" && item.status === "ACTIVE")?.modelRouteId ?? routes[0]?.modelRouteId ?? "";
    const route = routes.find((item) => item.modelRouteId === selectedRouteId.value);
    runtimeMode.value = route?.egressClass === "CLOUD" ? "MIMO" : "LOCAL";
    if (!selectedProfileId.value || !route?.candidates.some((candidate) => candidate.modelProfileId === selectedProfileId.value)) selectedProfileId.value = route?.candidates[0]?.modelProfileId ?? profiles.find((item) => item.purpose === "CHAT" && item.status === "PUBLISHED")?.modelProfileId ?? "";
    if (!selectedPromptTemplateId.value || !prompts.some((item) => item.promptTemplateId === selectedPromptTemplateId.value)) selectedPromptTemplateId.value = prompts.find((item) => item.purpose === "CHAT" && item.currentVersion !== null)?.promptTemplateId ?? prompts[0]?.promptTemplateId ?? "";
    model.value = profiles.find((item) => item.modelProfileId === selectedProfileId.value)?.modelName ?? "mimo-v2.5";
    void loadPromptVersion(prompts.find((item) => item.promptTemplateId === selectedPromptTemplateId.value)).catch((value) => {
      error.value = describeError(value);
    });
    if (failures.length) error.value = `部分真实状态读取失败：${failures.join("；")}`;
  } catch (value) {
    error.value = describeError(value);
  } finally {
    loading.value = false;
  }
}

async function uploadFile(): Promise<void> {
  if (!selectedFiles.value.length || !props.selectedSpaceId) return;
  uploadBusy.value = true; error.value = ""; notice.value = "";
  try {
    const submitted: Array<{ jobId: string; documentRevisionId: string; sourceId: string }> = [];
    for (const file of selectedFiles.value) {
      submitted.push(await uploadMarkdown(props.selectedSpaceId, file, file.webkitRelativePath || file.name));
    }
    notice.value = submitted.length === 1
      ? `已提交 ${submitted[0].jobId}，Worker 正在执行真实摄取。`
      : `已提交 ${submitted.length} 个 notes 文档，Worker 正在后台执行真实摄取。`;
    selectedFiles.value = [];
    await loadFlow();
    if (submitted.length === 1) await waitForIngestion(submitted[0].jobId);
  } catch (value) { error.value = describeError(value); }
  finally { uploadBusy.value = false; }
}

async function ingestWeb(): Promise<void> {
  if (!props.selectedSpaceId || !webUrl.value.trim() || !webEgressApproved.value) {
    error.value = "请输入网页 URL，并明确确认本次网页加载允许云端出境。";
    return;
  }
  uploadBusy.value = true; error.value = ""; notice.value = "";
  try {
    const submitted = await ingestWebSource(props.selectedSpaceId, webUrl.value.trim(), true);
    webUrl.value = ""; webEgressApproved.value = false;
    notice.value = "网页已提交 " + submitted.jobId + "，Worker 正在执行真实摄取。";
    await loadFlow();
    await waitForIngestion(submitted.jobId);
  } catch (value) { error.value = describeError(value); }
  finally { uploadBusy.value = false; }
}

async function waitForIngestion(jobId: string): Promise<void> {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    try {
      const current = await getIngestionJob(props.selectedSpaceId, jobId);
      const existingIndex = jobs.value.findIndex((item) => item.job.id === jobId);
      if (existingIndex >= 0) jobs.value.splice(existingIndex, 1, current);
      else jobs.value.unshift(current);
      if (["SUCCEEDED", "FAILED", "DEAD_LETTER", "CANCELLED"].includes(current.job.status)) {
        if (current.job.documentRevisionId) await showParseReport(current.job.documentRevisionId);
        notice.value = current.job.status === "SUCCEEDED"
          ? "摄取已完成：Parse Report、chunk、embedding 和候选索引均已由服务端确认。"
          : `摄取结束，状态为 ${current.job.status}；请查看 Run/任务详情中的错误。`;
        await loadFlow();
        return;
      }
    } catch (value) {
      error.value = describeError(value);
    }
    const current = jobs.value.find((item) => item.job.id === jobId);
    if (current && ["SUCCEEDED", "FAILED", "DEAD_LETTER", "CANCELLED"].includes(current.job.status)) {
      if (current.job.documentRevisionId) await showParseReport(current.job.documentRevisionId);
      notice.value = current.job.status === "SUCCEEDED"
        ? "摄取已完成：Parse Report、chunk、embedding 和 active index 均已由服务端确认。"
        : `摄取结束，状态为 ${current.job.status}；请查看 Run/任务详情中的错误。`;
      return;
    }
    await new Promise<void>((resolve) => window.setTimeout(resolve, 2000));
  }
  notice.value = "摄取仍在后台执行；页面只轮询服务端状态，不会伪造进度。可稍后刷新继续查看。";
}

async function showParseReport(revisionId: string | null): Promise<void> {
  if (!revisionId) return;
  try {
    parseReport.value = await getParseReport(props.selectedSpaceId, revisionId);
  } catch (value) {
    error.value = describeError(value);
  }
}

async function publishCandidate(index: IndexView): Promise<void> {
  if (!props.selectedSpaceId || index.state !== "READY") return;
  publishBusy.value = index.indexVersionId;
  error.value = "";
  notice.value = "正在通过服务端原子发布候选索引…";
  try {
    await publishIndex(props.selectedSpaceId, index.indexVersionId);
    notice.value = `索引 v${index.versionNo} 已发布为当前空间 active index。`;
    await loadFlow();
  } catch (value) {
    error.value = describeError(value);
  } finally {
    publishBusy.value = "";
  }
}

async function rollbackPrevious(indexVersionId: string): Promise<void> {
  const pointerVersion = activeIndex.value?.pointer.versionNo;
  if (!props.selectedSpaceId || pointerVersion === undefined) return;
  publishBusy.value = indexVersionId;
  error.value = "";
  try {
    await rollbackIndex(props.selectedSpaceId, indexVersionId, pointerVersion);
    notice.value = "已回滚上一版索引，current/previous pointer 已更新。";
    await loadFlow();
  } catch (value) {
    error.value = describeError(value);
  } finally {
    publishBusy.value = "";
  }
}

async function retireVersion(index: IndexView): Promise<void> {
  const pointerVersion = activeIndex.value?.pointer.versionNo;
  if (!props.selectedSpaceId || pointerVersion === undefined || index.state !== "ACTIVE") return;
  publishBusy.value = index.indexVersionId;
  error.value = "";
  try {
    await retireIndex(props.selectedSpaceId, index.indexVersionId, pointerVersion);
    notice.value = `索引 v${index.versionNo} 已退役，仍保留在生命周期记录中。`;
    await loadFlow();
  } catch (value) {
    error.value = describeError(value);
  } finally {
    publishBusy.value = "";
  }
}

function chooseFile(event: Event): void {
  selectedFiles.value = Array.from((event.target as HTMLInputElement).files ?? []).filter(isSupportedSourceFile);
}

function chooseFolder(event: Event): void {
  selectedFiles.value = Array.from((event.target as HTMLInputElement).files ?? []).filter(isSupportedSourceFile);
}

function isSupportedSourceFile(file: File): boolean {
  const relativePath = file.webkitRelativePath || file.name;
  const pathSegments = relativePath.split(/[\\/]/);
  return /\.(md|markdown|txt|pdf|docx|pptx|xlsx)$/i.test(file.name) && !pathSegments.includes(".obsidian");
}

async function refreshPromptVersion(): Promise<void> {
  try {
    await loadPromptVersion(selectedTemplate.value);
  } catch (value) {
    error.value = describeError(value);
  }
}

async function hashConfig(): Promise<string> {
  const source = JSON.stringify({ spaceId: props.selectedSpaceId, routeVersionId: selectedRouteId.value, profileVersionId: selectedProfileId.value, providerConnectionId: selectedProvider.value?.providerConnectionId, promptVersionId: promptVersion.value?.promptVersionId, model: model.value.trim() });
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(source));
  return [...new Uint8Array(digest)].map((item) => item.toString(16).padStart(2, "0")).join("");
}

async function startAnswer(): Promise<void> {
  if (!canStart.value || !selectedProvider.value || !promptVersion.value) return;
  const configHash = await hashConfig();
  notice.value = "配置已校验，正在打开带引用问答。";
  emit("start-answer", { routeVersionId: selectedRouteId.value, profileVersionId: selectedProfileId.value, providerConnectionId: selectedProvider.value.providerConnectionId, promptVersionId: promptVersion.value.promptVersionId, model: model.value.trim(), datasetHash: datasetHash.value.trim(), configHash, allowCloudEgress: selectedRoute.value?.egressClass === "CLOUD" });
}

watch(() => props.selectedSpaceId, () => { if (props.selectedSpaceId) void loadFlow(); }, { immediate: true });
</script>

<template>
  <details class="advanced-source">
    <summary><span><small>可选入口</small><strong>从公开网页加载内容</strong></span><span class="summary-action">需显式授权 · 展开</span></summary>
    <div class="advanced-source-body">
      <p>网页只会进入当前空间，且必须通过服务端白名单与公网地址策略；Git 来源请在下方“来源库”统一管理。</p>
      <div class="web-source-form"><div class="field"><label for="web-source-url">网页链接</label><input id="web-source-url" v-model="webUrl" type="url" placeholder="https://example.com/knowledge" /></div><label class="check-row"><input v-model="webEgressApproved" type="checkbox" />我确认本次加载允许访问已配置白名单的公开网页</label><button type="button" class="secondary-button" :disabled="uploadBusy || !webUrl.trim() || !webEgressApproved" @click="ingestWeb">{{ uploadBusy ? "加载中…" : "加载网页到知识库" }}</button></div>
    </div>
  </details>
  <section class="view-section business-flow" aria-labelledby="business-flow-heading">
    <div class="section-heading"><div><p class="eyebrow">00 · Guided business flow</p><h2 id="business-flow-heading">业务闭环</h2><p>按真实服务端状态完成配置，再进入问答。这里不接受手填不存在的资源，也不会把前端选中状态当成权限依据。</p></div><div class="read-only-note" :class="{ warning: !canStart }">{{ canStart ? "可进入问答" : "尚有步骤未完成" }}</div></div>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <SourceLibraryView :selected-space-id="selectedSpaceId" :current-role="currentRole" />
    <TaskCenterView :selected-space-id="selectedSpaceId" :current-role="currentRole" />
    <section v-if="needsRuntimeSetup" class="card setup-card" aria-labelledby="setup-heading">
      <div><span class="card-label">先验证运行环境</span><h3 id="setup-heading">还没有可用的问答模型</h3><p class="muted">先在配置中心登记并实测 Provider，再发布与实测结果匹配的模型配置。完整 RAG 还需要 P7-CORE-05 接入真实 Rerank；页面不会创建未经验证的伪闭环。</p></div>
      <div class="setup-actions"><button type="button" :disabled="!canManage" @click="emit('open-control', 'providers')">前往 Provider 验证</button></div>
      <small v-if="!canManage" class="permission-hint">当前角色没有配置权限，请让空间管理员完成模型配置。</small>
    </section>
    <div class="flow-steps"><article class="card flow-step done"><span class="step-number">01</span><div><strong>空间</strong><p>{{ selectedSpaceId ? `当前空间 ${selectedSpaceId}` : "请选择空间" }}</p></div><span class="step-state">{{ selectedSpaceId ? "完成" : "待完成" }}</span></article><article class="card flow-step" :class="{ done: hasPublishedModel }"><span class="step-number">02</span><div><strong>模型路由</strong><p>{{ hasPublishedModel ? `${selectedProvider?.providerType} · ${selectedProfile?.purpose}` : "需要 ACTIVE route、PUBLISHED profile 和 ACTIVE provider" }}</p></div><span class="step-state">{{ hasPublishedModel ? "完成" : "去配置" }}</span></article><article class="card flow-step" :class="{ done: hasPublishedPrompt }"><span class="step-number">03</span><div><strong>Prompt 版本</strong><p>{{ hasPublishedPrompt ? `已发布 v${promptVersion?.version}` : "需要已发布的 Prompt version" }}</p></div><span class="step-state">{{ hasPublishedPrompt ? "完成" : "去配置" }}</span></article><article class="card flow-step" :class="{ done: hasIndexIdentity }"><span class="step-number">04</span><div><strong>数据与索引</strong><p>{{ hasIndexIdentity ? `active index v${activeIndex?.index?.versionNo ?? "?"}` : "上传 Markdown 并等待 Worker 发布 active index" }}</p></div><span class="step-state">{{ hasIndexIdentity ? "完成" : "待完成" }}</span></article><article class="card flow-step" :class="{ done: canStart }"><span class="step-number">05</span><div><strong>带引用问答</strong><p>{{ canStart ? "运行配置已准备好，可开始回答" : "完成前置步骤后解锁" }}</p></div><span class="step-state">{{ canStart ? "已解锁" : "锁定" }}</span></article></div>

    <div class="flow-layout"><div class="card flow-config"><div class="card-title"><div><span class="card-label">01 · Data source + setup</span><h3>接入内容并确认问答配置</h3></div><button type="button" class="quiet-button" :disabled="loading" @click="loadFlow">{{ loading ? "读取中…" : "刷新真实状态" }}</button></div><div class="source-entry"><div><strong>常用本地知识库</strong><p class="muted">选择 notes 文件夹或单个文件（Markdown、TXT、PDF、DOCX、PPTX、XLSX）。服务端自动排除 .obsidian、附件和不支持的文件。</p></div><div class="source-actions"><label class="file-picker" for="flow-folder"><span>选择 notes 文件夹</span><input id="flow-folder" type="file" webkitdirectory directory multiple accept=".md,.markdown,.txt,.pdf,.docx,.pptx,.xlsx,text/markdown,text/plain,application/pdf" @change="chooseFolder" /></label><label class="file-picker secondary-picker" for="flow-file"><span>选择单个文件</span><input id="flow-file" type="file" accept=".md,.markdown,.txt,.pdf,.docx,.pptx,.xlsx,text/markdown,text/plain,application/pdf" @change="chooseFile" /></label></div><div v-if="selectedFiles.length" class="selected-files"><span>{{ selectedFiles.length }} 个文件已选择</span><button type="button" :disabled="uploadBusy" @click="uploadFile">{{ uploadBusy ? "提交中…" : "上传并发起摄取" }}</button></div></div><div v-if="jobs.length" class="job-list"><div v-for="item in jobs" :key="item.job.id" class="job-row"><span>{{ item.job.id }}</span><strong>{{ item.job.status }}</strong><small>{{ item.steps.map((step) => `${step.stepName}:${step.status}`).join(" · ") || "等待 Worker" }}</small><button v-if="item.job.documentRevisionId" type="button" class="quiet-button" @click="showParseReport(item.job.documentRevisionId)">查看 Parse Report</button></div></div><div v-if="parseReport" class="parse-report"><strong>Parse Report：{{ parseReport.status }}</strong><span>{{ parseReport.parserName }} {{ parseReport.parserVersion }} · {{ parseReport.characterCount }} 字符 · {{ parseReport.tokenCount }} tokens</span><small>{{ parseReport.errors || "无错误" }}</small></div><div class="section-divider"><span>02 · Runtime setup</span><small>以下资源必须是服务端真实存在且已发布的版本</small></div><div class="form-grid"><div class="field wide"><label for="flow-route">ACTIVE Model Route</label><select id="flow-route" v-model="selectedRouteId" @change="selectedProfileId = selectedRoute?.candidates[0]?.modelProfileId ?? ''"><option value="">请选择 route</option><option v-for="item in modelRoutes" :key="item.modelRouteId" :value="item.modelRouteId">{{ item.purpose }} · {{ item.status }}</option></select></div><div class="field"><label for="flow-profile">PUBLISHED Model Profile</label><select id="flow-profile" v-model="selectedProfileId"><option value="">请选择 profile</option><option v-for="item in modelProfiles.filter((candidate) => candidate.status === 'PUBLISHED')" :key="item.modelProfileId" :value="item.modelProfileId">{{ item.purpose }} · {{ item.status }}</option></select></div><div class="field"><label for="flow-model">模型名</label><input id="flow-model" v-model="model" placeholder="qwen3.5:9b" /></div><div class="field wide"><label for="flow-prompt">PUBLISHED Prompt Template</label><select id="flow-prompt" v-model="selectedPromptTemplateId" @change="refreshPromptVersion"><option value="">请选择 Prompt</option><option v-for="item in promptTemplates" :key="item.promptTemplateId" :value="item.promptTemplateId">{{ item.name }} · {{ item.currentVersion ? `v${item.currentVersion}` : "未发布" }}</option></select></div><div v-if="promptVersion" class="prompt-version-summary field wide"><label>当前 Prompt version</label><output>v{{ promptVersion.version }} · {{ promptVersion.state }} · {{ promptVersion.contentHash }}</output><small>messages: {{ promptVersion.messages.length }} · variables: {{ Object.keys(promptVersion.variableSchema).join(", ") || "—" }} · output: {{ Object.keys(promptVersion.outputContract).join(", ") || "—" }}</small></div></div><div class="flow-boundary-note" :class="{ warning: !hasIndexIdentity }"><strong>真实状态：</strong><span>{{ hasIndexIdentity ? "active index 已由服务端原子发布，可以进入问答。" : indexes.some((item) => item.state === 'READY') ? "候选索引 READY，必须点击发布才能进入问答。" : "上传后等待 Worker 完成 parse、chunk、embedding 和索引验证；刷新可读取服务端状态。" }}</span></div><div class="button-row"><button type="button" :disabled="!canStart" @click="startAnswer">进入带引用问答</button><button type="button" class="secondary-button" @click="emit('open-control', 'providers')">配置 Provider / 模型 / Prompt</button></div></div><aside class="flow-side"><div class="card"><span class="card-label">当前权限</span><h3>{{ currentRole }}</h3><p>配置是否可写由服务端权限裁决。当前向导只读取真实状态，不绕过 VIEWER 或跨空间访问。</p></div><div class="card"><span class="card-label">已读取资源</span><ul class="flow-resource-list"><li>{{ providerConnections.length }} 个 Provider connection</li><li>{{ modelProfiles.length }} 个 Model Route / Profile</li><li>{{ promptTemplates.length }} 个 Prompt template</li><li>{{ jobs.length }} 个 ingestion job</li><li>{{ indexes.length }} 个 index version</li></ul></div></aside></div>
  </section>
  <div v-if="props.selectedSpaceId" class="card runtime-switcher" aria-label="Chat 模型路线切换">
    <div><span class="card-label">Chat route switch</span><strong>选择本次问答模型</strong><p class="muted">切换只选择已发布的 Chat route；出境授权由服务端空间绑定强制校验，不会自动回退。</p></div>
    <div class="field"><label for="runtime-mode">Chat provider</label><select id="runtime-mode" v-model="runtimeMode" @change="selectRuntimeMode"><option value="LOCAL">本地 Ollama（LOCAL_ONLY）</option><option value="MIMO" :disabled="!chatRoutes.some((item) => item.egressClass === 'CLOUD')">Xiaomi MiMo（显式云端授权）</option></select></div>
  </div>
  <IndexLifecycleView
    v-if="props.selectedSpaceId"
    :indexes="indexes"
    :active-index="activeIndex"
    :can-manage="canManage"
    :busy-index-id="publishBusy"
    @publish="publishCandidate"
    @rollback="rollbackPrevious"
    @retire="retireVersion"
  />
</template>

<style scoped>
.setup-card { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-top: 15px; padding: 18px; border-color: #a9c8ed; background: linear-gradient(135deg, #f3f8ff, #fbfdff); }.setup-card h3 { margin: 5px 0 6px; color: #284572; }.setup-card .muted { max-width: 700px; margin: 0; line-height: 1.5; }.setup-actions { display: flex; flex: 0 0 auto; flex-wrap: wrap; gap: 8px; }.permission-hint { display: block; margin-top: 9px; color: #8a5f1d; }
.business-flow { margin-top: 26px; }.flow-steps { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }.flow-step { position: relative; min-height: 146px; padding: 15px; }.flow-step.done { border-color: #9ccfb2; background: #fbfffc; }.step-number { display: inline-grid; width: 30px; height: 30px; place-items: center; margin-bottom: 15px; border-radius: 9px; background: #e8eef8; color: #315b97; font-size: .7rem; font-weight: 900; }.flow-step.done .step-number { background: #ddf2e5; color: #1b754d; }.flow-step strong { display: block; color: #284572; }.flow-step p { min-height: 38px; margin: 7px 0 12px; color: #71809a; font-size: .75rem; line-height: 1.45; overflow-wrap: anywhere; }.step-state { color: #8794a8; font-size: .7rem; font-weight: 800; }.flow-step.done .step-state { color: #1b754d; }.flow-layout { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(260px, .55fr); gap: 15px; margin-top: 15px; }.flow-config .card-title { align-items: flex-start; margin-bottom: 20px; }.flow-config .form-grid { margin-bottom: 18px; }.flow-boundary-note { display: flex; gap: 8px; margin: 16px 0; padding: 12px 14px; border: 1px dashed #e0bb76; border-radius: 10px; background: #fffaf0; color: #80632e; font-size: .78rem; line-height: 1.5; }.flow-boundary-note strong { color: #684b17; }.flow-side { display: grid; align-content: start; gap: 15px; }.flow-side p { margin: 9px 0 0; color: #687893; font-size: .82rem; line-height: 1.55; }.flow-resource-list { display: grid; gap: 10px; margin: 12px 0 0; padding-left: 18px; color: #526b92; font-size: .82rem; line-height: 1.4; }
@media (max-width: 1050px) { .flow-steps { grid-template-columns: repeat(3, minmax(0, 1fr)); }.flow-layout { grid-template-columns: 1fr; }.flow-side { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .flow-steps, .flow-side { grid-template-columns: 1fr; }.flow-step { min-height: auto; }.flow-step p { min-height: auto; }.flow-boundary-note { flex-direction: column; }.setup-card { align-items: stretch; flex-direction: column; }.setup-actions button { width: 100%; } }
.source-entry { display: grid; gap: 13px; margin: 2px 0 22px; padding: 18px; border: 1px solid #e3e8f3; border-radius: 15px; background: linear-gradient(135deg, #f7f9ff, #fbfdff); }.source-entry strong { display: block; color: #25365d; font-size: .94rem; }.source-entry .muted { max-width: 650px; margin: 6px 0 0; font-size: .8rem; }.source-actions { display: flex; flex-wrap: wrap; gap: 9px; }.file-picker { position: relative; display: inline-flex; align-items: center; padding: 10px 13px; border: 1px solid #bdc9f0; border-radius: 10px; background: #eef1ff; color: #4d5fe0; cursor: pointer; font-size: .76rem; font-weight: 800; }.file-picker:hover { border-color: #8794ed; background: #e5e9ff; }.file-picker input { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }.secondary-picker { border-color: #d6ddeb; background: #fff; color: #60708d; }.selected-files { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #56708f; font-size: .76rem; }.selected-files button { padding: 8px 11px; font-size: .73rem; }.section-divider { display: flex; align-items: baseline; justify-content: space-between; gap: 14px; margin: 23px 0 15px; padding-top: 18px; border-top: 1px solid #edf1f6; }.section-divider span { color: #5261b4; font-size: .74rem; font-weight: 900; letter-spacing: .08em; text-transform: uppercase; }.section-divider small { color: #91a0b2; font-size: .7rem; }.flow-config { padding: clamp(20px, 2.8vw, 30px); }
@media (max-width: 600px) { .source-actions, .selected-files, .section-divider { align-items: stretch; flex-direction: column; }.file-picker, .selected-files button { justify-content: center; width: 100%; } }
</style>

<style scoped>
.advanced-source { margin: 15px 0 0; border: 1px solid #e5e8f1; border-radius: 15px; background: rgba(255, 255, 255, .76); }
.advanced-source summary { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 15px 18px; color: #25365d; cursor: pointer; list-style: none; }
.advanced-source summary::-webkit-details-marker { display: none; }
.advanced-source summary::after { content: "+"; color: #6473dd; font-size: 1.25rem; font-weight: 400; }
.advanced-source[open] summary::after { content: "–"; }
.advanced-source summary small, .advanced-source summary strong { display: block; }
.advanced-source summary small { margin-bottom: 3px; color: #8490a7; font-size: .68rem; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }
.advanced-source summary strong { font-size: .86rem; }
.summary-action { color: #667493; font-size: .72rem; white-space: nowrap; }
.advanced-source-body { padding: 0 18px 18px; }
.advanced-source-body > p { margin: 0 0 13px; color: #74819a; font-size: .78rem; line-height: 1.55; }
.web-source-form { display: flex; align-items: end; flex-wrap: wrap; gap: 12px; }
.web-source-form .field { flex: 1 1 320px; }
.web-source-form .check-row { display: flex; align-items: center; gap: 7px; color: #526b92; font-size: .78rem; }
.web-source-form .check-row input { width: auto; accent-color: #5969e8; }
@media (max-width: 600px) {
  .advanced-source summary { align-items: flex-start; }
  .summary-action { display: none; }
  .web-source-form { align-items: stretch; flex-direction: column; }
}
</style>
