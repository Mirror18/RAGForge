<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ApiError, type ModelProfile, type ModelRoute, type PlatformRole, type PromptTemplate, type PromptVersion, type ProviderConnection, type RunSnapshot, type SpaceRole, apiFetch } from "./api";
import { formatDateTime } from "./format";

type ControlSection = "spaces" | "providers" | "models" | "prompts" | "runs";

const props = defineProps<{
  selectedSpaceId: string;
  currentRole: SpaceRole | PlatformRole | string;
  currentUserId: string;
  initialSection?: ControlSection;
  initialRunId?: string;
}>();

const section = ref<ControlSection>(props.initialSection ?? "providers");
const loading = ref(false);
const error = ref("");
const notice = ref("");
const providerConnections = ref<ProviderConnection[]>([]);
const modelProfiles = ref<ModelProfile[]>([]);
const modelRoutes = ref<ModelRoute[]>([]);
const promptTemplates = ref<PromptTemplate[]>([]);
const selectedPromptTemplateId = ref("");
const promptVersionDetails = ref<PromptVersion | null>(null);
const runId = ref(props.initialRunId ?? "");
const runSnapshot = ref<RunSnapshot | null>(null);

const providerForm = ref({ displayName: "Xiaomi MiMo 云端", providerType: "MIMO", egressClass: "CLOUD", endpoint: "https://api.xiaomimimo.com", credentialRef: "env:XIAOMI_API_KEY", status: "ACTIVE" });
const profileForm = ref({ providerConnectionId: "", purpose: "CHAT", modelName: "mimo-v2.5", capabilities: "CHAT,STREAMING,TOOLS,USAGE_REPORTING", contextWindow: 32768, maxOutputTokens: 2048, embeddingDimension: null as number | null, usageReporting: "PROVIDER_REPORTED", status: "PUBLISHED" });
const routeForm = ref({ purpose: "CHAT", egressClass: "CLOUD", failoverPolicy: "NONE", modelProfileId: "", status: "ACTIVE" });
const promptForm = ref({ name: "RAG Chat Prompt", purpose: "CHAT" });
const promptVersionForm = ref({
  messages: JSON.stringify([
    { role: "SYSTEM", content: "Use only the supplied evidence. Return ONLY valid JSON with this exact shape: {\"answer_text\":\"brief answer\",\"claims\":[{\"claim_text\":\"one supported claim\",\"citation_tokens\":[\"exact evidence UUIDv7 from the evidence id attribute\"]}]}. Do not use Markdown, code fences, extra keys, character offsets, or invented IDs. Every claim_text must be copied as an exact contiguous substring of answer_text, including punctuation and spacing. Every citation token must copy an exact evidence UUIDv7 from the supplied evidence block. If evidence is insufficient, return a short answer and make claim_text the exact matching substring from answer_text." },
    { role: "USER", content: "{{query}}" },
  ], null, 2),
  variableSchema: JSON.stringify({ type: "object", required: ["context", "question"] }, null, 2),
  outputContract: JSON.stringify({ type: "object", required: ["answer", "citations"] }, null, 2),
  changeDescription: "创建初始 Prompt 版本",
});

const canManage = computed(() => props.currentRole === "SPACE_ADMIN" || props.currentRole === "PLATFORM_ADMIN");
const sectionTitle = computed(() => ({ spaces: "空间与运行上下文", providers: "Provider 连接", models: "模型 Profile 与路由", prompts: "Prompt 模板", runs: "Run / Step 追踪" })[section.value]);
const sectionDescription = computed(() => ({
  spaces: "创建和切换空间；所有后续入口都在当前 spaceId 边界内工作。",
  providers: "登记本地或云端连接。云端出境不会由前端默认开启，最终权限由服务端裁决。",
  models: "创建不可变模型 Profile 和 route candidate；发布与 failover 策略由服务端校验。",
  prompts: "管理版本化 Prompt 模板。发布后不可变，问答入口只引用已发布版本。",
  runs: "按 Run ID 查看执行状态、Step、错误和序列，并在服务端允许时重试。",
})[section.value]);

function selectSection(value: string): void {
  if (["spaces", "providers", "models", "prompts", "runs"].includes(value)) {
    section.value = value as ControlSection;
  }
}

function path(suffix: string): string { return `/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}${suffix}`; }
const formatDate = formatDateTime;
function describeError(value: unknown, fallback: string): string {
  if (value instanceof Error && !(value instanceof ApiError)) return value.message;
  if (!(value instanceof ApiError)) return fallback;
  const permission = value.status === 403 ? "当前角色无权执行此操作。" : value.status === 404 ? "资源不存在或不属于当前空间。" : value.status === 409 ? "资源版本冲突，请刷新后重试。" : "请检查输入并稍后重试。";
  return `${value.problem?.code ? `${value.problem.code}：` : ""}${value.problem?.detail ?? value.message} ${permission}`;
}
function ensureSpace(): boolean {
  if (props.selectedSpaceId) return true;
  error.value = "请先在页面顶部选择当前空间。";
  return false;
}

