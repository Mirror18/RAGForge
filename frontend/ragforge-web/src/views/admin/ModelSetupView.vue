<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ApiError, createUuidV7, listAllCursorPages, type ModelProfile, type ModelRoute, type PlatformRole, type PromptTemplate, type PromptVersion, type ProviderConnection, type ProviderConnectionTestResult, type SpaceBinding, type SpaceRole, apiFetch } from "../../api";
import { cloudEgressPayload, spaceBindingWrite } from "../../space-enable";
import { egressLabel, providerLabel, purposeLabel, stateLabel } from "../../i18n";

type ModelSection = "providers" | "prompts";
type Purpose = "CHAT" | "EMBEDDING" | "RERANK";

const props = defineProps<{
  selectedSpaceId: string;
  currentRole: SpaceRole | PlatformRole | string;
  platformRole: PlatformRole;
  currentUserId: string;
  initialSection?: string;
}>();
const emit = defineEmits<{ "select-section": [section: ModelSection] }>();

const section = ref<ModelSection>(props.initialSection === "prompts" ? "prompts" : "providers");
const loading = ref(false);
const error = ref("");
const notice = ref("");
const showAdvanced = ref(false);
const providerConnections = ref<ProviderConnection[]>([]);
const modelProfiles = ref<ModelProfile[]>([]);
const modelRoutes = ref<ModelRoute[]>([]);
const promptTemplates = ref<PromptTemplate[]>([]);
const selectedPromptTemplateId = ref("");
const promptVersionDetails = ref<PromptVersion | null>(null);
const spaceBinding = ref<SpaceBinding | null>(null);
const lastProviderTest = ref<ProviderConnectionTestResult | null>(null);
const confirmEgress = ref(false);
const providerForm = ref({ displayName: "Xiaomi MiMo 云端", providerType: "MIMO", endpoint: "https://api.xiaomimimo.com", credentialRef: "env:XIAOMI_API_KEY" });
const purposeDrafts = ref<Record<Purpose, { providerConnectionId: string; modelName: string }>>({
  CHAT: { providerConnectionId: "", modelName: "mimo-v2.5" },
  EMBEDDING: { providerConnectionId: "", modelName: "text-embedding-3-small" },
  RERANK: { providerConnectionId: "", modelName: "mimo-v2.5" },
});
const promptForm = ref({ name: "带引用回答规则", purpose: "CHAT" as Purpose });
const promptVersionForm = ref({
  messages: JSON.stringify([
    { role: "SYSTEM", content: "Use only the supplied evidence. Return ONLY valid JSON with this exact shape: {\"answer_text\":\"brief answer\",\"claims\":[{\"claim_text\":\"one supported claim\",\"citation_tokens\":[\"exact evidence UUIDv7 from the evidence id attribute\"]}]}. Do not use Markdown, code fences, extra keys, character offsets, or invented IDs." },
    { role: "USER", content: "{{query}}" },
  ], null, 2),
  variableSchema: JSON.stringify({ type: "object", required: ["context", "question"] }, null, 2),
  outputContract: JSON.stringify({ type: "object", required: ["answer_text", "claims"] }, null, 2),
  changeDescription: "创建初始 Prompt 版本",
});

const canManage = computed(() => props.currentRole === "SPACE_ADMIN" || props.platformRole === "PLATFORM_ADMIN");
const canManageProvider = computed(() => props.platformRole === "PLATFORM_ADMIN");
const canAuthorize = computed(() => props.currentRole === "SPACE_ADMIN");
const purposes: Purpose[] = ["CHAT", "EMBEDDING", "RERANK"];
const publishedPrompt = computed(() => promptTemplates.value.find((item) => item.purpose === "CHAT" && item.currentVersion !== null) ?? null);
const checklist = computed(() => [
  { key: "connection", label: "登记连接", done: providerConnections.value.length > 0 },
  { key: "CHAT", label: "对话模型", done: Boolean(boundSlot("CHAT")?.profile) },
  { key: "EMBEDDING", label: "向量模型", done: Boolean(boundSlot("EMBEDDING")?.profile) },
  { key: "RERANK", label: "重排模型", done: Boolean(boundSlot("RERANK")?.profile) },
  { key: "prompt", label: "回答规则", done: Boolean(publishedPrompt.value) },
  { key: "enabled", label: "本空间已启用", done: Boolean(spaceBinding.value?.cloudEgressEnabled) },
]);
const missingPurposes = computed(() => purposes.filter((purpose) => !boundSlot(purpose)?.route));
const canEnable = computed(() => missingPurposes.value.length === 0 && Boolean(publishedPrompt.value) && canAuthorize.value && confirmEgress.value);

