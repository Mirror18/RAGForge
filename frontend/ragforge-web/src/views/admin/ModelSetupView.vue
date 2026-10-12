<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ApiError, listAllCursorPages, type ModelProfile, type ModelRoute, type PlatformRole, type PromptTemplate, type PromptVersion, type ProviderConnection, type ProviderConnectionTestResult, type SpaceBinding, type SpaceRole, apiFetch } from "../../api";
import { egressLabel, providerLabel, purposeLabel, stateLabel } from "../../i18n";

type ModelSection = "providers" | "prompts";
type Purpose = "CHAT" | "EMBEDDING" | "RERANK";

const props = defineProps<{
  selectedSpaceId: string;
  currentRole: SpaceRole | PlatformRole | string;
  platformRole: PlatformRole;
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
const providerTestModel = ref("mimo-v2.5");
const providerTestPurpose = ref<Purpose>("CHAT");
const providerForm = ref({ displayName: "Xiaomi MiMo 云端", providerType: "MIMO", endpoint: "https://api.xiaomimimo.com", credentialRef: "env:XIAOMI_API_KEY" });
const purposeDraft = ref({ purpose: "CHAT" as Purpose, providerConnectionId: "", modelName: "mimo-v2.5" });
const promptForm = ref({ name: "RAG Chat Prompt", purpose: "CHAT" as Purpose });
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
const purposes: Purpose[] = ["CHAT", "EMBEDDING", "RERANK"];

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
  notice.value = "";
  if (!ensureSpace()) return;
  loading.value = true;
  try {
    if (section.value === "prompts") {
      const response = await listAllCursorPages<PromptTemplate>(path("/prompt-templates"), { limit: 10 });
      promptTemplates.value = response;
      if (!response.some((item) => item.promptTemplateId === selectedPromptTemplateId.value)) {
        selectedPromptTemplateId.value = response.find((item) => item.purpose === "CHAT")?.promptTemplateId
          ?? response[0]?.promptTemplateId ?? "";
        promptVersionDetails.value = null;
      }
      return;
    }
    const [connections, profiles, routes, binding] = await Promise.all([
      listAllCursorPages<ProviderConnection>(path("/provider-connections"), { limit: 10 }),
      listAllCursorPages<ModelProfile>(path("/model-profiles"), { limit: 10 }),
      listAllCursorPages<ModelRoute>(path("/model-routes"), { limit: 10 }),
      apiFetch<SpaceBinding>(path("/space-bindings")).catch(() => null),
    ]);
    providerConnections.value = connections;
    modelProfiles.value = profiles;
    modelRoutes.value = routes;
    spaceBinding.value = binding;
    if (!purposeDraft.value.providerConnectionId) purposeDraft.value.providerConnectionId = connections[0]?.providerConnectionId ?? "";
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
    notice.value = "连接已登记。请测试后再绑定用途。";
    await loadSection();
  } catch (value) { error.value = describeError(value, "连接创建失败。"); }
  finally { loading.value = false; }
}

async function testProvider(connection: ProviderConnection, purpose = providerTestPurpose.value, modelName = providerTestModel.value): Promise<ProviderConnectionTestResult | null> {
  if (!ensureSpace() || !canManageProvider.value) return null;
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
      ? `连接测试通过：${lastProviderTest.value.verifiedCapabilities.join(", ")}`
      : `连接测试失败：${lastProviderTest.value.errorClass ?? "UNKNOWN"}`;
    return lastProviderTest.value;
  } catch (value) { error.value = describeError(value, "连接测试失败。"); return null; }
  finally { loading.value = false; }
}

