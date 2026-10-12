<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ApiError, type Anchor, type ChunkOverrideResponse, type ChunkOverrideTargetState, type ChunkStudioProjection, type CreateChunkOverrideRequest, type ProvenanceContext, type TransitionChunkOverrideRequest, apiFetch, lookupChunkStudio } from "../../api";
import { formatDateTime } from "../../format";
import { roleLabel, stateLabel } from "../../i18n";

const props = defineProps<{ selectedSpaceId: string; currentRole: string; initialContext?: ProvenanceContext | null }>();
const childChunkId = ref("");
const projection = ref<ChunkStudioProjection | null>(null);
const loading = ref(false);
const error = ref("");
const notice = ref("");
const replacementContentRef = ref("");
const replacementTextHash = ref("");
const overrideReason = ref("");
const transitionReason = ref("");
const isViewer = computed(() => props.currentRole === "VIEWER");
const canEdit = computed(() => !isViewer.value && Boolean(props.selectedSpaceId));

function path(value: string): string { return encodeURIComponent(value.trim()); }
function explain(errorValue: unknown, fallback: string): string {
  if (!(errorValue instanceof ApiError)) return fallback;
  if (errorValue.status === 403) return "当前角色或空间权限不允许此操作。";
  if (errorValue.status === 404) return "资源不存在，或 provenance context 不属于当前空间。";
  return errorValue.problem?.detail ?? errorValue.message;
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
function applyProjection(value: ChunkStudioProjection): void {
  projection.value = value;
  childChunkId.value = value.childChunkId;
  replacementContentRef.value = value.contentRef;
  replacementTextHash.value = value.textHash;
  overrideReason.value = "";
  transitionReason.value = "";
}
async function loadChunk(): Promise<void> {
  error.value = ""; notice.value = "";
  if (!props.selectedSpaceId || !childChunkId.value.trim()) { error.value = "需要当前空间和 childChunkId，或从 provenance context 进入。"; return; }
  loading.value = true;
  try {
    const context = props.initialContext;
    const value = context?.target === "studio" && context.spaceId === props.selectedSpaceId && context.childChunkId && context.documentRevisionId && context.contentRef && context.textHash
      ? await lookupChunkStudio(props.selectedSpaceId, context)
      : await apiFetch<ChunkStudioProjection>(`/api/v1/spaces/${path(props.selectedSpaceId)}/chunk-studio/children/${path(childChunkId.value)}`);
    applyProjection(value);
  } catch (errorValue) { projection.value = null; error.value = explain(errorValue, "Chunk Studio projection 加载失败。"); }
  finally { loading.value = false; }
}
function applyOverride(value: ChunkOverrideResponse): void {
  if (projection.value) projection.value = { ...projection.value, contentRef: value.contentRef, textHash: value.textHash, override: value.override };
  replacementContentRef.value = value.contentRef; replacementTextHash.value = value.textHash;
}
function validContentRef(value: string): boolean { return Boolean(value) && value.length <= 512 && !/[\s\u0000-\u001f\u007f]/.test(value) && !/(?:full|raw|document)[_-]?(?:text|document|content)|embedding|vector/i.test(value); }
function validHash(value: string): boolean { return /^[0-9a-fA-F]{64}$/.test(value); }
async function createOverride(): Promise<void> {
  error.value = ""; notice.value = "";
  if (!projection.value || !canEdit.value) { error.value = isViewer.value ? "VIEWER 无写权限。" : "请先读取 projection。"; return; }
  if (!validContentRef(replacementContentRef.value)) { error.value = "contentRef 必须是非敏感 opaque reference。"; return; }
  if (!validHash(replacementTextHash.value)) { error.value = "textHash 必须是 64 位 SHA-256 十六进制值。"; return; }
  if (!overrideReason.value.trim()) { error.value = "请填写 override reason。"; return; }
  loading.value = true;
  try {
    const body: CreateChunkOverrideRequest = { documentRevisionId: projection.value.documentRevisionId, contentRef: replacementContentRef.value, textHash: replacementTextHash.value, reason: overrideReason.value.trim() };
    applyOverride(await apiFetch<ChunkOverrideResponse>(`/api/v1/spaces/${path(props.selectedSpaceId)}/chunk-studio/children/${path(projection.value.childChunkId)}/overrides`, { method: "POST", body }));
    notice.value = "override 已创建。"; overrideReason.value = "";
  } catch (errorValue) { error.value = explain(errorValue, "override 创建失败。"); }
  finally { loading.value = false; }
}
async function transitionOverride(targetState: ChunkOverrideTargetState): Promise<void> {
  error.value = ""; notice.value = "";
  const current = projection.value?.override;
  if (!projection.value || !current?.overrideId || !canEdit.value) { error.value = isViewer.value ? "VIEWER 无写权限。" : "当前没有可流转的 override。"; return; }
  if (!transitionReason.value.trim()) { error.value = "状态流转需要填写审计 reason。"; return; }
  loading.value = true;
  try {
    const body: TransitionChunkOverrideRequest = { targetState, expectedVersion: current.version, reason: transitionReason.value.trim() };
    applyOverride(await apiFetch<ChunkOverrideResponse>(`/api/v1/spaces/${path(props.selectedSpaceId)}/chunk-studio/children/${path(projection.value.childChunkId)}/overrides/${path(current.overrideId)}/transitions`, { method: "POST", body }));
    notice.value = `override 已迁移为 ${targetState}。`; transitionReason.value = "";
  } catch (errorValue) { error.value = explain(errorValue, "override 状态流转失败。"); }
  finally { loading.value = false; }
}
watch(() => props.initialContext, (context) => {
  if (context?.target === "studio" && context.childChunkId) { childChunkId.value = context.childChunkId; void loadChunk(); }
}, { immediate: true });
onMounted(() => { if (!projection.value && childChunkId.value) void loadChunk(); });
</script>

<template>
  <section class="view-section" role="tabpanel" aria-labelledby="chunk-studio-heading">
    <div class="section-heading"><div><p class="eyebrow">01 · 内容证据</p><h2 id="chunk-studio-heading">内容证据</h2><p>读取可审计 来源证据；本页不渲染正文、原文或向量。</p></div><div class="read-only-note" :class="{ warning: isViewer }">{{ isViewer ? "只读成员：查看模式" : "写操作由 API 权限最终裁决" }}</div></div>
    <form class="card lookup-form" @submit.prevent="loadChunk"><div class="field wide"><label for="child-chunk-id">子分块标识</label><input id="child-chunk-id" v-model="子分块标识" autocomplete="off" placeholder="从文档、修订版本、引用证据 或检索命中打开" /></div><button type="submit" :disabled="loading || !selectedSpaceId">{{ loading ? "读取中…" : "读取证据" }}</button></form>
    <p class="helper">普通跳转会携带并恢复 provenance context；当前空间与服务端重新授权始终优先。</p>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <template v-if="projection">
      <div class="identity-grid"><article class="card"><span class="card-label">空间 / 修订版本</span><code>{{ projection.spaceId }}</code><code>{{ projection.documentRevisionId }}</code></article><article class="card"><span class="card-label">分块引用关系</span><span>子分块 <code>{{ projection.childChunkId }}</code></span><span>父分块 <code>{{ projection.parentChunkId }}</code></span></article><article class="card"><span class="card-label">内容引用</span><code>{{ projection.contentRef }}</code><span class="muted">正文摘要：{{ projection.textHash }}</span></article></div>
      <div class="two-column"><article class="card detail-card"><div class="card-title"><h3>来源证据与父子关系</h3><span class="tag">只读元数据</span></div><dl class="details"><dt>来源标识</dt><dd><code>{{ projection.provenance.sourceId }}</code></dd><dt>文档标识</dt><dd><code>{{ projection.provenance.documentId }}</code></dd><dt>来源路径</dt><dd>{{ projection.provenance.sourcePath }}</dd><dt>修订版本</dt><dd>{{ projection.provenance.revisionVersion }}</dd><dt>关系</dt><dd>{{ projection.parentChild.relationship }} · 子分块序号 {{ projection.parentChild.childIndex }}</dd><dt>父内容引用</dt><dd><code>{{ projection.parentChild.parentContentRef }}</code></dd></dl></article><article class="card detail-card"><div class="card-title"><h3>引用锚点</h3><span class="tag">允许列表</span></div><p class="anchor-value">{{ formatAnchor(projection.anchor) }}</p><p class="muted">锚点只表示位置，不包含引用摘录。</p></article></div>
      <div class="two-column"><article class="card detail-card"><div class="card-title"><h3>向量与索引状态</h3><span class="state-pill" :class="projection.vectorStatus.state.toLowerCase()">{{ stateLabel(projection.vectorStatus.state) }}</span></div><dl class="details"><dt>索引版本标识</dt><dd><code>{{ projection.vectorStatus.indexVersionId ?? "—" }}</code></dd><dt>向量维度</dt><dd>{{ projection.vectorStatus.vectorDimension ?? "—" }} <span class="muted">（仅维度元数据）</span></dd><dt>更新时间</dt><dd>{{ formatDateTime(projection.vectorStatus.updatedAt) }}</dd></dl></article><article class="card detail-card"><div class="card-title"><h3>覆盖修订审计摘要</h3><span class="state-pill" :class="projection.override.state.toLowerCase()">{{ stateLabel(projection.override.state) }}</span></div><dl class="details"><dt>修订标识</dt><dd><code>{{ projection.override.overrideId ?? "—" }}</code></dd><dt>version</dt><dd>{{ projection.override.version }}</dd><dt>reason</dt><dd>{{ projection.override.reason ?? "—" }}</dd><dt>创建人</dt><dd><code>{{ projection.override.createdBy ?? "—" }}</code></dd><dt>createdAt / 更新时间</dt><dd>{{ formatDateTime(projection.override.createdAt) }} / {{ formatDateTime(projection.override.updatedAt) }}</dd></dl></article></div>
      <div class="two-column action-grid"><form class="card action-card" @submit.prevent="createOverride"><div class="card-title"><h3>创建人工修订</h3><span class="tag">服务端审计</span></div><p class="muted">客户端只提交 不透明内容引用、SHA-256 摘要和原因；正文不进入客户端。</p><div class="field"><label for="replacement-content-ref">替换内容引用</label><input id="replacement-content-ref" v-model="replacementContentRef" maxlength="512" spellcheck="false" required /></div><div class="field"><label for="replacement-text-hash">替换正文摘要</label><input id="replacement-text-hash" v-model="replacementTextHash" maxlength="64" minlength="64" spellcheck="false" required /></div><div class="field"><label for="override-reason">创建原因</label><textarea id="override-reason" v-model="overrideReason" rows="3" maxlength="2000"></textarea></div><button type="submit" :disabled="loading || !canEdit">{{ loading ? "提交中…" : "创建修订" }}</button></form><div class="card action-card"><div class="card-title"><h3>冲突与状态流转</h3></div><p class="muted">仅允许 生效 ↔ 待复核，或待复核 → 丢弃。</p><div class="field"><label for="transition-reason">流转原因</label><textarea id="transition-reason" v-model="transitionReason" rows="3" maxlength="2000"></textarea></div><div class="button-row"><button type="button" :disabled="loading || !canEdit || projection.override.state !== 'NEEDS_REVIEW'" @click="transitionOverride('ACTIVE')">恢复生效</button><button type="button" class="danger-button" :disabled="loading || !canEdit || projection.override.state !== 'NEEDS_REVIEW'" @click="transitionOverride('丢弃')">丢弃</button><button type="button" class="secondary-button" :disabled="loading || !canEdit || projection.override.state !== 'ACTIVE'" @click="transitionOverride('NEEDS_REVIEW')">标记复核</button></div></div></div>
    </template><div v-else class="empty-state card"><strong>尚未读取 projection</strong><span>从带 来源证据 的文档、修订版本、引用证据 或检索命中进入，或输入 子分块标识。</span></div>
  </section>
</template>