function path(suffix: string): string { return `/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}${suffix}`; }
function describeError(value: unknown, fallback: string): string {
  if (value instanceof Error && !(value instanceof ApiError)) return value.message;
  if (!(value instanceof ApiError)) return fallback;
  const permission = value.status === 403 ? "当前角色无权执行此操作。" : value.status === 404 ? "资源不存在或不属于当前空间。" : value.status === 409 ? "资源版本冲突，请刷新后重试。" : "请检查输入并稍后重试。";
  const correlation = value.correlationId ? ` 相关 ID：${value.correlationId}` : "";
  return `${value.problem?.code ? `${value.problem.code}：` : ""}${value.problem?.detail ?? value.message} ${permission}${correlation}`;
}
function ensureSpace(): boolean {
  if (props.selectedSpaceId) return true;
  error.value = "请先选择当前空间。";
  return false;
}
function boundSlot(purpose: Purpose): { route: ModelRoute; profile: ModelProfile | null } | null {
  const route = modelRoutes.value.find((item) => item.purpose === purpose && item.status === "ACTIVE");
  if (!route) return null;
  const profileId = route.candidates[0]?.modelProfileId;
  const profile = modelProfiles.value.find((item) => item.modelProfileId === profileId) ?? null;
  return { route, profile };
}
function capabilitiesFor(purpose: Purpose, verified: string[]): string[] {
  if (verified.length) return verified;
  if (purpose === "EMBEDDING") return ["EMBEDDING"];
  if (purpose === "RERANK") return ["RERANK"];
  return ["CHAT", "STREAMING", "USAGE_REPORTING"];
}

async function loadSection(): Promise<void> {
  error.value = "";
  if (!ensureSpace()) return;
  loading.value = true;
  try {
    const [connections, profiles, routes, binding, prompts] = await Promise.all([
      listAllCursorPages<ProviderConnection>(path("/provider-connections"), { limit: 10 }),
      listAllCursorPages<ModelProfile>(path("/model-profiles"), { limit: 10 }),
      listAllCursorPages<ModelRoute>(path("/model-routes"), { limit: 10 }),
      apiFetch<SpaceBinding>(path("/space-bindings")).catch(() => null),
      listAllCursorPages<PromptTemplate>(path("/prompt-templates"), { limit: 10 }),
    ]);
    providerConnections.value = connections;
    modelProfiles.value = profiles;
    modelRoutes.value = routes;
    spaceBinding.value = binding;
    promptTemplates.value = prompts;
    const firstConnection = connections[0]?.providerConnectionId ?? "";
    for (const purpose of purposes) {
      if (!purposeDrafts.value[purpose].providerConnectionId) purposeDrafts.value[purpose].providerConnectionId = firstConnection;
    }
    if (!prompts.some((item) => item.promptTemplateId === selectedPromptTemplateId.value)) {
      selectedPromptTemplateId.value = prompts.find((item) => item.purpose === "CHAT")?.promptTemplateId
        ?? prompts[0]?.promptTemplateId ?? "";
      promptVersionDetails.value = null;
    }
    confirmEgress.value = Boolean(binding?.cloudEgressEnabled);
  } catch (value) {
    error.value = describeError(value, "云端模型数据加载失败。");
  } finally {
    loading.value = false;
  }
}

async function createProvider(): Promise<void> {
  if (!ensureSpace() || !canManageProvider.value) return;
  loading.value = true; error.value = ""; notice.value = "";
  try {
    await apiFetch<ProviderConnection>(path("/provider-connections"), {
      method: "POST",
      body: { ...providerForm.value, egressClass: "CLOUD", status: "ACTIVE" },
    });
    notice.value = "连接已登记。请分别验证对话、向量和重排模型。";
    await loadSection();
  } catch (value) { error.value = describeError(value, "连接创建失败。"); }
  finally { loading.value = false; }
}