async function loadSection(): Promise<void> {
  error.value = "";
  notice.value = "";
  if (!ensureSpace()) return;
  loading.value = true;
  try {
    if (section.value === "providers") {
      const response = await apiFetch<{ items: ProviderConnection[] }>(path("/provider-connections?limit=100"));
      providerConnections.value = response.items;
      if (!profileForm.value.providerConnectionId) profileForm.value.providerConnectionId = response.items[0]?.providerConnectionId ?? "";
    } else if (section.value === "models") {
      const [profiles, routes, connections] = await Promise.all([
        apiFetch<{ items: ModelProfile[] }>(path("/model-profiles?limit=100")),
        apiFetch<{ items: ModelRoute[] }>(path("/model-routes?limit=100")),
        apiFetch<{ items: ProviderConnection[] }>(path("/provider-connections?limit=100")),
      ]);
      modelProfiles.value = profiles.items;
      modelRoutes.value = routes.items;
      providerConnections.value = connections.items;
      if (!profileForm.value.providerConnectionId) profileForm.value.providerConnectionId = providerConnections.value[0]?.providerConnectionId ?? "";
      if (!routeForm.value.modelProfileId) routeForm.value.modelProfileId = profiles.items[0]?.modelProfileId ?? "";
    } else if (section.value === "prompts") {
      const response = await apiFetch<{ items: PromptTemplate[] }>(path("/prompt-templates"));
      promptTemplates.value = response.items;
      if (!response.items.some((item) => item.promptTemplateId === selectedPromptTemplateId.value)) {
        selectedPromptTemplateId.value = response.items.find((item) => item.purpose === "CHAT")?.promptTemplateId
          ?? response.items[0]?.promptTemplateId ?? "";
        promptVersionDetails.value = null;
      }
    }
  } catch (value) {
    error.value = describeError(value, "功能中心数据加载失败。");
  } finally {
    loading.value = false;
  }
}

async function createProvider(): Promise<void> {
  if (!ensureSpace() || !canManage.value) return;
  loading.value = true; error.value = ""; notice.value = "";
  try {
    await apiFetch<ProviderConnection>(path("/provider-connections"), { method: "POST", body: providerForm.value });
    notice.value = "Provider connection 已创建。";
    await loadSection();
  } catch (value) { error.value = describeError(value, "Provider connection 创建失败。"); }
  finally { loading.value = false; }
}

async function createProfile(): Promise<void> {
  if (!ensureSpace() || !canManage.value) return;
  if (!profileForm.value.providerConnectionId) { error.value = "请先选择 Provider connection。"; return; }
  loading.value = true; error.value = ""; notice.value = "";
  try {
    await apiFetch<ModelProfile>(path("/model-profiles"), { method: "POST", body: { ...profileForm.value, capabilities: profileForm.value.capabilities.split(",").map((item) => item.trim()).filter(Boolean) } });
    notice.value = "Model profile 已创建。";
    await loadSection();
  } catch (value) { error.value = describeError(value, "Model profile 创建失败。"); }
  finally { loading.value = false; }
}

async function createRoute(): Promise<void> {
  if (!ensureSpace() || !canManage.value) return;
  if (!routeForm.value.modelProfileId) { error.value = "请先选择 route candidate profile。"; return; }
  loading.value = true; error.value = ""; notice.value = "";
  try {
    await apiFetch<ModelRoute>(path("/model-routes"), { method: "POST", body: { ...routeForm.value, candidates: [{ modelProfileId: routeForm.value.modelProfileId, priority: 1, egressClass: routeForm.value.egressClass }] } });
    notice.value = "Model route 已创建。";
    await loadSection();
  } catch (value) { error.value = describeError(value, "Model route 创建失败。"); }
  finally { loading.value = false; }
}

async function createPrompt(): Promise<void> {
  if (!ensureSpace() || !canManage.value) return;
  loading.value = true; error.value = ""; notice.value = "";
  try {
    const created = await apiFetch<PromptTemplate>(path("/prompt-templates"), { method: "POST", body: promptForm.value });
    selectedPromptTemplateId.value = created.promptTemplateId;
    promptVersionDetails.value = null;
    notice.value = "Prompt template 已创建；请继续为它创建版本。";
    await loadSection();
  } catch (value) { error.value = describeError(value, "Prompt template 创建失败。"); }
  finally { loading.value = false; }
}

function parseJsonObject(value: string, field: string): Record<string, unknown> {
  let parsed: unknown;
  try { parsed = JSON.parse(value); } catch { throw new Error(`${field} 必须是有效 JSON。`); }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) throw new Error(`${field} 必须是 JSON 对象。`);
  return parsed as Record<string, unknown>;
}

function parsePromptMessages(value: string): Array<{ role: "SYSTEM" | "USER" | "ASSISTANT" | "TOOL"; content: string }> {
  let parsed: unknown;
  try { parsed = JSON.parse(value); } catch { throw new Error("messages 必须是有效 JSON 数组。"); }
  if (!Array.isArray(parsed) || parsed.length === 0) throw new Error("messages 至少需要一条消息。");
  const roles = new Set(["SYSTEM", "USER", "ASSISTANT", "TOOL"]);
  if (parsed.some((item) => typeof item !== "object" || item === null || Array.isArray(item)
    || typeof (item as Record<string, unknown>).role !== "string"
    || !roles.has((item as Record<string, unknown>).role as string)
    || typeof (item as Record<string, unknown>).content !== "string"
    || !(item as Record<string, unknown>).content)) {
    throw new Error("messages 必须包含合法 role 和非空 content。");
  }
  return parsed as Array<{ role: "SYSTEM" | "USER" | "ASSISTANT" | "TOOL"; content: string }>;
}

