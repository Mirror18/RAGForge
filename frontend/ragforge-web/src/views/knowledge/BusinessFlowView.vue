<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ApiError, getActiveIndex, getIngestionJob, getParseReport, ingestWebSource, listAllCursorPages, publishIndex, rollbackIndex, retireIndex, uploadMarkdown, type ActiveIndexView, type AnswerDefaults, type IndexView, type IngestionJobView, type ModelProfile, type ModelRoute, type ParseReportView, type PlatformRole, type PromptTemplate, type PromptVersion, type ProviderConnection, type SpaceBinding, type SpaceRole, apiFetch } from "../../api";
import SourceLibraryView from "./SourceLibraryView.vue";
import TaskCenterView from "./TaskCenterView.vue";
import IndexLifecycleView from "./IndexLifecycleView.vue";
import { purposeLabel, stateLabel } from "../../i18n";

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
const pageSize = 10;
const jobPage = ref(1);

const selectedRoute = computed(() => modelRoutes.value.find((item) => item.modelRouteId === selectedRouteId.value) ?? null);
const selectedProfile = computed(() => modelProfiles.value.find((item) => item.modelProfileId === selectedProfileId.value) ?? null);
const selectedProvider = computed(() => providerConnections.value.find((item) => item.providerConnectionId === selectedProfile.value?.providerConnectionId) ?? null);
const selectedTemplate = computed(() => promptTemplates.value.find((item) => item.promptTemplateId === selectedPromptTemplateId.value) ?? null);
const chatRoutes = computed(() => modelRoutes.value.filter((item) => item.purpose === "CHAT" && item.status === "ACTIVE" && item.egressClass === "CLOUD"));
const selectedRouteCandidate = computed(() => selectedRoute.value?.candidates.find((candidate) => candidate.modelProfileId === selectedProfileId.value) ?? null);
const hasPublishedModel = computed(() => selectedRoute.value?.purpose === "CHAT"
  && selectedRoute.value.status === "ACTIVE"
  && selectedRoute.value.egressClass === "CLOUD"
  && selectedRouteCandidate.value?.egressClass === "CLOUD"
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
const hasImportedContent = computed(() => jobs.value.length > 0 || indexes.value.length > 0 || Boolean(activeIndex.value));
const jobPageCount = computed(() => Math.max(1, Math.ceil(jobs.value.length / pageSize)));
const visibleJobs = computed(() => jobs.value.slice((jobPage.value - 1) * pageSize, jobPage.value * pageSize));
const activeStep = computed(() => {
  if (!props.selectedSpaceId) return 1;
  if (!hasImportedContent.value) return 2;
  if (!hasIndexIdentity.value) return 3;
  return 4;
});
const nextAction = computed(() => {
  if (!props.selectedSpaceId) return "先在顶部选择或创建一个知识空间";
  if (!hasImportedContent.value) return "选择一份资料，导入到当前空间";
  if (!hasIndexIdentity.value) return "等待摄取完成，然后发布待发布索引";
  if (!hasPublishedModel.value || !hasPublishedPrompt.value) return "管理员只需完成一次回答配置";
  return "输入问题，开始一次带引用问答";
});

function apiPath(suffix: string): string {
  return `/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}${suffix}`;
}

async function applyChatBinding(route: ModelRoute): Promise<void> {
  if (route.egressClass !== "CLOUD") {
    throw new Error("当前产品只允许已授权的云端模型。");
  }
  const binding = await apiFetch<SpaceBinding>(apiPath("/space-bindings"));
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
      cloudEgressEnabled: true,
      cloudEgressAuthorization: {
        approvalId: crypto.randomUUID(),
        approvedBy: props.currentUserId,
        approvedAt: approvedAt.toISOString(),
        expiresAt: expiresAt.toISOString(),
        scope: "CHAT",
      },
    },
  });
  notice.value = "已绑定云端 Chat：本次授权后才能把空间内容送给模型。";
}