async function testProvider(connection: ProviderConnection, purpose: Purpose, modelName: string): Promise<ProviderConnectionTestResult | null> {
  if (!ensureSpace() || !canManage.value) return null;
  if (!modelName.trim()) { error.value = "请填写需要实测的模型名。"; return null; }
  const allowCloudProbe = connection.egressClass === "CLOUD"
    ? window.confirm("将向该云端服务发送固定合成探测文本，不包含空间内容。是否仅批准本次测试？") : false;
  if (connection.egressClass === "CLOUD" && !allowCloudProbe) return null;
  loading.value = true; error.value = ""; notice.value = "";
  try {
    lastProviderTest.value = await apiFetch<ProviderConnectionTestResult>(path(`/provider-connections/${encodeURIComponent(connection.providerConnectionId)}/test`), {
      method: "POST", body: { modelName: modelName.trim(), purpose, timeoutSeconds: 15, allowCloudProbe },
    });
    notice.value = lastProviderTest.value.outcome === "SUCCEEDED"
      ? `${purposeLabel(purpose)} 测试通过`
      : `连接测试失败：${lastProviderTest.value.errorClass ?? "UNKNOWN"}`;
    return lastProviderTest.value;
  } catch (value) { error.value = describeError(value, "连接测试失败。"); return null; }
  finally { loading.value = false; }
}

async function savePurpose(purpose: Purpose): Promise<void> {
  if (!ensureSpace() || !canManage.value) return;
  const draft = purposeDrafts.value[purpose];
  const connection = providerConnections.value.find((item) => item.providerConnectionId === draft.providerConnectionId);
  if (!connection) { error.value = "请先选择连接。"; return; }
  const test = await testProvider(connection, purpose, draft.modelName);
  if (!test || test.outcome !== "SUCCEEDED") return;
  loading.value = true; error.value = "";
  try {
    const profile = await apiFetch<ModelProfile>(path("/model-profiles"), {
      method: "POST",
      body: {
        providerConnectionId: connection.providerConnectionId,
        purpose,
        modelName: draft.modelName.trim(),
        capabilities: capabilitiesFor(purpose, test.verifiedCapabilities),
        contextWindow: 32768,
        maxOutputTokens: 2048,
        embeddingDimension: purpose === "EMBEDDING" ? test.embeddingDimension : null,
        usageReporting: "PROVIDER_REPORTED",
        status: "PUBLISHED",
      },
    });
    await apiFetch<ModelRoute>(path("/model-routes"), {
      method: "POST",
      body: {
        purpose,
        egressClass: connection.egressClass,
        failoverPolicy: "NONE",
        status: "ACTIVE",
        candidates: [{ modelProfileId: profile.modelProfileId, priority: 1, egressClass: connection.egressClass }],
      },
    });
    notice.value = `${purposeLabel(purpose)} 已绑定 ${draft.modelName}。`;
    await loadSection();
  } catch (value) { error.value = describeError(value, "用途绑定失败。"); }
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

async function publishedPromptVersionId(): Promise<string | null> {
  const template = publishedPrompt.value;
  if (!template?.currentVersion) return null;
  const version = await apiFetch<PromptVersion>(path(`/prompt-templates/${encodeURIComponent(template.promptTemplateId)}/versions/${template.currentVersion}`));
  return version.state === "PUBLISHED" ? version.promptVersionId : null;
}

async function useDefaultPrompt(): Promise<void> {
  if (!ensureSpace() || !canManage.value) return;
  loading.value = true; error.value = ""; notice.value = "";
  try {
    let templateId = publishedPrompt.value?.promptTemplateId ?? selectedPromptTemplateId.value;
    if (!templateId) {
      const created = await apiFetch<PromptTemplate>(path("/prompt-templates"), { method: "POST", body: promptForm.value });
      templateId = created.promptTemplateId;
      selectedPromptTemplateId.value = templateId;
    }
    const version = await apiFetch<PromptVersion>(path(`/prompt-templates/${encodeURIComponent(templateId)}/versions`), {
      method: "POST",
      body: {
        messages: parsePromptMessages(promptVersionForm.value.messages),
        variableSchema: parseJsonObject(promptVersionForm.value.variableSchema, "variableSchema"),
        outputContract: parseJsonObject(promptVersionForm.value.outputContract, "outputContract"),
        changeDescription: promptVersionForm.value.changeDescription.trim(),
      },
    });
    promptVersionDetails.value = await apiFetch<PromptVersion>(path(`/prompt-templates/${encodeURIComponent(version.promptTemplateId)}/versions/${version.version}/publish`), { method: "POST" });
    notice.value = "已发布默认带引用回答规则。";
    await loadSection();
  } catch (value) { error.value = describeError(value, "默认回答规则发布失败。"); }
  finally { loading.value = false; }
}

async function createPrompt(): Promise<void> {
  if (!ensureSpace() || !canManage.value) return;
  loading.value = true; error.value = ""; notice.value = "";
  try {
    const created = await apiFetch<PromptTemplate>(path("/prompt-templates"), { method: "POST", body: promptForm.value });
    selectedPromptTemplateId.value = created.promptTemplateId;
    promptVersionDetails.value = null;
    notice.value = "回答规则模板已创建，请编写并发布草稿。";
    await loadSection();
  } catch (value) { error.value = describeError(value, "模板创建失败。"); }
  finally { loading.value = false; }
}

async function createPromptVersion(): Promise<void> {
  if (!ensureSpace() || !canManage.value) return;
  if (!selectedPromptTemplateId.value) { error.value = "请先选择模板。"; return; }
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
    notice.value = `草稿 v${version.version} 已创建，确认后发布。`;
  } catch (value) { error.value = describeError(value, "草稿创建失败。"); }
  finally { loading.value = false; }
}