async function createPromptVersion(): Promise<void> {
  if (!ensureSpace() || !canManage.value) return;
  if (!selectedPromptTemplateId.value) { error.value = "请先选择 Prompt Template。"; return; }
  loading.value = true; error.value = ""; notice.value = "";
  try {
    const version = await apiFetch<PromptVersion>(path(`/prompt-templates/${encodeURIComponent(selectedPromptTemplateId.value)}/versions`), {
      method: "POST",
      body: {
        messages: parsePromptMessages(promptVersionForm.value.messages),
        variableSchema: parseJsonObject(promptVersionForm.value.variableSchema, "variableSchema"),
        outputContract: parseJsonObject(promptVersionForm.value.outputContract, "outputContract"),
        changeDescription: promptVersionForm.value.changeDescription.trim(),
      },
    });
    promptVersionDetails.value = version;
    notice.value = `Prompt version v${version.version} 已创建为 DRAFT，请确认后发布。`;
  } catch (value) { error.value = describeError(value, "Prompt version 创建失败。"); }
  finally { loading.value = false; }
}

async function publishPromptVersion(): Promise<void> {
  if (!ensureSpace() || !canManage.value || !promptVersionDetails.value) return;
  if (promptVersionDetails.value.state !== "DRAFT") return;
  loading.value = true; error.value = ""; notice.value = "";
  try {
    const version = promptVersionDetails.value;
    promptVersionDetails.value = await apiFetch<PromptVersion>(path(`/prompt-templates/${encodeURIComponent(version.promptTemplateId)}/versions/${version.version}/publish`), { method: "POST" });
    notice.value = `Prompt version v${version.version} 已由服务端发布，发布后不可变。`;
    await loadSection();
  } catch (value) { error.value = describeError(value, "Prompt version 发布失败。"); }
  finally { loading.value = false; }
}

async function viewPromptVersion(template: PromptTemplate): Promise<void> {
  if (!template.currentVersion) {
    promptVersionDetails.value = null;
    error.value = "当前模板还没有可查看的已发布版本。";
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    promptVersionDetails.value = await apiFetch<PromptVersion>(path(`/prompt-templates/${encodeURIComponent(template.promptTemplateId)}/versions/${template.currentVersion}`));
  } catch (value) {
    error.value = describeError(value, "Prompt version 加载失败。");
  } finally {
    loading.value = false;
  }
}