async function savePurpose(): Promise<void> {
  if (!ensureSpace() || !canManage.value) return;
  const connection = providerConnections.value.find((item) => item.providerConnectionId === purposeDraft.value.providerConnectionId);
  if (!connection) { error.value = "请先选择连接。"; return; }
  const test = await testProvider(connection, purposeDraft.value.purpose, purposeDraft.value.modelName);
  if (!test || test.outcome !== "SUCCEEDED") return;
  loading.value = true; error.value = "";
  try {
    const profile = await apiFetch<ModelProfile>(path("/model-profiles"), {
      method: "POST",
      body: {
        providerConnectionId: connection.providerConnectionId,
        purpose: purposeDraft.value.purpose,
        modelName: purposeDraft.value.modelName.trim(),
        capabilities: capabilitiesFor(purposeDraft.value.purpose, test.verifiedCapabilities),
        contextWindow: 32768,
        maxOutputTokens: 2048,
        embeddingDimension: purposeDraft.value.purpose === "EMBEDDING" ? test.embeddingDimension : null,
        usageReporting: "PROVIDER_REPORTED",
        status: "PUBLISHED",
      },
    });
    await apiFetch<ModelRoute>(path("/model-routes"), {
      method: "POST",
      body: {
        purpose: purposeDraft.value.purpose,
        egressClass: connection.egressClass,
        failoverPolicy: "NONE",
        status: "ACTIVE",
        candidates: [{ modelProfileId: profile.modelProfileId, priority: 1, egressClass: connection.egressClass }],
      },
    });
    notice.value = `${purposeLabel(purposeDraft.value.purpose)} 已绑定 ${purposeDraft.value.modelName}。`;
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

watch(() => props.initialSection, (value) => { section.value = value === "prompts" ? "prompts" : "providers"; });
watch(section, (value) => emit("select-section", value));
watch(() => [props.selectedSpaceId, section.value], () => { void loadSection(); }, { immediate: true });
</script>

<template>
  <section class="view-section control-center" aria-labelledby="control-center-heading">
    <div class="section-heading">
      <div>
        <p class="eyebrow">云端模型</p>
        <h2 id="control-center-heading">{{ section === "prompts" ? "回答规则" : "连接与用途" }}</h2>
        <p>{{ section === "prompts" ? "版本化回答规则，发布后不可变。问答只使用已发布版本。" : "先登记并验证连接，再为对话、向量和重排绑定模型。未授权空间不会改走云端。" }}</p>
      </div>
      <div class="read-only-note" :class="{ warning: !canManage }">{{ canManage ? "空间管理员：可保存绑定" : "当前角色：只读" }}</div>
    </div>
    <nav class="control-nav" aria-label="云端模型">
      <button type="button" :class="{ active: section === 'providers' }" @click="section = 'providers'">连接与用途</button>
      <button type="button" :class="{ active: section === 'prompts' }" @click="section = 'prompts'">回答规则</button>
    </nav>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p v-if="notice" class="alert success" role="status">{{ notice }}</p>

    <template v-if="section === 'providers'">
      <article class="card egress-chip">
        <div>
          <span class="card-label">本空间云端出境</span>
          <strong>{{ spaceBinding?.cloudEgressEnabled ? "已授权" : "未授权（fail-closed）" }}</strong>
          <p>出境在知识空间中审批。这里不会静默打开云端。</p>
        </div>
        <a class="secondary-button" :href="`?view=profile&spaceId=${encodeURIComponent(selectedSpaceId)}`">去知识空间</a>
      </article>

      <div class="card list-card">
        <div class="card-title"><h3>已登记连接</h3><button type="button" class="quiet-button" :disabled="loading" @click="loadSection">刷新</button></div>
        <p v-if="!providerConnections.length" class="empty-state">还没有连接。平台管理员可登记 OpenAI 兼容或 MiMo 端点。</p>
        <article v-for="item in providerConnections" :key="item.providerConnectionId" class="list-row">
          <div>
            <strong>{{ item.displayName || providerLabel(item.providerType) }} · {{ item.endpoint }}</strong>
            <span>{{ egressLabel(item.egressClass) }}</span>
          </div>
          <div class="list-row-actions">
            <span class="state-pill" :class="item.status.toLowerCase()">{{ stateLabel(item.status) }}</span>
            <button v-if="canManageProvider" type="button" class="quiet-button" :disabled="loading" @click="testProvider(item)">测试</button>
          </div>
        </article>
        <p v-if="lastProviderTest" class="muted">最近测试：{{ lastProviderTest.outcome }} · {{ lastProviderTest.modelName }}</p>
      </div>

      <form v-if="canManageProvider" class="card form-card" @submit.prevent="createProvider">
        <h3>登记连接</h3>
        <p class="muted">只保存凭据引用，不在页面输入密钥。</p>
        <div class="form-grid">
          <div class="field"><label for="provider-display-name">显示名称</label><input id="provider-display-name" v-model="providerForm.displayName" required /></div>
          <div class="field"><label for="provider-type">类型</label><select id="provider-type" v-model="providerForm.providerType"><option value="OPENAI_COMPATIBLE">兼容 OpenAI 的服务</option><option value="MIMO">MiMo 云端服务</option></select></div>
          <div class="field"><label for="provider-endpoint">服务地址</label><input id="provider-endpoint" v-model="providerForm.endpoint" type="url" required /></div>
          <div class="field"><label for="provider-credential-ref">凭据引用</label><input id="provider-credential-ref" v-model="providerForm.credentialRef" required /></div>
        </div>
        <button type="submit" :disabled="loading">保存连接</button>
      </form>

      <div class="purpose-grid">
        <article v-for="purpose in purposes" :key="purpose" class="card purpose-card">
          <span class="card-label">{{ purposeLabel(purpose) }}</span>
          <template v-if="boundSlot(purpose)?.profile">
            <strong>{{ boundSlot(purpose)?.profile?.modelName }}</strong>
            <p>已验证：{{ boundSlot(purpose)?.profile?.verifiedCapabilities.join(", ") || "未列出" }}</p>
          </template>
          <p v-else class="empty-state">未配置</p>
        </article>
      </div>

      <form v-if="canManage" class="card form-card" @submit.prevent="savePurpose">
        <h3>绑定用途</h3>
        <p class="muted">测试通过后会创建能力档案和路线。失败不会改走其他云端。</p>
        <div class="form-grid">
          <div class="field"><label for="purpose-kind">用途</label><select id="purpose-kind" v-model="purposeDraft.purpose"><option v-for="item in purposes" :key="item" :value="item">{{ purposeLabel(item) }}</option></select></div>
          <div class="field"><label for="purpose-connection">连接</label><select id="purpose-connection" v-model="purposeDraft.providerConnectionId" required><option value="">选择连接</option><option v-for="item in providerConnections" :key="item.providerConnectionId" :value="item.providerConnectionId">{{ item.displayName || item.providerType }} · {{ item.endpoint }}</option></select></div>
          <div class="field"><label for="purpose-model">模型名</label><input id="purpose-model" v-model="purposeDraft.modelName" required /></div>
        </div>
        <button type="submit" :disabled="loading || !purposeDraft.providerConnectionId">测试并保存</button>
      </form>

      <details class="card advanced" :open="showAdvanced">
        <summary @click.prevent="showAdvanced = !showAdvanced">高级：档案与路线标识</summary>
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
          <div class="card-title"><h3>v{{ promptVersionDetails.version }}</h3><span class="state-pill">{{ promptVersionDetails.state }}</span></div>
          <p>contentHash：<code>{{ promptVersionDetails.contentHash }}</code></p>
          <button v-if="promptVersionDetails.state === 'DRAFT'" type="button" @click="publishPromptVersion">发布此版本</button>
        </article>
      </div>
      <div class="stack">
        <form class="card form-card" @submit.prevent="createPrompt">
          <h3>创建模板</h3>
          <div class="field"><label for="prompt-name">名称</label><input id="prompt-name" v-model="promptForm.name" required /></div>
          <div class="field"><label for="prompt-purpose">用途</label><select id="prompt-purpose" v-model="promptForm.purpose"><option>CHAT</option></select></div>
          <button type="submit" :disabled="loading || !canManage">创建模板</button>
        </form>
        <details class="card form-card">
          <summary>编辑草稿（JSON 契约）</summary>
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
.control-layout { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(320px, .9fr); gap: 16px; }
.stack, .purpose-grid { display: grid; gap: 16px; }
.purpose-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); margin: 16px 0; }
.list-row { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 10px; padding: 12px; border: 1px solid #e3eaf4; border-radius: 10px; background: #f8fbff; }
.list-row strong, .list-row span { display: block; overflow-wrap: anywhere; }
.list-row span { margin-top: 4px; color: #71809a; font-size: .78rem; }
.list-row-actions { display: flex; align-items: center; gap: 8px; }
.prompt-version-card { margin-top: 14px; padding: 14px; border: 1px solid #c8d8ef; border-radius: 10px; background: #f5f9ff; }
.egress-chip { display: flex; justify-content: space-between; gap: 16px; align-items: center; margin-bottom: 16px; }
.advanced { margin-top: 16px; padding: 14px; }
.advanced summary { cursor: pointer; font-weight: 700; }
@media (max-width: 850px) { .control-layout, .purpose-grid { grid-template-columns: 1fr; } .list-row { align-items: flex-start; flex-direction: column; } .egress-chip { flex-direction: column; align-items: flex-start; } }
</style>
