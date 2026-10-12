<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ApiError, type Anchor, type ChunkOverrideResponse, type ChunkOverrideTargetState, type ChunkStudioProjection, type CreateChunkOverrideRequest, type ProvenanceContext, type TransitionChunkOverrideRequest, apiFetch, lookupChunkStudio } from "../../api";
import { formatDateTime } from "../../format";
import { stateLabel } from "../../i18n";

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
const showIds = ref(false);
const isViewer = computed(() => props.currentRole === "VIEWER");
const canEdit = computed(() => !isViewer.value && Boolean(props.selectedSpaceId));
const fromCitation = computed(() => props.initialContext?.target === "studio" && Boolean(props.initialContext.childChunkId));

function path(value: string): string { return encodeURIComponent(value.trim()); }
function explain(errorValue: unknown, fallback: string): string {
  if (!(errorValue instanceof ApiError)) return fallback;
  if (errorValue.status === 403) return "当前角色或空间权限不允许此操作。";
  if (errorValue.status === 404) return "这条来源不属于当前空间，或引用已失效。";
  return errorValue.problem?.detail ?? errorValue.message;
}
function formatAnchor(anchor: Anchor): string {
  const parts = [anchor.headingPath.join(" / ")];
  if (anchor.pageNumber) parts.push(`第 ${anchor.pageNumber} 页`);
  if (anchor.sheet) parts.push(`工作表 ${anchor.sheet}`);
  if (anchor.slideNumber) parts.push(`第 ${anchor.slideNumber} 张幻灯片`);
  if (anchor.lineRange) parts.push(`行 ${anchor.lineRange.startLine}–${anchor.lineRange.endLine}`);
  if (anchor.tableCell) parts.push(`单元格 ${anchor.tableCell}`);
  return parts.filter(Boolean).join(" · ") || "未提供位置";
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
  if (!props.selectedSpaceId) { error.value = "请先选择空间。"; return; }
  if (!fromCitation.value && !childChunkId.value.trim()) {
    error.value = "请从问答的引用打开看来源，不要手填内部编号。";
    return;
  }
  loading.value = true;
  try {
    const context = props.initialContext;
    const value = context?.target === "studio" && context.spaceId === props.selectedSpaceId && context.childChunkId && context.documentRevisionId && context.contentRef && context.textHash
      ? await lookupChunkStudio(props.selectedSpaceId, context)
      : await apiFetch<ChunkStudioProjection>(`/api/v1/spaces/${path(props.selectedSpaceId)}/chunk-studio/children/${path(childChunkId.value)}`);
    applyProjection(value);
  } catch (errorValue) { projection.value = null; error.value = explain(errorValue, "看来源失败。"); }
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
  if (!projection.value || !canEdit.value) { error.value = isViewer.value ? "只读成员不能修订。" : "请先打开一条来源。"; return; }
  if (!validContentRef(replacementContentRef.value)) { error.value = "替换引用不合法。"; return; }
  if (!validHash(replacementTextHash.value)) { error.value = "摘要必须是 64 位十六进制。"; return; }
  if (!overrideReason.value.trim()) { error.value = "请填写修订原因。"; return; }
  loading.value = true;
  try {
    const body: CreateChunkOverrideRequest = { documentRevisionId: projection.value.documentRevisionId, contentRef: replacementContentRef.value, textHash: replacementTextHash.value, reason: overrideReason.value.trim() };
    applyOverride(await apiFetch<ChunkOverrideResponse>(`/api/v1/spaces/${path(props.selectedSpaceId)}/chunk-studio/children/${path(projection.value.childChunkId)}/overrides`, { method: "POST", body }));
    notice.value = "修订已创建。"; overrideReason.value = "";
  } catch (errorValue) { error.value = explain(errorValue, "修订创建失败。"); }
  finally { loading.value = false; }
}
async function transitionOverride(targetState: ChunkOverrideTargetState): Promise<void> {
  error.value = ""; notice.value = "";
  const current = projection.value?.override;
  if (!projection.value || !current?.overrideId || !canEdit.value) { error.value = isViewer.value ? "只读成员不能修订。" : "当前没有可流转的修订。"; return; }
  if (!transitionReason.value.trim()) { error.value = "状态流转需要填写原因。"; return; }
  loading.value = true;
  try {
    const body: TransitionChunkOverrideRequest = { targetState, expectedVersion: current.version, reason: transitionReason.value.trim() };
    applyOverride(await apiFetch<ChunkOverrideResponse>(`/api/v1/spaces/${path(props.selectedSpaceId)}/chunk-studio/children/${path(projection.value.childChunkId)}/overrides/${path(current.overrideId)}/transitions`, { method: "POST", body }));
    notice.value = targetState === "ACTIVE" ? "已恢复生效。" : targetState === "DISCARDED" ? "已丢弃修订。" : "已标记复核。";
    transitionReason.value = "";
  } catch (errorValue) { error.value = explain(errorValue, "修订状态流转失败。"); }
  finally { loading.value = false; }
}
watch(() => props.initialContext, (context) => {
  if (context?.target === "studio" && context.childChunkId) { childChunkId.value = context.childChunkId; void loadChunk(); }
}, { immediate: true });
onMounted(() => { if (!projection.value && childChunkId.value) void loadChunk(); });
</script>