async function initializeLocalRag(): Promise<void> {
  if (!ensureSpace() || !canManage.value) return;
  loading.value = true; error.value = ""; notice.value = "";
  try {
    const providerPage = await apiFetch<{ items: ProviderConnection[] }>(path("/provider-connections?limit=100"));
    let localProvider = providerPage.items.find((item) => item.providerType === "OLLAMA" && item.egressClass === "LOCAL" && item.status === "ACTIVE");
    if (!localProvider) {
      localProvider = await apiFetch<ProviderConnection>(path("/provider-connections"), {
        method: "POST",
        body: { displayName: "本地 Ollama", providerType: "OLLAMA", egressClass: "LOCAL", endpoint: "http://127.0.0.1:11434", credentialRef: "local-ollama", status: "ACTIVE" },
      });
    }

    const profilePage = await apiFetch<{ items: ModelProfile[] }>(path("/model-profiles?limit=100"));
    const profileDefinitions = [
      { purpose: "CHAT", modelName: "qwen3.5:9b", capabilities: ["CHAT", "STREAMING", "TOOLS", "USAGE_REPORTING"], embeddingDimension: null },
      { purpose: "EMBEDDING", modelName: "nomic-embed-text:latest", capabilities: ["EMBEDDING"], embeddingDimension: 768 },
      { purpose: "RERANK", modelName: "qwen3.5:9b", capabilities: ["RERANK"], embeddingDimension: null },
    ] as const;
    const profiles: Record<string, ModelProfile> = {};
    for (const definition of profileDefinitions) {
      const existing = profilePage.items.find((item) => item.providerConnectionId === localProvider.providerConnectionId && item.purpose === definition.purpose && item.modelName === definition.modelName && item.status === "PUBLISHED" && item.embeddingDimension === definition.embeddingDimension);
      profiles[definition.purpose] = existing ?? await apiFetch<ModelProfile>(path("/model-profiles"), {
        method: "POST",
        body: { providerConnectionId: localProvider.providerConnectionId, ...definition, contextWindow: 8192, maxOutputTokens: 1024, usageReporting: definition.purpose === "CHAT" ? "PROVIDER_REPORTED" : "LOCAL_ESTIMATE", status: "PUBLISHED" },
      });
    }

    const routePage = await apiFetch<{ items: ModelRoute[] }>(path("/model-routes?limit=100"));
    const routes: Record<string, ModelRoute> = {};
    for (const purpose of ["CHAT", "EMBEDDING", "RERANK"] as const) {
      const profile = profiles[purpose];
      const existing = routePage.items.find((item) => item.purpose === purpose && item.status === "ACTIVE" && item.candidates.some((candidate) => candidate.modelProfileId === profile.modelProfileId));
      routes[purpose] = existing ?? await apiFetch<ModelRoute>(path("/model-routes"), {
        method: "POST",
        body: { purpose, egressClass: "LOCAL", failoverPolicy: "NONE", candidates: [{ modelProfileId: profile.modelProfileId, priority: 1, egressClass: "LOCAL" }], status: "ACTIVE" },
      });
    }

    const promptPage = await apiFetch<{ items: PromptTemplate[] }>(path("/prompt-templates"));
    let prompt = promptPage.items.find((item) => item.purpose === "CHAT");
    if (!prompt) prompt = await apiFetch<PromptTemplate>(path("/prompt-templates"), { method: "POST", body: { name: "Local RAG Answer", purpose: "CHAT" } });
    let promptVersion: PromptVersion | null = prompt.currentVersion ? await apiFetch<PromptVersion>(path(`/prompt-templates/${prompt.promptTemplateId}/versions/${prompt.currentVersion}`)) : null;
    const structuredPromptReady = promptVersion?.state === "PUBLISHED"
      && promptVersion.messages.some((message) => message.content.includes("answer_text")
        && message.content.includes("citation_tokens")
        && message.content.includes("exact contiguous substring of answer_text"));
    if (!structuredPromptReady) {
      promptVersion = await apiFetch<PromptVersion>(path(`/prompt-templates/${prompt.promptTemplateId}/versions`), {
        method: "POST",
        body: {
          messages: [
            { role: "SYSTEM", content: "Use only the supplied evidence. Return ONLY valid JSON with this exact shape: {\"answer_text\":\"brief answer\",\"claims\":[{\"claim_text\":\"one supported claim\",\"citation_tokens\":[\"exact evidence UUIDv7 from the evidence id attribute\"]}]}. Do not use Markdown, code fences, extra keys, character offsets, or invented IDs. Every claim_text must be copied as an exact contiguous substring of answer_text, including punctuation and spacing. Every citation token must copy an exact evidence UUIDv7 from the supplied evidence block. If evidence is insufficient, return a short answer and make claim_text the exact matching substring from answer_text." },
            { role: "USER", content: "{{query}}" },
          ],
          variableSchema: { type: "object", required: ["context", "question"] },
          outputContract: { type: "object", required: ["answer", "citations"] },
          changeDescription: "初始化本地 Ollama RAG 闭环 Prompt",
        },
      });
      promptVersion = await apiFetch<PromptVersion>(path(`/prompt-templates/${prompt.promptTemplateId}/versions/${promptVersion.version}/publish`), { method: "POST" });
    }

    let binding: { version: number } | null = null;
    try { binding = await apiFetch<{ version: number }>(path("/space-bindings")); } catch (value) { if (!(value instanceof ApiError) || value.status !== 404) throw value; }
    const version = binding?.version ?? 1;
    await apiFetch(path("/space-bindings"), {
      method: "PUT",
      headers: { "If-Match": `"${binding?.version ?? 0}"` },
      body: { version, chatRouteId: routes.CHAT.modelRouteId, embeddingRouteId: routes.EMBEDDING.modelRouteId, rerankRouteId: routes.RERANK.modelRouteId, promptVersionId: promptVersion.promptVersionId, cloudEgressEnabled: false, cloudEgressAuthorization: null },
    });
    notice.value = "本地 Ollama RAG 配置已通过服务端 API 完成：Provider、Profile、Route、Prompt 和空间绑定均已就绪。";
    section.value = "providers";
    await loadSection();
  } catch (value) { error.value = describeError(value, "本地 Ollama RAG 初始化失败，请检查服务端错误并重试。"); }
  finally { loading.value = false; }
}

async function initializeMimoRag(): Promise<void> {
  if (!ensureSpace() || !canManage.value || !props.currentUserId) return;
  loading.value = true; error.value = ""; notice.value = "";
  try {
    await initializeLocalRag();
    const [providersPage, profilesPage, routesPage, binding] = await Promise.all([
      apiFetch<{ items: ProviderConnection[] }>(path("/provider-connections?limit=100")),
      apiFetch<{ items: ModelProfile[] }>(path("/model-profiles?limit=100")),
      apiFetch<{ items: ModelRoute[] }>(path("/model-routes?limit=100")),
      apiFetch<{ version: number; embeddingRouteId: string; rerankRouteId: string; promptVersionId: string }>(path("/space-bindings")),
    ]);
    let mimoProvider = providersPage.items.find((item) => item.providerType === "MIMO" && item.egressClass === "CLOUD" && item.status === "ACTIVE");
    if (!mimoProvider) {
      mimoProvider = await apiFetch<ProviderConnection>(path("/provider-connections"), {
        method: "POST",
        body: { displayName: "Xiaomi MiMo 云端", providerType: "MIMO", egressClass: "CLOUD", endpoint: "https://api.xiaomimimo.com", credentialRef: "env:XIAOMI_API_KEY", status: "ACTIVE" },
      });
    }
    const mimoProfile = profilesPage.items.find((item) => item.providerConnectionId === mimoProvider?.providerConnectionId && item.purpose === "CHAT" && item.modelName === "mimo-v2.5" && item.status === "PUBLISHED") ?? await apiFetch<ModelProfile>(path("/model-profiles"), {
      method: "POST",
      body: { providerConnectionId: mimoProvider.providerConnectionId, purpose: "CHAT", modelName: "mimo-v2.5", capabilities: ["CHAT", "USAGE_REPORTING"], contextWindow: 32768, maxOutputTokens: 2048, embeddingDimension: null, usageReporting: "PROVIDER_REPORTED", status: "PUBLISHED" },
    });
    const mimoRoute = routesPage.items.find((item) => item.purpose === "CHAT" && item.egressClass === "CLOUD" && item.status === "ACTIVE" && item.candidates.some((candidate) => candidate.modelProfileId === mimoProfile.modelProfileId)) ?? await apiFetch<ModelRoute>(path("/model-routes"), {
      method: "POST",
      body: { purpose: "CHAT", egressClass: "CLOUD", failoverPolicy: "NONE", candidates: [{ modelProfileId: mimoProfile.modelProfileId, priority: 1, egressClass: "CLOUD" }], status: "ACTIVE" },
    });
    const approvedAt = new Date();
    const expiresAt = new Date(approvedAt.getTime() + 24 * 60 * 60 * 1000);
    await apiFetch(path("/space-bindings"), {
      method: "PUT",
      headers: { "If-Match": `"${binding.version}"` },
      body: {
        version: binding.version,
        chatRouteId: mimoRoute.modelRouteId,
        embeddingRouteId: binding.embeddingRouteId,
        rerankRouteId: binding.rerankRouteId,
        promptVersionId: binding.promptVersionId,
        cloudEgressEnabled: true,
        cloudEgressAuthorization: { approvalId: crypto.randomUUID(), approvedBy: props.currentUserId, approvedAt: approvedAt.toISOString(), expiresAt: expiresAt.toISOString(), scope: "CHAT" },
      },
    });
    notice.value = "MiMo 云端 Chat 已通过显式授权接入；Embedding/Rerank 仍使用本地 Ollama，问答页可切换本地或 MiMo。";
    section.value = "providers";
    await loadSection();
  } catch (value) { error.value = describeError(value, "MiMo 云端 RAG 初始化失败，请检查本地环境变量和空间授权。"); }
  finally { loading.value = false; }
}