async function publishPromptVersion(): Promise<void> {
  if (!ensureSpace() || !canManage.value || !promptVersionDetails.value) return;
  if (promptVersionDetails.value.state !== "DRAFT") return;
  loading.value = true; error.value = ""; notice.value = "";
  try {
    const version = promptVersionDetails.value;
    promptVersionDetails.value = await apiFetch<PromptVersion>(path(`/prompt-templates/${encodeURIComponent(version.promptTemplateId)}/versions/${version.version}/publish`), { method: "POST" });
    notice.value = `v${version.version} 已发布，发布后不可变。`;
    await loadSection();
  } catch (value) { error.value = describeError(value, "发布失败。"); }
  finally { loading.value = false; }
}

async function viewPromptVersion(template: PromptTemplate): Promise<void> {
  if (!template.currentVersion) {
    promptVersionDetails.value = null;
    error.value = "当前模板还没有已发布版本。";
    return;
  }
  loading.value = true; error.value = "";
  try {
    promptVersionDetails.value = await apiFetch<PromptVersion>(path(`/prompt-templates/${encodeURIComponent(template.promptTemplateId)}/versions/${template.currentVersion}`));
  } catch (value) {
    error.value = describeError(value, "版本加载失败。");
  } finally {
    loading.value = false;
  }
}

async function enableSpaceAnswers(): Promise<void> {
  if (!ensureSpace() || !canAuthorize.value) {
    error.value = "只有空间管理员可以启用本空间的云端问答。";
    return;
  }
  if (!confirmEgress.value) {
    error.value = "请先确认允许把本空间资料发给已登记的云端模型。";
    return;
  }
  const chat = boundSlot("CHAT")?.route;
  const embedding = boundSlot("EMBEDDING")?.route;
  const rerank = boundSlot("RERANK")?.route;
  if (!chat || !embedding || !rerank) {
    error.value = "请先完成对话、向量和重排三种模型绑定。";
    return;
  }
  loading.value = true; error.value = ""; notice.value = "";
  try {
    const promptVersionId = await publishedPromptVersionId();
    if (!promptVersionId) {
      error.value = "请先发布回答规则，或使用默认引用规则。";
      return;
    }
    const write = spaceBindingWrite(spaceBinding.value);
    spaceBinding.value = await apiFetch<SpaceBinding>(path("/space-bindings"), {
      method: "PUT",
      headers: { "If-Match": write.ifMatch },
      body: {
        version: write.version,
        chatRouteId: chat.modelRouteId,
        embeddingRouteId: embedding.modelRouteId,
        rerankRouteId: rerank.modelRouteId,
        promptVersionId,
        cloudEgressEnabled: true,
        cloudEgressAuthorization: cloudEgressPayload(createUuidV7(), props.currentUserId),
      },
    });
    notice.value = "本空间已启用云端问答。接下来去资料页导入文档，再去提问。";
    await loadSection();
  } catch (value) { error.value = describeError(value, "启用本空间问答失败。"); }
  finally { loading.value = false; }
}