<template>
  <section class="view-section" role="tabpanel" aria-labelledby="chunk-studio-heading">
    <div class="section-heading"><div><p class="eyebrow">证据</p><h2 id="chunk-studio-heading">看来源</h2><p>从问答里的引用点进来，核对资料位置是否已入索引。本页不展示正文。</p></div><div class="read-only-note" :class="{ warning: isViewer }">{{ isViewer ? "只读成员：查看模式" : "可核对来源并做人工修订" }}</div></div>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <template v-if="projection">
      <div class="two-column">
        <article class="card detail-card">
          <div class="card-title"><h3>来源</h3><span class="tag">{{ projection.provenance.sourcePath || "当前空间资料" }}</span></div>
          <dl class="details">
            <dt>文件</dt><dd>{{ projection.provenance.sourcePath || "—" }}</dd>
            <dt>修订</dt><dd>第 {{ projection.provenance.revisionVersion }} 版</dd>
            <dt>在文中的位置</dt><dd>{{ formatAnchor(projection.anchor) }}</dd>
            <dt>片段关系</dt><dd>{{ projection.parentChild.relationship }} · 第 {{ projection.parentChild.childIndex }} 段</dd>
          </dl>
        </article>
        <article class="card detail-card">
          <div class="card-title"><h3>索引状态</h3><span class="state-pill" :class="projection.vectorStatus.state.toLowerCase()">{{ stateLabel(projection.vectorStatus.state) }}</span></div>
          <dl class="details">
            <dt>是否已入索引</dt><dd>{{ projection.vectorStatus.indexVersionId ? "已写入当前索引" : "尚未入索引" }}</dd>
            <dt>向量维度</dt><dd>{{ projection.vectorStatus.vectorDimension ?? "—" }}</dd>
            <dt>更新时间</dt><dd>{{ formatDateTime(projection.vectorStatus.updatedAt) }}</dd>
            <dt>修订状态</dt><dd>{{ stateLabel(projection.override.state) }}</dd>
          </dl>
        </article>
      </div>
      <details class="card">
        <summary>人工修订（覆盖错误切分）</summary>
        <div class="two-column action-grid">
          <form class="card action-card" @submit.prevent="createOverride">
            <p class="muted">只提交外部已存储内容的引用和摘要，正文不会进入浏览器。</p>
            <div class="field"><label for="override-reason">修订原因</label><textarea id="override-reason" v-model="overrideReason" rows="3" maxlength="2000"></textarea></div>
            <button type="submit" :disabled="loading || !canEdit">创建修订</button>
          </form>
          <div class="card action-card">
            <div class="field"><label for="transition-reason">流转原因</label><textarea id="transition-reason" v-model="transitionReason" rows="3" maxlength="2000"></textarea></div>
            <div class="button-row">
              <button type="button" :disabled="loading || !canEdit || projection.override.state !== 'NEEDS_REVIEW'" @click="transitionOverride('ACTIVE')">恢复生效</button>
              <button type="button" class="danger-button" :disabled="loading || !canEdit || projection.override.state !== 'NEEDS_REVIEW'" @click="transitionOverride('DISCARDED')">丢弃</button>
              <button type="button" class="secondary-button" :disabled="loading || !canEdit || projection.override.state !== 'ACTIVE'" @click="transitionOverride('NEEDS_REVIEW')">标记复核</button>
            </div>
          </div>
        </div>
      </details>
      <details class="card" :open="showIds">
        <summary @click.prevent="showIds = !showIds">高级：内部标识</summary>
        <p class="muted">子片段 <code>{{ projection.childChunkId }}</code> · 父片段 <code>{{ projection.parentChunkId }}</code></p>
        <p class="muted">内容引用 <code>{{ projection.contentRef }}</code></p>
      </details>
    </template>
    <div v-else class="empty-state card">
      <strong>从问答里的引用打开</strong>
      <span>点一条回答中的引用来源，就会带上位置信息到这里。不要手填内部编号。</span>
    </div>
  </section>
</template>
