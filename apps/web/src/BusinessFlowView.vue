<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ApiError, type AnswerDefaults, type ModelProfile, type ModelRoute, type PlatformRole, type PromptTemplate, type PromptVersion, type ProviderConnection, type SpaceRole, apiFetch } from "./api";

const props = defineProps<{
  selectedSpaceId: string;
  currentRole: SpaceRole | PlatformRole | string;
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
const model = ref("qwen3.5:9b");
const indexVersionId = ref("");
const datasetHash = ref("");

const selectedRoute = computed(() => modelRoutes.value.find((item) => item.modelRouteId === selectedRouteId.value) ?? null);
const selectedProfile = computed(() => modelProfiles.value.find((item) => item.modelProfileId === selectedProfileId.value) ?? null);
const selectedProvider = computed(() => providerConnections.value.find((item) => item.providerConnectionId === selectedProfile.value?.providerConnectionId) ?? null);
const selectedTemplate = computed(() => promptTemplates.value.find((item) => item.promptTemplateId === selectedPromptTemplateId.value) ?? null);
const hasPublishedModel = computed(() => selectedRoute.value?.status === "ACTIVE" && selectedProfile.value?.status === "PUBLISHED" && selectedProvider.value?.status === "ACTIVE");
const hasPublishedPrompt = computed(() => Boolean(promptVersion.value?.promptVersionId && promptVersion.value.state === "PUBLISHED"));
const hasIndexIdentity = computed(() => Boolean(indexVersionId.value.trim() && /^[0-9a-f]{64}$/i.test(datasetHash.value.trim())));
const canStart = computed(() => Boolean(props.selectedSpaceId && hasPublishedModel.value && hasPublishedPrompt.value && hasIndexIdentity.value));

function describeError(value: unknown): string {
  if (!(value instanceof ApiError)) return "业务闭环配置加载失败，请稍后重试。";
  if (value.status === 403) return "当前角色无权读取或操作此空间配置。";
  if (value.status === 404) return "配置资源不存在，或不属于当前空间。";
  return value.problem?.detail ?? value.message;
}

async function loadPromptVersion(template: PromptTemplate | undefined): Promise<void> {
  promptVersion.value = null;
  if (!template?.currentVersion) return;
  promptVersion.value = await apiFetch<PromptVersion>(`/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}/prompt-templates/${encodeURIComponent(template.promptTemplateId)}/versions/${template.currentVersion}`);
}

async function loadFlow(): Promise<void> {
  if (!props.selectedSpaceId) return;
  loading.value = true; error.value = ""; notice.value = "";
  try {
    const [providers, profiles, routes, prompts] = await Promise.all([
      apiFetch<{ items: ProviderConnection[] }>(`/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}/provider-connections?limit=100`),
      apiFetch<{ items: ModelProfile[] }>(`/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}/model-profiles?limit=100`),
      apiFetch<{ items: ModelRoute[] }>(`/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}/model-routes?limit=100`),
      apiFetch<{ items: PromptTemplate[] }>(`/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}/prompt-templates`),
    ]);
    providerConnections.value = providers.items;
    modelProfiles.value = profiles.items;
    modelRoutes.value = routes.items;
    promptTemplates.value = prompts.items;
    if (!selectedRouteId.value || !routes.items.some((item) => item.modelRouteId === selectedRouteId.value)) selectedRouteId.value = routes.items.find((item) => item.purpose === "CHAT" && item.status === "ACTIVE")?.modelRouteId ?? routes.items[0]?.modelRouteId ?? "";
    const route = routes.items.find((item) => item.modelRouteId === selectedRouteId.value);
    if (!selectedProfileId.value || !profiles.items.some((item) => item.modelProfileId === selectedProfileId.value)) selectedProfileId.value = route?.candidates[0]?.modelProfileId ?? profiles.items.find((item) => item.purpose === "CHAT" && item.status === "PUBLISHED")?.modelProfileId ?? "";
    if (!selectedPromptTemplateId.value || !prompts.items.some((item) => item.promptTemplateId === selectedPromptTemplateId.value)) selectedPromptTemplateId.value = prompts.items.find((item) => item.purpose === "CHAT" && item.currentVersion !== null)?.promptTemplateId ?? prompts.items[0]?.promptTemplateId ?? "";
    model.value = model.value || "qwen3.5:9b";
    await loadPromptVersion(prompts.items.find((item) => item.promptTemplateId === selectedPromptTemplateId.value));
  } catch (value) {
    error.value = describeError(value);
  } finally {
    loading.value = false;
  }
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
  emit("start-answer", { routeVersionId: selectedRouteId.value, profileVersionId: selectedProfileId.value, providerConnectionId: selectedProvider.value.providerConnectionId, promptVersionId: promptVersion.value.promptVersionId, model: model.value.trim(), datasetHash: datasetHash.value.trim(), configHash });
}

watch(() => props.selectedSpaceId, () => { if (props.selectedSpaceId) void loadFlow(); }, { immediate: true });
</script>

<template>
  <section class="view-section business-flow" aria-labelledby="business-flow-heading">
    <div class="section-heading"><div><p class="eyebrow">00 · Guided business flow</p><h2 id="business-flow-heading">业务闭环</h2><p>按真实服务端状态完成配置，再进入问答。这里不接受手填不存在的资源，也不会把前端选中状态当成权限依据。</p></div><div class="read-only-note" :class="{ warning: !canStart }">{{ canStart ? "可进入问答" : "尚有步骤未完成" }}</div></div>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <div class="flow-steps"><article class="card flow-step done"><span class="step-number">01</span><div><strong>空间</strong><p>{{ selectedSpaceId ? `当前空间 ${selectedSpaceId}` : "请选择空间" }}</p></div><span class="step-state">{{ selectedSpaceId ? "完成" : "待完成" }}</span></article><article class="card flow-step" :class="{ done: hasPublishedModel }"><span class="step-number">02</span><div><strong>模型路由</strong><p>{{ hasPublishedModel ? `${selectedProvider?.providerType} · ${selectedProfile?.purpose} · ${selectedRoute?.modelRouteId}` : "需要 ACTIVE route、PUBLISHED profile 和 ACTIVE provider" }}</p></div><span class="step-state">{{ hasPublishedModel ? "完成" : "去配置" }}</span></article><article class="card flow-step" :class="{ done: hasPublishedPrompt }"><span class="step-number">03</span><div><strong>Prompt 版本</strong><p>{{ hasPublishedPrompt ? `已发布 v${promptVersion?.version}` : "需要已发布的 Prompt version" }}</p></div><span class="step-state">{{ hasPublishedPrompt ? "完成" : "去配置" }}</span></article><article class="card flow-step" :class="{ done: hasIndexIdentity }"><span class="step-number">04</span><div><strong>数据与索引</strong><p>{{ hasIndexIdentity ? `index ${indexVersionId}` : "需要可追溯的 indexVersionId 与 datasetHash" }}</p></div><span class="step-state">{{ hasIndexIdentity ? "完成" : "待输入" }}</span></article><article class="card flow-step" :class="{ done: canStart }"><span class="step-number">05</span><div><strong>带引用问答</strong><p>{{ canStart ? "运行配置已准备好，可开始回答" : "完成前置步骤后解锁" }}</p></div><span class="step-state">{{ canStart ? "已解锁" : "锁定" }}</span></article></div>

    <div class="flow-layout"><div class="card flow-config"><div class="card-title"><div><span class="card-label">Runtime configuration</span><h3>确认问答运行配置</h3></div><button type="button" class="quiet-button" :disabled="loading" @click="loadFlow">{{ loading ? "读取中…" : "刷新配置" }}</button></div><div class="form-grid"><div class="field wide"><label for="flow-route">ACTIVE Model Route</label><select id="flow-route" v-model="selectedRouteId" @change="selectedProfileId = selectedRoute?.candidates[0]?.modelProfileId ?? ''"><option value="">请选择 route</option><option v-for="item in modelRoutes" :key="item.modelRouteId" :value="item.modelRouteId">{{ item.purpose }} · {{ item.status }} · {{ item.modelRouteId }}</option></select></div><div class="field"><label for="flow-profile">PUBLISHED Model Profile</label><select id="flow-profile" v-model="selectedProfileId"><option value="">请选择 profile</option><option v-for="item in modelProfiles.filter((candidate) => candidate.status === 'PUBLISHED')" :key="item.modelProfileId" :value="item.modelProfileId">{{ item.purpose }} · {{ item.modelProfileId }}</option></select></div><div class="field"><label for="flow-model">模型名</label><input id="flow-model" v-model="model" placeholder="qwen3.5:9b" /></div><div class="field wide"><label for="flow-prompt">PUBLISHED Prompt Template</label><select id="flow-prompt" v-model="selectedPromptTemplateId" @change="refreshPromptVersion"><option value="">请选择 Prompt</option><option v-for="item in promptTemplates" :key="item.promptTemplateId" :value="item.promptTemplateId">{{ item.name }} · {{ item.currentVersion ? `v${item.currentVersion}` : "未发布" }}</option></select></div><div class="field"><label for="flow-index">indexVersionId</label><input id="flow-index" v-model="indexVersionId" placeholder="UUIDv7" /></div><div class="field"><label for="flow-dataset-hash">datasetHash</label><input id="flow-dataset-hash" v-model="datasetHash" maxlength="64" placeholder="64 位 SHA-256" /></div></div><div class="flow-boundary-note"><strong>数据与索引步骤：</strong><span>当前仓库没有数据源/导入/索引 REST API，不能在此伪造上传或自动生成 indexVersionId。填写值必须来自真实已发布索引证据；后端补齐该契约后，这一步才能自动化。</span></div><div class="button-row"><button type="button" :disabled="!canStart" @click="startAnswer">进入带引用问答</button><button type="button" class="secondary-button" @click="emit('open-control', 'providers')">配置 Provider / 模型 / Prompt</button></div></div><aside class="flow-side"><div class="card"><span class="card-label">当前权限</span><h3>{{ currentRole }}</h3><p>配置是否可写由服务端权限裁决。当前向导只读取真实状态，不绕过 VIEWER 或跨空间访问。</p></div><div class="card"><span class="card-label">已读取资源</span><ul class="flow-resource-list"><li>{{ providerConnections.length }} 个 Provider connection</li><li>{{ modelProfiles.length }} 个 Model Profile</li><li>{{ modelRoutes.length }} 个 Model Route</li><li>{{ promptTemplates.length }} 个 Prompt template</li></ul></div></aside></div>
  </section>
</template>

<style scoped>
.business-flow { margin-top: 26px; }.flow-steps { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }.flow-step { position: relative; min-height: 146px; padding: 15px; }.flow-step.done { border-color: #9ccfb2; background: #fbfffc; }.step-number { display: inline-grid; width: 30px; height: 30px; place-items: center; margin-bottom: 15px; border-radius: 9px; background: #e8eef8; color: #315b97; font-size: .7rem; font-weight: 900; }.flow-step.done .step-number { background: #ddf2e5; color: #1b754d; }.flow-step strong { display: block; color: #284572; }.flow-step p { min-height: 38px; margin: 7px 0 12px; color: #71809a; font-size: .75rem; line-height: 1.45; overflow-wrap: anywhere; }.step-state { color: #8794a8; font-size: .7rem; font-weight: 800; }.flow-step.done .step-state { color: #1b754d; }.flow-layout { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(260px, .55fr); gap: 15px; margin-top: 15px; }.flow-config .card-title { align-items: flex-start; margin-bottom: 20px; }.flow-config .form-grid { margin-bottom: 18px; }.flow-boundary-note { display: flex; gap: 8px; margin: 16px 0; padding: 12px 14px; border: 1px dashed #e0bb76; border-radius: 10px; background: #fffaf0; color: #80632e; font-size: .78rem; line-height: 1.5; }.flow-boundary-note strong { color: #684b17; }.flow-side { display: grid; align-content: start; gap: 15px; }.flow-side p { margin: 9px 0 0; color: #687893; font-size: .82rem; line-height: 1.55; }.flow-resource-list { display: grid; gap: 10px; margin: 12px 0 0; padding-left: 18px; color: #526b92; font-size: .82rem; line-height: 1.4; }
@media (max-width: 1050px) { .flow-steps { grid-template-columns: repeat(3, minmax(0, 1fr)); }.flow-layout { grid-template-columns: 1fr; }.flow-side { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .flow-steps, .flow-side { grid-template-columns: 1fr; }.flow-step { min-height: auto; }.flow-step p { min-height: auto; }.flow-boundary-note { flex-direction: column; } }
</style>