watch(() => props.initialSection, (value) => { section.value = value === "prompts" ? "prompts" : "providers"; });
watch(section, (value) => emit("select-section", value));
watch(() => [props.selectedSpaceId, section.value], () => { void loadSection(); }, { immediate: true });
</script>

<template>
  <section class="view-section control-center" aria-labelledby="control-center-heading">
    <div class="section-heading">
      <div>
        <p class="eyebrow">云端模型</p>
        <h2 id="control-center-heading">{{ section === "prompts" ? "回答规则" : "启用本空间问答" }}</h2>
        <p>{{ section === "prompts" ? "问答只用已发布的规则。普通使用点「使用默认引用规则」即可。" : "按清单完成连接、三种模型和启用。页面会把路线、档案和出境授权写进当前空间，不必手填编号。" }}</p>
      </div>
      <div class="read-only-note" :class="{ warning: !canManage }">{{ canManage ? "可保存本空间配置" : "当前角色：只读" }}</div>
    </div>
    <nav class="control-nav" aria-label="云端模型">
      <button type="button" :class="{ active: section === 'providers' }" @click="section = 'providers'">启用向导</button>
      <button type="button" :class="{ active: section === 'prompts' }" @click="section = 'prompts'">回答规则</button>
    </nav>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p v-if="notice" class="alert success" role="status">{{ notice }}</p>

    <template v-if="section === 'providers'">
      <ol class="checklist" aria-label="启用进度">
        <li v-for="item in checklist" :key="item.key" :class="{ done: item.done }">{{ item.label }}</li>
      </ol>

      <article class="card enable-card">
        <div>
          <span class="card-label">本空间问答</span>
          <strong>{{ spaceBinding?.cloudEgressEnabled ? "已启用云端问答" : "尚未启用" }}</strong>
          <p>启用后才会把资料发给已验证的云端模型。未授权不会改走其他云端。</p>
        </div>
        <div class="enable-actions">
          <label class="check-row"><input v-model="confirmEgress" type="checkbox" :disabled="!canAuthorize" />允许把本空间资料发给已登记的云端模型（30 天）</label>
          <button type="button" :disabled="loading || !canEnable" @click="enableSpaceAnswers">{{ spaceBinding?.cloudEgressEnabled ? "更新并重新授权" : "启用本空间问答" }}</button>
          <small v-if="!canAuthorize" class="permission-hint">只有空间管理员可以授权出境。</small>
          <small v-else-if="missingPurposes.length" class="permission-hint">还差：{{ missingPurposes.map(purposeLabel).join("、") }}</small>
          <small v-else-if="!publishedPrompt" class="permission-hint">还差已发布的回答规则。</small>
        </div>
      </article>

      <div class="card list-card">
        <div class="card-title"><h3>1. 连接</h3><button type="button" class="quiet-button" :disabled="loading" @click="loadSection">刷新</button></div>
        <p v-if="!providerConnections.length" class="empty-state">还没有连接。{{ canManageProvider ? "先登记 OpenAI 兼容或 MiMo 端点。" : "请让平台管理员登记连接。" }}</p>
        <article v-for="item in providerConnections" :key="item.providerConnectionId" class="list-row">
          <div>
            <strong>{{ item.displayName || providerLabel(item.providerType) }}</strong>
            <span>{{ item.endpoint }} · {{ egressLabel(item.egressClass) }}</span>
          </div>
          <span class="state-pill" :class="item.status.toLowerCase()">{{ stateLabel(item.status) }}</span>
        </article>
        <form v-if="canManageProvider" class="inline-form" @submit.prevent="createProvider">
          <div class="form-grid">
            <div class="field"><label for="provider-display-name">显示名称</label><input id="provider-display-name" v-model="providerForm.displayName" required /></div>
            <div class="field"><label for="provider-type">类型</label><select id="provider-type" v-model="providerForm.providerType"><option value="OPENAI_COMPATIBLE">兼容 OpenAI 的服务</option><option value="MIMO">MiMo 云端服务</option></select></div>
            <div class="field"><label for="provider-endpoint">服务地址</label><input id="provider-endpoint" v-model="providerForm.endpoint" type="url" required /></div>
            <div class="field"><label for="provider-credential-ref">凭据引用</label><input id="provider-credential-ref" v-model="providerForm.credentialRef" required /></div>
          </div>
          <button type="submit" :disabled="loading">保存连接</button>
        </form>
      </div>

      <div class="purpose-grid">
        <article v-for="purpose in purposes" :key="purpose" class="card purpose-card">
          <span class="card-label">2. {{ purposeLabel(purpose) }}</span>
          <template v-if="boundSlot(purpose)?.profile">
            <strong>{{ boundSlot(purpose)?.profile?.modelName }}</strong>
            <p>已完成验证并绑定到本空间用途。</p>
          </template>
          <p v-else class="empty-state">未配置</p>
          <form v-if="canManage" class="slot-form" @submit.prevent="savePurpose(purpose)">
            <div class="field"><label :for="`purpose-connection-${purpose}`">连接</label><select :id="`purpose-connection-${purpose}`" v-model="purposeDrafts[purpose].providerConnectionId" required><option value="">选择连接</option><option v-for="item in providerConnections" :key="item.providerConnectionId" :value="item.providerConnectionId">{{ item.displayName || item.providerType }}</option></select></div>
            <div class="field"><label :for="`purpose-model-${purpose}`">模型名</label><input :id="`purpose-model-${purpose}`" v-model="purposeDrafts[purpose].modelName" required /></div>
            <button type="submit" :disabled="loading || !purposeDrafts[purpose].providerConnectionId">测试并保存</button>
          </form>
        </article>
      </div>

      <article class="card">
        <span class="card-label">3. 回答规则</span>
        <strong>{{ publishedPrompt ? `${publishedPrompt.name} · 已发布 v${publishedPrompt.currentVersion}` : "尚未发布" }}</strong>
        <p>默认规则要求模型只引用结构化证据，不会把引用写成自由文本。</p>
        <button v-if="canManage" type="button" class="secondary-button" :disabled="loading" @click="useDefaultPrompt">使用默认引用规则</button>
        <button type="button" class="quiet-button" @click="section = 'prompts'">查看或自定义</button>
      </article>

      <details class="card advanced" :open="showAdvanced">
        <summary @click.prevent="showAdvanced = !showAdvanced">高级：内部标识（排障用）</summary>
        <p v-for="purpose in purposes" :key="`adv-${purpose}`" class="muted">
          {{ purposeLabel(purpose) }}
          · 路线 <code>{{ boundSlot(purpose)?.route.modelRouteId ?? "—" }}</code>
          · 档案 <code>{{ boundSlot(purpose)?.profile?.modelProfileId ?? "—" }}</code>
        </p>
      </details>
    </template>

    <div v-else class="control-layout">
      <div class="card list-card">
        <div class="card-title"><h3>已发布规则</h3><button type="button" class="quiet-button" :disabled="loading" @click="loadSection">刷新</button></div>
        <p v-if="!promptTemplates.length" class="empty-state">还没有回答规则模板。</p>
        <article v-for="item in promptTemplates" :key="item.promptTemplateId" class="list-row">
          <div>
            <strong>{{ item.name }}</strong>
            <span>{{ purposeLabel(item.purpose) }} · {{ item.currentVersion ? `已发布 v${item.currentVersion}` : "尚未发布" }}</span>
          </div>
          <div class="list-row-actions">
            <button type="button" class="quiet-button" @click="selectedPromptTemplateId = item.promptTemplateId; promptVersionDetails = null">选为草稿模板</button>
            <button v-if="item.currentVersion" type="button" class="quiet-button" @click="viewPromptVersion(item)">查看已发布</button>
          </div>
        </article>
        <article v-if="promptVersionDetails" class="prompt-version-card">
          <div class="card-title"><h3>第 {{ promptVersionDetails.version }} 版</h3><span class="state-pill">{{ promptVersionDetails.state === "PUBLISHED" ? "已发布" : promptVersionDetails.state === "DRAFT" ? "草稿" : "已退役" }}</span></div>
          <button v-if="promptVersionDetails.state === 'DRAFT'" type="button" @click="publishPromptVersion">发布此版本</button>
        </article>
      </div>
      <div class="stack">
        <button v-if="canManage" type="button" :disabled="loading" @click="useDefaultPrompt">使用默认引用规则</button>
        <form class="card form-card" @submit.prevent="createPrompt">
          <h3>创建模板</h3>
          <div class="field"><label for="prompt-name">名称</label><input id="prompt-name" v-model="promptForm.name" required /></div>
          <button type="submit" :disabled="loading || !canManage">创建模板</button>
        </form>
        <details class="card form-card">
          <summary>高级：编辑草稿 JSON</summary>
          <form @submit.prevent="createPromptVersion">
            <div class="field"><label for="prompt-version-template">模板</label><select id="prompt-version-template" v-model="selectedPromptTemplateId" required><option value="">选择模板</option><option v-for="item in promptTemplates" :key="item.promptTemplateId" :value="item.promptTemplateId">{{ item.name }}</option></select></div>
            <div class="field"><label for="prompt-messages">messages</label><textarea id="prompt-messages" v-model="promptVersionForm.messages" rows="7" required></textarea></div>
            <div class="field"><label for="prompt-variables">variableSchema</label><textarea id="prompt-variables" v-model="promptVersionForm.variableSchema" rows="4" required></textarea></div>
            <div class="field"><label for="prompt-contract">outputContract</label><textarea id="prompt-contract" v-model="promptVersionForm.outputContract" rows="4" required></textarea></div>
            <div class="field"><label for="prompt-change-description">变更说明</label><input id="prompt-change-description" v-model="promptVersionForm.changeDescription" required /></div>
            <button type="submit" :disabled="loading || !canManage || !selectedPromptTemplateId">创建草稿</button>
          </form>
        </details>
      </div>
    </div>
  </section>