async function loadRun(): Promise<void> {
  if (!ensureSpace() || !runId.value.trim()) { error.value = "请输入当前空间内的 Run ID。"; return; }
  loading.value = true; error.value = ""; notice.value = "";
  try { runSnapshot.value = await apiFetch<RunSnapshot>(path(`/runs/${encodeURIComponent(runId.value.trim())}`)); }
  catch (value) { runSnapshot.value = null; error.value = describeError(value, "Run 加载失败。"); }
  finally { loading.value = false; }
}
async function retryRun(): Promise<void> {
  if (!ensureSpace() || !runSnapshot.value) return;
  loading.value = true; error.value = ""; notice.value = "";
  try {
    await apiFetch(path(`/runs/${encodeURIComponent(runSnapshot.value.runId)}/retry`), { method: "POST" });
    notice.value = "重试已提交，重新加载 Run 查看状态。";
    await loadRun();
  } catch (value) { error.value = describeError(value, "Run 重试失败。"); }
  finally { loading.value = false; }
}

watch(() => props.initialSection, (value) => { if (value) section.value = value; });
watch(() => props.initialRunId, (value) => {
  if (!value || value === runId.value) return;
  runId.value = value;
  if (section.value === "runs") void loadRun();
});
watch(() => [props.selectedSpaceId, section.value], () => {
  if (section.value === "runs" && runId.value) void loadRun();
  else void loadSection();
}, { immediate: true });
</script>