async function bindPublishedCloudRoute(): Promise<void> {
  const route = chatRoutes.value[0];
  if (!route) {
    error.value = "当前空间还没有已发布的云端回答路线，请先在「云端模型」中完成配置。";
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
    jobPage.value = Math.min(jobPage.value, jobPageCount.value);
    indexVersionId.value = active?.pointer.activeIndexVersionId ?? "";
    datasetHash.value = active?.datasetHash ?? "";
    if (!selectedRouteId.value || !routes.some((item) => item.modelRouteId === selectedRouteId.value)) selectedRouteId.value = routes.find((item) => item.purpose === "CHAT" && item.status === "ACTIVE" && item.egressClass === "CLOUD")?.modelRouteId ?? "";
    const route = routes.find((item) => item.modelRouteId === selectedRouteId.value);
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

function setJobPage(nextPage: number): void {
  jobPage.value = Math.min(Math.max(nextPage, 1), jobPageCount.value);
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
    <summary><span><small>可选入口</small><strong>从公开网页导入资料</strong></span><span class="summary-action">需要明确授权 · 展开</span></summary>
    <div class="advanced-source-body">
      <p>网页只会进入当前空间，并且必须通过服务端白名单和公网地址策略；网页内容会离开本机，Git 来源请在下方“来源与任务管理”中维护。</p>
      <div class="web-source-form"><div class="field"><label for="web-source-url">网页链接</label><input id="web-source-url" v-model="webUrl" type="url" placeholder="https://example.com/knowledge" /></div><label class="check-row"><input v-model="webEgressApproved" type="checkbox" />我确认本次加载允许访问已配置白名单的公开网页</label><button type="button" class="secondary-button" :disabled="uploadBusy || !webUrl.trim() || !webEgressApproved" @click="ingestWeb">{{ uploadBusy ? "加载中…" : "加载网页到知识库" }}</button></div>
    </div>
  </details>
  <section class="view-section business-flow" aria-labelledby="business-flow-heading">
    <div class="section-heading"><div><p class="eyebrow">00 · 四步业务向导</p><h2 id="business-flow-heading">从资料到答案</h2><p>只需要按顺序完成四步：选择空间、导入资料、发布索引、开始问答。模型和回答规则由向导自动带入，不需要手填内部编号。</p></div><div class="read-only-note" :class="{ warning: !canStart }">{{ canStart ? "已准备好提问" : `下一步：${nextAction}` }}</div></div>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <div class="next-action-banner"><span class="next-action-index">{{ String(activeStep).padStart(2, "0") }}</span><div><span class="card-label">现在要做什么</span><strong>{{ nextAction }}</strong><p>页面会读取服务端真实状态；完成后下一步会自动解锁。</p></div></div>
    <details class="advanced-management"><summary><span><small>可选管理</small><strong>来源与任务管理</strong></span><span class="summary-action">需要维护 Git 来源或排查任务时再展开</span></summary><div class="advanced-management-grid"><SourceLibraryView :selected-space-id="selectedSpaceId" :current-role="currentRole" /><TaskCenterView :selected-space-id="selectedSpaceId" :current-role="currentRole" /></div></details>
    <section v-if="needsRuntimeSetup" class="card setup-card" aria-labelledby="setup-heading">
      <div><span class="card-label">只需管理员完成一次</span><h3 id="setup-heading">当前空间还没有可用的回答配置</h3><p class="muted">管理员需要先验证模型服务，再发布模型能力和回答规则。配置完成后，普通使用者只需导入资料和提问，向导会自动复用已发布版本。</p></div>
      <div class="setup-actions"><button type="button" :disabled="!canManage" @click="emit('open-control', 'providers')">打开一次性配置</button></div>
      <small v-if="!canManage" class="permission-hint">当前角色没有配置权限，请让空间管理员完成这一步。</small>
    </section>
    <div class="flow-steps"><article class="card flow-step" :class="{ done: Boolean(selectedSpaceId), current: activeStep === 1 }"><span class="step-number">01</span><div><strong>选择空间</strong><p>{{ selectedSpaceId ? "当前空间已确定，资料和回答都会隔离在这里" : "请在页面顶部选择或创建空间" }}</p></div><span class="step-state">{{ selectedSpaceId ? "已完成" : "当前步骤" }}</span></article><article class="card flow-step" :class="{ done: hasImportedContent, current: activeStep === 2 }"><span class="step-number">02</span><div><strong>导入资料</strong><p>{{ hasImportedContent ? `已提交 ${jobs.length} 个摄取任务` : "选择 Markdown、TXT、PDF、DOCX、PPTX 或 XLSX" }}</p></div><span class="step-state">{{ hasImportedContent ? "已完成" : "当前步骤" }}</span></article><article class="card flow-step" :class="{ done: hasIndexIdentity, current: activeStep === 3 }"><span class="step-number">03</span><div><strong>发布索引</strong><p>{{ hasIndexIdentity ? `当前使用第 ${activeIndex?.index?.versionNo ?? "—"} 版索引` : "等待任务完成后，点击“发布为当前版本”" }}</p></div><span class="step-state">{{ hasIndexIdentity ? "已完成" : "当前步骤" }}</span></article><article class="card flow-step" :class="{ done: canStart, current: activeStep === 4 }"><span class="step-number">04</span><div><strong>开始问答</strong><p>{{ canStart ? "回答配置已自动带入，现在可以提问" : "回答配置或索引还未准备好" }}</p></div><span class="step-state">{{ canStart ? "已解锁" : "等待前置步骤" }}</span></article></div>

    <div class="flow-layout"><div class="card flow-config"><div class="card-title"><div><span class="card-label">第 2 步 · 导入资料</span><h3>把资料放进当前空间</h3><p class="muted">向导会自动等待摄取任务完成，并在下方展示可发布的索引。</p></div><button type="button" class="quiet-button" :disabled="loading" @click="loadFlow">{{ loading ? "读取中…" : "刷新状态" }}</button></div><div class="source-entry"><div><strong>从本机选择资料</strong><p class="muted">支持 Markdown、TXT、PDF、DOCX、PPTX、XLSX。浏览器只提交你明确选择的文件，服务端会排除 .obsidian 和不支持的文件。</p></div><div class="source-actions"><label class="file-picker" for="flow-folder"><span>选择资料文件夹</span><input id="flow-folder" type="file" webkitdirectory directory multiple accept=".md,.markdown,.txt,.pdf,.docx,.pptx,.xlsx,text/markdown,text/plain,application/pdf" @change="chooseFolder" /></label><label class="file-picker secondary-picker" for="flow-file"><span>选择单个文件</span><input id="flow-file" type="file" accept=".md,.markdown,.txt,.pdf,.docx,.pptx,.xlsx,text/markdown,text/plain,application/pdf" @change="chooseFile" /></label></div><div v-if="selectedFiles.length" class="selected-files"><span>{{ selectedFiles.length }} 个文件已选择</span><button type="button" :disabled="uploadBusy" @click="uploadFile">{{ uploadBusy ? "提交中…" : "开始导入" }}</button></div></div><div v-if="jobs.length" class="data-list-section"><div class="data-list-heading"><div><span class="card-label">导入任务数据列表</span><p class="muted">每页展示 10 条，按更新时间从服务端读取。</p></div><span class="data-list-count">共 {{ jobs.length }} 条</span></div><div class="data-table-wrap"><table class="data-table" aria-label="导入任务列表"><thead><tr><th scope="col">任务</th><th scope="col">状态</th><th scope="col">处理步骤</th><th scope="col">更新时间</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="item in visibleJobs" :key="item.job.id"><td><strong>摄取任务</strong><small>{{ item.job.id }}</small></td><td><span class="state-pill" :class="item.job.status.toLowerCase()">{{ stateLabel(item.job.status) }}</span></td><td><span class="step-summary">{{ item.steps.map((step) => `${step.stepName}:${stateLabel(step.status)}`).join(" · ") || "等待后台处理" }}</span></td><td>{{ new Date(item.job.updatedAt).toLocaleString() }}</td><td><button v-if="item.job.documentRevisionId" type="button" class="quiet-button" @click="showParseReport(item.job.documentRevisionId)">查看解析结果</button><span v-else class="muted">—</span></td></tr></tbody></table></div><div class="pagination-bar" data-pagination="ingestion-jobs"><span>第 {{ jobPage }} / {{ jobPageCount }} 页 · 共 {{ jobs.length }} 条</span><div><button type="button" class="quiet-button" :disabled="jobPage <= 1" aria-label="上一页" @click="setJobPage(jobPage - 1)">上一页</button><button type="button" class="quiet-button" :disabled="jobPage >= jobPageCount" aria-label="下一页" @click="setJobPage(jobPage + 1)">下一页</button></div></div></div><p v-else class="empty-state data-list-empty">还没有导入任务；选择资料后，任务会出现在这个列表中。</p><div v-if="parseReport" class="parse-report"><strong>解析结果：{{ stateLabel(parseReport.status) }}</strong><span>{{ parseReport.parserName }} {{ parseReport.parserVersion }} · {{ parseReport.characterCount }} 字符 · {{ parseReport.tokenCount }} 个词元</span><small>{{ parseReport.errors || "没有错误" }}</small></div><details class="advanced-config"><summary>查看自动带入的回答配置</summary><p class="field-hint">普通用户不需要修改这些内容；管理员只在首次配置或切换模型时使用。</p><div class="form-grid"><div class="field wide"><label for="flow-route">模型路线</label><select id="flow-route" v-model="selectedRouteId" @change="selectedProfileId = selectedRoute?.candidates[0]?.modelProfileId ?? ''"><option value="">自动选择已发布路线</option><option v-for="item in modelRoutes" :key="item.modelRouteId" :value="item.modelRouteId">{{ purposeLabel(item.purpose) }} · {{ stateLabel(item.status) }}</option></select></div><div class="field"><label for="flow-profile">模型能力档案</label><select id="flow-profile" v-model="selectedProfileId"><option value="">自动选择已发布档案</option><option v-for="item in modelProfiles.filter((candidate) => candidate.status === 'PUBLISHED')" :key="item.modelProfileId" :value="item.modelProfileId">{{ purposeLabel(item.purpose) }} · {{ stateLabel(item.status) }}</option></select></div><div class="field"><label for="flow-model">模型名称</label><input id="flow-model" v-model="model" placeholder="例如 qwen3.5:9b" /></div><div class="field wide"><label for="flow-prompt">回答规则模板</label><select id="flow-prompt" v-model="selectedPromptTemplateId" @change="refreshPromptVersion"><option value="">自动选择已发布模板</option><option v-for="item in promptTemplates" :key="item.promptTemplateId" :value="item.promptTemplateId">{{ item.name }} · {{ item.currentVersion ? `第 ${item.currentVersion} 版` : "未发布" }}</option></select></div><div v-if="promptVersion" class="prompt-version-summary field wide"><label>当前回答规则</label><output>第 {{ promptVersion.version }} 版 · {{ stateLabel(promptVersion.state) }}</output><small>内部版本哈希：{{ promptVersion.contentHash }}</small></div></div></details><div class="section-divider"><span>第 3 步 · 发布索引</span><small>任务完成后，只有验证通过的版本可以发布</small></div><div class="flow-boundary-note" :class="{ warning: !hasIndexIdentity }"><strong>{{ hasIndexIdentity ? "可以提问" : indexes.some((item) => item.state === 'READY') ? "还差一步" : "正在处理" }}：</strong><span>{{ hasIndexIdentity ? "当前索引已由服务端原子发布，可以进入问答。" : indexes.some((item) => item.state === 'READY') ? "下方会出现“发布为当前版本”按钮，点击后才能用于回答。" : "等待后台完成解析、切分、向量化和索引验证；页面不会伪造进度。" }}</span></div><div class="button-row"><button type="button" :disabled="!canStart" @click="startAnswer">去提问</button><button type="button" class="secondary-button" @click="emit('open-control', 'providers')">管理员高级设置</button></div></div><aside class="flow-side"><div class="card"><span class="card-label">当前权限</span><h3>{{ stateLabel(currentRole) }}</h3><p>配置和写入权限由服务端裁决。你只需要关注当前步骤，技术细节已自动收拢。</p></div><div class="card runtime-choice"><span class="card-label">回答方式</span><h3>仅云端 API</h3><p>{{ chatRoutes.length ? "当前空间将使用已授权的 OpenAI 兼容云端模型；未授权的请求会被拒绝。" : "还没有已发布的云端回答路线，请先打开「云端模型」完成配置。" }}</p><button v-if="chatRoutes.length" type="button" class="secondary-button" @click="bindPublishedCloudRoute">确认并授权本次云端调用</button></div><div class="card"><span class="card-label">后台真实状态</span><ul class="flow-resource-list"><li>{{ providerConnections.length }} 个模型服务连接</li><li>{{ modelProfiles.length }} 个模型能力档案</li><li>{{ promptTemplates.length }} 个回答规则模板</li><li>{{ jobs.length }} 个导入任务</li><li>{{ indexes.length }} 个索引版本</li></ul></div></aside></div>
  </section>
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
.business-flow { margin-top: 26px; }.flow-steps { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }.flow-step { position: relative; min-height: 146px; padding: 15px; }.flow-step.done { border-color: #9ccfb2; background: #fbfffc; }.step-number { display: inline-grid; width: 30px; height: 30px; place-items: center; margin-bottom: 15px; border-radius: 9px; background: #e8eef8; color: #315b97; font-size: .7rem; font-weight: 900; }.flow-step.done .step-number { background: #ddf2e5; color: #1b754d; }.flow-step strong { display: block; color: #284572; }.flow-step p { min-height: 38px; margin: 7px 0 12px; color: #71809a; font-size: .75rem; line-height: 1.45; overflow-wrap: anywhere; }.step-state { color: #8794a8; font-size: .7rem; font-weight: 800; }.flow-step.done .step-state { color: #1b754d; }.flow-layout { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(260px, .55fr); gap: 15px; margin-top: 15px; }.flow-config .card-title { align-items: flex-start; margin-bottom: 20px; }.flow-config .form-grid { margin-bottom: 18px; }.flow-boundary-note { display: flex; gap: 8px; margin: 16px 0; padding: 12px 14px; border: 1px dashed #e0bb76; border-radius: 10px; background: #fffaf0; color: #80632e; font-size: .78rem; line-height: 1.5; }.flow-boundary-note strong { color: #684b17; }.flow-side { display: grid; align-content: start; gap: 15px; }.flow-side p { margin: 9px 0 0; color: #687893; font-size: .82rem; line-height: 1.55; }.flow-resource-list { display: grid; gap: 10px; margin: 12px 0 0; padding-left: 18px; color: #526b92; font-size: .82rem; line-height: 1.4; }.data-list-section { margin-top: 18px; padding: 14px; border: 1px solid #e1e9f4; border-radius: 12px; background: #fbfdff; }.data-list-heading { display: flex; align-items: end; justify-content: space-between; gap: 12px; }.data-list-heading .muted { margin: 5px 0 0; font-size: .74rem; }.data-list-count { color: #52709e; font-size: .72rem; font-weight: 800; }.data-table-wrap { margin-top: 12px; overflow-x: auto; border: 1px solid #e1e9f4; border-radius: 9px; background: #fff; }.data-table { width: 100%; min-width: 680px; border-collapse: collapse; text-align: left; }.data-table th { padding: 9px 10px; background: #f1f6fd; color: #71809a; font-size: .68rem; font-weight: 800; }.data-table td { padding: 10px; border-top: 1px solid #edf1f6; color: #405979; font-size: .74rem; vertical-align: middle; }.data-table td strong, .data-table td small { display: block; }.data-table td strong { color: #284c87; }.data-table td small { margin-top: 3px; overflow-wrap: anywhere; color: #7a8aa2; font-size: .65rem; }.step-summary { display: block; max-width: 230px; overflow-wrap: anywhere; color: #607594; font-size: .7rem; line-height: 1.4; }.pagination-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 11px; color: #71809a; font-size: .72rem; }.pagination-bar > div { display: flex; gap: 7px; }.pagination-bar button { padding: 7px 10px; font-size: .7rem; }.data-list-empty { margin-top: 14px; padding: 14px; border: 1px dashed #dce5f2; border-radius: 10px; }
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

<style scoped>
.advanced-management { margin-top: 18px; border: 1px solid #e3e9f4; border-radius: 14px; background: #fbfdff; }
.advanced-management summary { padding: 13px 16px; color: #4d6486; cursor: pointer; font-size: .78rem; font-weight: 800; list-style: none; }
.advanced-management summary::-webkit-details-marker { display: none; }
.advanced-management summary::after { float: right; content: "+"; color: #7081bd; font-size: 1rem; }
.advanced-management[open] summary::after { content: "–"; }
.advanced-management-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; padding: 0 14px 14px; }
.advanced-config { margin-top: 14px; border: 1px solid #e4e9f3; border-radius: 12px; background: #fbfdff; }
.advanced-config summary { padding: 11px 13px; color: #546d92; cursor: pointer; font-size: .76rem; font-weight: 800; }
.advanced-config .field-hint { margin: 0 13px 12px; }
.runtime-choice { border-color: #d8e1f7; background: linear-gradient(145deg, #f6f9ff, #fff); }
.runtime-options { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 14px; }
.runtime-options button { display: grid; gap: 4px; justify-items: start; padding: 11px 12px; border: 1px solid #d9e2f1; border-radius: 10px; background: #fff; color: #466181; text-align: left; }
.runtime-options button.selected { border-color: #7a96e8; background: #eef4ff; box-shadow: 0 0 0 2px rgba(122, 150, 232, .12); }
.runtime-options button:disabled { cursor: not-allowed; opacity: .48; }
.runtime-options button strong { color: #315b97; font-size: .78rem; }
.runtime-options button small { color: #8190a5; font-size: .68rem; }
@media (max-width: 700px) { .advanced-management-grid, .runtime-options { grid-template-columns: 1fr; } }
</style>