</template>

<style scoped>
.control-center { margin-top: 0; }
.control-nav { display: flex; flex-wrap: wrap; gap: 6px; margin: 0 0 18px; }
.control-nav button { border: 1px solid #e5e7eb; background: #fff; color: #374151; box-shadow: none; }
.control-nav button.active { border-color: #111827; background: #111827; color: #fff; }
.checklist { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 8px; margin: 0 0 16px; padding: 0; list-style: none; }
.checklist li { padding: 8px 10px; border: 1px solid #e5e7eb; border-radius: 10px; background: #fff; color: #6b7280; font-size: .74rem; }
.checklist li.done { border-color: #a7f3d0; background: #ecfdf5; color: #047857; }
.control-layout { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(320px, .9fr); gap: 16px; }
.stack, .purpose-grid { display: grid; gap: 16px; }
.purpose-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); margin: 16px 0; }
.list-row { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 10px; padding: 12px; border: 1px solid #e3eaf4; border-radius: 10px; background: #f8fbff; }
.list-row strong, .list-row span { display: block; overflow-wrap: anywhere; }
.list-row span { margin-top: 4px; color: #71809a; font-size: .78rem; }
.list-row-actions { display: flex; align-items: center; gap: 8px; }
.prompt-version-card { margin-top: 14px; padding: 14px; border: 1px solid #c8d8ef; border-radius: 10px; background: #f5f9ff; }
.enable-card { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; margin-bottom: 16px; }
.enable-actions, .slot-form, .inline-form { display: grid; gap: 10px; }
.check-row { display: flex; align-items: center; gap: 8px; color: #4b5563; font-size: .8rem; }
.permission-hint { color: #b45309; }
.advanced { margin-top: 16px; padding: 14px; }
.advanced summary { cursor: pointer; font-weight: 700; }
@media (max-width: 850px) { .control-layout, .purpose-grid, .checklist { grid-template-columns: 1fr; } .list-row { align-items: flex-start; flex-direction: column; } .enable-card { flex-direction: column; } }
</style>