<template>
  <section class="view-section control-center" aria-labelledby="control-center-heading">
    <div class="section-heading">
      <div><p class="eyebrow">04 · Platform operations</p><h2 id="control-center-heading">{{ sectionTitle }}</h2><p>{{ sectionDescription }}</p></div>
      <div class="read-only-note" :class="{ warning: !canManage }">{{ canManage ? "SPACE_ADMIN：可管理配置" : "当前角色：只读入口" }}</div>
    </div>
    <nav class="control-nav" aria-label="配置与运维入口">
      <button v-for="item in [{ key: 'spaces', label: '空间与健康' }, { key: 'providers', label: 'Provider 连接' }, { key: 'models', label: '模型与路由' }, { key: 'prompts', label: 'Prompt 模板' }, { key: 'runs', label: 'Run 追踪' }]" :key="item.key" type="button" :class="{ active: section === item.key }" @click="selectSection(item.key)">{{ item.label }}</button>
    </nav>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p>

    <div v-if="section === 'spaces'" class="entry-grid">
      <article class="card entry-card"><span class="card-label">当前空间</span><strong>{{ selectedSpaceId || "未选择" }}</strong><p>空间切换在页面顶部完成；所有内容 API 都必须使用当前 spaceId。</p><button type="button" class="secondary-button" @click="section = 'runs'">查看 Run 追踪</button></article>
      <article class="card entry-card"><span class="card-label">服务健康</span><strong>Server / API</strong><p>本地开发可通过健康端点检查 server 与基础设施状态。</p><a class="link-button" href="/actuator/health" target="_blank" rel="noreferrer">打开健康检查</a></article>
      <article class="card entry-card"><span class="card-label">本地 RAG 初始化</span><strong>一键准备 Ollama 闭环</strong><p>通过当前空间 API 创建或复用本地 Provider、三类模型路由、已发布 Prompt，并写入空间绑定；云端出境保持关闭。</p><button type="button" :disabled="loading || !canManage" @click="initializeLocalRag">{{ loading ? "初始化中…" : "初始化本地 Ollama RAG" }}</button></article>
      <article class="card entry-card"><span class="card-label">MiMo 云端 Chat</span><strong>显式授权后接入 Xiaomi MiMo</strong><p>只为 Chat 开启 24 小时、当前用户审批的云端授权；Embedding/Rerank 保持本地，业务闭环中可切换模型路线。</p><button type="button" :disabled="loading || !canManage" @click="initializeMimoRag">{{ loading ? "初始化中…" : "初始化 MiMo 云端 RAG" }}</button></article>
      <article class="card entry-card"><span class="card-label">审计与保留</span><strong>Run / Step 可追踪</strong><p>业务闭环的执行状态、步骤、错误 correlationId 和重试入口均通过当前空间的 Run 追踪页查看。</p><button type="button" class="secondary-button" @click="section = 'runs'">打开 Run 追踪</button></article>
    </div>

    <div v-else-if="section === 'providers'" class="control-layout">
      <div class="card list-card"><div class="card-title"><h3>已登记连接</h3><button type="button" class="quiet-button" :disabled="loading" @click="loadSection">刷新</button></div><p v-if="!providerConnections.length" class="empty-state">当前空间还没有 Provider connection。</p><article v-for="item in providerConnections" :key="item.providerConnectionId" class="list-row"><div><strong>{{ item.providerType }} · {{ item.endpoint }}</strong><span>{{ item.providerConnectionId }} · v{{ item.version }}</span></div><span class="state-pill" :class="item.status.toLowerCase()">{{ item.status }} · {{ item.egressClass }}</span></article></div>
      <form class="card form-card" @submit.prevent="createProvider"><h3>登记 Provider connection</h3><p class="muted">凭据只保存 credentialRef，不在前端输入或展示 Secret。云端连接需由服务端策略明确允许。</p><div class="form-grid"><div class="field"><label for="provider-display-name">显示名称</label><input id="provider-display-name" v-model="providerForm.displayName" required /></div><div class="field"><label for="provider-type">类型</label><select id="provider-type" v-model="providerForm.providerType"><option>OLLAMA</option><option>OPENAI_COMPATIBLE</option><option>MIMO</option><option>AI_RUNTIME</option></select></div><div class="field"><label for="provider-egress">出境等级</label><select id="provider-egress" v-model="providerForm.egressClass"><option>LOCAL</option><option>CLOUD</option></select></div></div><div class="form-grid"><div class="field"><label for="provider-endpoint">Endpoint</label><input id="provider-endpoint" v-model="providerForm.endpoint" type="url" required /></div><div class="field"><label for="provider-credential-ref">credentialRef</label><input id="provider-credential-ref" v-model="providerForm.credentialRef" required /></div><div class="field"><label for="provider-status">状态</label><select id="provider-status" v-model="providerForm.status"><option>DRAFT</option><option>ACTIVE</option><option>DISABLED</option></select></div></div><button type="submit" :disabled="loading || !canManage">创建连接</button><p v-if="!canManage" class="permission-hint">当前角色只能查看连接，创建由 API 继续强制拒绝。</p></form>
    </div>

    <div v-else-if="section === 'models'" class="control-layout">
      <div class="stack"><article class="card list-card"><div class="card-title"><h3>Model Profiles</h3><span class="tag">{{ modelProfiles.length }} 个</span></div><p v-if="!modelProfiles.length" class="empty-state">暂无 Model Profile。</p><div v-for="item in modelProfiles" :key="item.modelProfileId" class="list-row"><div><strong>{{ item.purpose }} · {{ item.capabilities.join(", ") }}</strong><span>{{ item.modelProfileId }} · v{{ item.version }} · {{ item.providerConnectionId }}</span></div><span class="state-pill">{{ item.status }}</span></div></article><article class="card list-card"><div class="card-title"><h3>Model Routes</h3><span class="tag">{{ modelRoutes.length }} 个</span></div><p v-if="!modelRoutes.length" class="empty-state">暂无 Model Route。</p><div v-for="item in modelRoutes" :key="item.modelRouteId" class="list-row"><div><strong>{{ item.purpose }} · {{ item.egressClass }} · {{ item.failoverPolicy }}</strong><span>{{ item.modelRouteId }} · v{{ item.version }} · candidates {{ item.candidates.length }}</span></div><span class="state-pill">{{ item.status }}</span></div></article></div>
      <div class="stack"><form class="card form-card" @submit.prevent="createProfile"><h3>创建 Model Profile</h3><div class="form-grid"><div class="field wide"><label for="profile-provider">Provider connection</label><select id="profile-provider" v-model="profileForm.providerConnectionId" required><option value="">选择连接</option><option v-for="item in providerConnections" :key="item.providerConnectionId" :value="item.providerConnectionId">{{ item.providerType }} · {{ item.endpoint }}</option></select></div><div class="field"><label for="profile-purpose">用途</label><select id="profile-purpose" v-model="profileForm.purpose"><option>CHAT</option><option>EMBEDDING</option><option>RERANK</option></select></div><div class="field"><label for="profile-model">模型名</label><input id="profile-model" v-model="profileForm.modelName" required /></div></div><div class="form-grid"><div class="field wide"><label for="profile-capabilities">能力（逗号分隔）</label><input id="profile-capabilities" v-model="profileForm.capabilities" required /></div><div class="field"><label for="profile-context">Context window</label><input id="profile-context" v-model.number="profileForm.contextWindow" type="number" min="1" required /></div><div class="field"><label for="profile-output">Max output</label><input id="profile-output" v-model.number="profileForm.maxOutputTokens" type="number" min="1" required /></div><div class="field"><label for="profile-embedding-dimension">Embedding dimension</label><input id="profile-embedding-dimension" v-model.number="profileForm.embeddingDimension" type="number" min="1" max="4096" placeholder="仅 EMBEDDING 填写" /></div><div class="field"><label for="profile-status">创建状态</label><select id="profile-status" v-model="profileForm.status"><option>PUBLISHED</option><option>DRAFT</option><option>DISABLED</option></select><p class="field-hint">选择 PUBLISHED 后可直接用于 ACTIVE route；服务端仍会校验 Provider、能力和空间权限。</p></div></div><button type="submit" :disabled="loading || !canManage">创建 Profile</button></form><form class="card form-card" @submit.prevent="createRoute"><h3>创建 Model Route</h3><div class="form-grid"><div class="field"><label for="route-purpose">用途</label><select id="route-purpose" v-model="routeForm.purpose"><option>CHAT</option><option>EMBEDDING</option><option>RERANK</option></select></div><div class="field"><label for="route-egress">出境等级</label><select id="route-egress" v-model="routeForm.egressClass"><option>LOCAL</option><option>CLOUD</option></select></div><div class="field"><label for="route-failover">Failover</label><select id="route-failover" v-model="routeForm.failoverPolicy"><option>NONE</option><option>SAME_EGRESS_ONLY</option></select></div><div class="field"><label for="route-status">创建状态</label><select id="route-status" v-model="routeForm.status"><option>ACTIVE</option><option>DRAFT</option><option>DISABLED</option></select></div></div><div class="field"><label for="route-profile">Candidate Profile</label><select id="route-profile" v-model="routeForm.modelProfileId" required><option value="">选择 profile</option><option v-for="item in modelProfiles.filter((candidate) => candidate.purpose === routeForm.purpose && candidate.status === 'PUBLISHED' && ((routeForm.egressClass === 'LOCAL' && providerConnections.some((provider) => provider.providerConnectionId === candidate.providerConnectionId && provider.egressClass === 'LOCAL')) || (routeForm.egressClass === 'CLOUD' && providerConnections.some((provider) => provider.providerConnectionId === candidate.providerConnectionId && provider.egressClass === 'CLOUD'))))" :key="item.modelProfileId" :value="item.modelProfileId">{{ item.purpose }} · {{ item.modelName }} · {{ item.modelProfileId }}</option></select><p class="field-hint">候选已按用途、发布状态和出境等级过滤，避免提交服务端必然拒绝的组合。</p></div><button type="submit" :disabled="loading || !canManage">创建 Route</button></form></div>
    </div>

    <div v-else-if="section === 'prompts'" class="control-layout"><div class="card list-card"><div class="card-title"><h3>Prompt Templates</h3><button type="button" class="quiet-button" :disabled="loading" @click="loadSection">刷新</button></div><p v-if="!promptTemplates.length" class="empty-state">当前空间还没有 Prompt 模板。</p><article v-for="item in promptTemplates" :key="item.promptTemplateId" class="list-row"><div><strong>{{ item.name }} · {{ item.purpose }}</strong><span>{{ item.promptTemplateId }} · 当前版本 {{ item.currentVersion ?? "未创建" }}</span></div><div class="list-row-actions"><span class="tag">版本化</span><button type="button" class="quiet-button" @click="selectedPromptTemplateId = item.promptTemplateId; promptVersionDetails = null">选为版本模板</button><button v-if="item.currentVersion" type="button" class="quiet-button" @click="viewPromptVersion(item)">查看版本</button></div></article><article v-if="promptVersionDetails" class="prompt-version-card"><div class="card-title"><h3>Prompt version v{{ promptVersionDetails.version }}</h3><span class="state-pill">{{ promptVersionDetails.state }}</span></div><p>contentHash：<code>{{ promptVersionDetails.contentHash }}</code></p><p>messages：{{ promptVersionDetails.messages.map((message) => `${message.role}: ${message.content}`).join(" · ") }}</p><p>variableSchema：<code>{{ JSON.stringify(promptVersionDetails.variableSchema) }}</code></p><p>outputContract：<code>{{ JSON.stringify(promptVersionDetails.outputContract) }}</code></p><button v-if="promptVersionDetails.state === 'DRAFT'" type="button" @click="publishPromptVersion">发布此版本</button></article></div><div class="stack"><form class="card form-card" @submit.prevent="createPrompt"><h3>创建 Prompt Template</h3><p class="muted">模板创建后自动成为版本表单的选择项；后续版本使用服务端生成的版本号。</p><div class="field"><label for="prompt-name">名称</label><input id="prompt-name" v-model="promptForm.name" required /></div><div class="field"><label for="prompt-purpose">用途</label><select id="prompt-purpose" v-model="promptForm.purpose"><option>CHAT</option><option>EMBEDDING</option><option>RERANK</option></select></div><button type="submit" :disabled="loading || !canManage">创建模板</button></form><form class="card form-card" @submit.prevent="createPromptVersion"><h3>创建 Prompt Version</h3><p class="muted">模板、版本号和 contentHash 均来自服务端；这里只填写消息与契约内容，发布后不可变。</p><div class="field"><label for="prompt-version-template">Prompt Template</label><select id="prompt-version-template" v-model="selectedPromptTemplateId" required><option value="">选择模板</option><option v-for="item in promptTemplates" :key="item.promptTemplateId" :value="item.promptTemplateId">{{ item.name }} · {{ item.purpose }} · {{ item.currentVersion ? `当前 v${item.currentVersion}` : "未发布" }}</option></select></div><div class="field"><label for="prompt-messages">messages（JSON 数组）</label><textarea id="prompt-messages" v-model="promptVersionForm.messages" rows="7" required></textarea></div><div class="field"><label for="prompt-variables">variableSchema（JSON 对象）</label><textarea id="prompt-variables" v-model="promptVersionForm.variableSchema" rows="4" required></textarea></div><div class="field"><label for="prompt-contract">outputContract（JSON 对象）</label><textarea id="prompt-contract" v-model="promptVersionForm.outputContract" rows="4" required></textarea></div><div class="field"><label for="prompt-change-description">变更说明</label><input id="prompt-change-description" v-model="promptVersionForm.changeDescription" required /></div><button type="submit" :disabled="loading || !canManage || !selectedPromptTemplateId">创建 DRAFT 版本</button></form></div></div>

    <div v-else class="run-center"><form class="card lookup-form" @submit.prevent="loadRun"><div class="field"><label for="run-id">Run ID</label><input id="run-id" v-model="runId" autocomplete="off" placeholder="UUIDv7" required /></div><button type="submit" :disabled="loading">{{ loading ? "读取中…" : "读取 Run" }}</button></form><article v-if="runSnapshot" class="card run-card"><div class="card-title"><div><span class="card-label">Run</span><code>{{ runSnapshot.runId }}</code></div><span class="state-pill">{{ runSnapshot.status }}</span></div><dl class="details"><dt>correlationId</dt><dd><code>{{ runSnapshot.correlationId ?? runSnapshot.error?.correlationId ?? "—" }}</code></dd><dt>modelRouteId</dt><dd><code>{{ runSnapshot.modelRouteId }}</code></dd><dt>promptVersionId</dt><dd><code>{{ runSnapshot.promptVersionId }}</code></dd><dt>created / finished</dt><dd>{{ formatDate(runSnapshot.createdAt) }} / {{ formatDate(runSnapshot.finishedAt) }}</dd><dt>last sequence</dt><dd>{{ runSnapshot.lastSequence }}</dd></dl><p v-if="runSnapshot.error" class="alert error">{{ runSnapshot.error.message }}</p><div class="button-row"><button type="button" :disabled="loading || !runSnapshot.error?.retryable || !canManage" @click="retryRun">重试 Run</button><span class="muted">重试仍由服务端权限、状态和幂等规则裁决。</span></div><h3 class="steps-heading">Steps</h3><div v-for="step in runSnapshot.steps" :key="step.stepId" class="list-row"><div><strong>#{{ step.sequence }} · {{ step.type }}</strong><span>{{ step.stepId }} · attempt {{ step.attempt }} · {{ formatDate(step.createdAt) }}</span></div><span class="state-pill">{{ step.status }}</span></div></article><div v-else class="empty-state card"><strong>尚未读取 Run</strong><span>输入当前空间内的 Run ID；不会跨空间查找。</span></div></div>
  </section>
</template>

<style scoped>
.control-center { margin-top: 20px; }
.control-nav { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 0 18px; }
.control-nav button { border: 1px solid #cbd7e8; background: #fff; color: #345582; }
.control-nav button.active { border-color: #173b74; background: #173b74; color: #fff; }
.control-layout { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(320px, .9fr); gap: 16px; }
.stack { display: grid; gap: 16px; }
.list-card, .form-card { min-width: 0; }
.list-row { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 10px; padding: 12px; border: 1px solid #e3eaf4; border-radius: 10px; background: #f8fbff; }
.list-row strong, .list-row span { display: block; overflow-wrap: anywhere; }
.list-row span { margin-top: 4px; color: #71809a; font-size: .78rem; }
.list-row-actions { display: flex; align-items: center; gap: 8px; }
.prompt-version-card { margin-top: 14px; padding: 14px; border: 1px solid #c8d8ef; border-radius: 10px; background: #f5f9ff; }
.prompt-version-card p { margin: 8px 0 0; color: #536988; font-size: .8rem; line-height: 1.5; overflow-wrap: anywhere; }
.entry-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.entry-card { min-height: 180px; }
.entry-card strong { display: block; margin: 7px 0 10px; color: #213d6c; overflow-wrap: anywhere; }
.entry-card p { color: #687893; line-height: 1.6; }
.disabled-entry { background: #f7f9fc; }
.disabled-entry strong { color: #71809a; }
.link-button { display: inline-block; padding: 10px 13px; border-radius: 9px; background: #e7eef9; color: #28518f; font-weight: 700; text-decoration: none; }
.run-center { display: grid; gap: 16px; }
.run-card { min-width: 0; }
.steps-heading { margin-top: 24px; }
.field.wide { grid-column: span 2; }
@media (max-width: 850px) { .control-layout, .entry-grid { grid-template-columns: 1fr; } .field.wide { grid-column: auto; } .list-row { align-items: flex-start; flex-direction: column; } }
</style>
