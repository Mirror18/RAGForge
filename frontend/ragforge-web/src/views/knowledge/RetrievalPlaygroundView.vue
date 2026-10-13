<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import {
  ApiError,
  getAnswerReadiness,
  getPlaygroundDefaults,
  listIndexes,
  type Anchor,
  type AnswerReadiness,
  type IndexView,
  type PlaygroundDefaults,
  type ProvenanceContext,
  type RetrievalExperiment,
  apiFetch,
} from "../../api";
import { resolvePlaygroundPrefill, prefillAfterSpaceChange } from "../../studio-prefill";

const props = defineProps<{ selectedSpaceId: string; initialContext?: ProvenanceContext | null }>();
const emit = defineEmits<{ "open-context": [context: ProvenanceContext]; "follow-gap": [path: string] }>();
const query = ref("");
const defaults = ref<PlaygroundDefaults | null>(null);
const readiness = ref<AnswerReadiness | null>(null);
const indexes = ref<IndexView[]>([]);
const selectedIndexVersionId = ref("");
const loading = ref(false);
const error = ref("");
const experiment = ref<RetrievalExperiment | null>(null);
const traceStages = [
  { key: "dense" as const, title: "向量召回" },
  { key: "bm25" as const, title: "关键词召回" },
  { key: "rrf" as const, title: "融合" },
  { key: "rerank" as const, title: "重排" },
];
const effectiveDefaults = computed<PlaygroundDefaults | null>(() => {
  if (!defaults.value) return null;
  const indexVersionId = selectedIndexVersionId.value || defaults.value.indexVersionId;
  return { ...defaults.value, indexVersionId };
});
const guide = computed(() =>
  resolvePlaygroundPrefill({
    spaceId: props.selectedSpaceId,
    defaults: effectiveDefaults.value,
    gaps: readiness.value?.gaps ?? [],
  }),
);
const usingActive = computed(() => guide.value.kind === "ready");
const historicalIndexes = computed(() =>
  indexes.value.filter((item) => item.indexVersionId !== defaults.value?.indexVersionId),
);

function path(value: string): string {
  return encodeURIComponent(value.trim());
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
function explain(value: unknown): string {
  if (!(value instanceof ApiError)) return "试检索失败。";
  return value.status === 403
    ? "当前空间权限不允许此操作。"
    : value.status === 404
      ? "当前索引或检索档不属于这个空间。"
      : (value.problem?.detail ?? value.message);
}

async function loadDefaults(): Promise<void> {
  if (!props.selectedSpaceId) {
    defaults.value = null;
    readiness.value = null;
    indexes.value = [];
    selectedIndexVersionId.value = "";
    return;
  }
  error.value = "";
  try {
    const [active, spaceReadiness, indexPage] = await Promise.all([
      getPlaygroundDefaults(props.selectedSpaceId),
      getAnswerReadiness(props.selectedSpaceId).catch(() => null),
      listIndexes(props.selectedSpaceId, { limit: 50 }).catch(() => ({ items: [], nextCursor: null })),
    ]);
    readiness.value = spaceReadiness;
    indexes.value = indexPage.items;
    const context = props.initialContext;
    defaults.value =
      context?.target === "playground" && context.indexVersionId && context.profileId
        ? {
            indexVersionId: context.indexVersionId,
            profileId: context.profileId,
            profileVersion: context.profileVersion ?? active?.profileVersion ?? 1,
          }
        : active;
    selectedIndexVersionId.value = defaults.value?.indexVersionId ?? "";
  } catch (value) {
    defaults.value = null;
    selectedIndexVersionId.value = "";
    error.value = explain(value);
  }
}

async function runExperiment(): Promise<void> {
  error.value = "";
  experiment.value = null;
  if (!props.selectedSpaceId || !query.value.trim()) {
    error.value = "请输入要试检索的问题。";
    return;
  }
  if (!effectiveDefaults.value) {
    error.value = guide.value.hint;
    return;
  }
  loading.value = true;
  try {
    experiment.value = await apiFetch<RetrievalExperiment>(
      `/api/v1/spaces/${path(props.selectedSpaceId)}/retrieval-playground/experiments`,
      {
        method: "POST",
        body: {
          query: query.value.trim(),
          indexVersionId: effectiveDefaults.value.indexVersionId,
          profileA: {
            profileId: effectiveDefaults.value.profileId,
            version: effectiveDefaults.value.profileVersion,
            candidateOnly: true,
          },
          profileB: null,
        },
      },
    );
  } catch (value) {
    error.value = explain(value);
  } finally {
    loading.value = false;
  }
}

function openHit(hit: {
  childChunkId: string;
  documentRevisionId: string;
  contentRef: string;
  textHash: string;
}): void {
  emit("open-context", {
    target: "studio",
    spaceId: props.selectedSpaceId,
    childChunkId: hit.childChunkId,
    documentRevisionId: hit.documentRevisionId,
    contentRef: hit.contentRef,
    textHash: hit.textHash,
  });
}

watch(
  () => props.selectedSpaceId,
  (next, previous) => {
    const change = prefillAfterSpaceChange(previous ?? "", next);
    if (change.clearCitation) {
      experiment.value = null;
      query.value = "";
    }
    if (change.reload || !previous) void loadDefaults();
  },
  { immediate: true },
);
watch(
  () => props.initialContext,
  () => {
    void loadDefaults();
  },
);
onMounted(() => {
  void loadDefaults();
});
</script>

<template>
  <section class="view-section" role="tabpanel" aria-labelledby="retrieval-playground-heading">
    <div class="section-heading">
      <div>
        <p class="eyebrow">检索</p>
        <h2 id="retrieval-playground-heading">试检索</h2>
        <p>用当前空间正在使用的索引试搜。结果可点开看来源，不会改变问答正在用的配置。</p>
      </div>
      <div class="read-only-note">{{ usingActive ? "已使用当前索引" : "还没有可试检索的索引" }}</div>
    </div>
    <p v-if="guide.kind === 'needs_setup'" class="alert error" role="status">
      {{ guide.hint }}
      <button
        v-if="guide.nextStep"
        type="button"
        class="quiet-button"
        @click="emit('follow-gap', guide.nextStep.path)"
      >
        {{ guide.nextStep.label }}
      </button>
    </p>
    <form class="card experiment-form" @submit.prevent="runExperiment">
      <div class="field wide">
        <label for="query">问题</label>
        <textarea
          id="query"
          v-model="query"
          rows="3"
          maxlength="10000"
          placeholder="输入一句话，看看知识库会命中什么"
        ></textarea>
      </div>
      <p class="muted">{{ guide.hint }}</p>
      <details v-if="usingActive && (historicalIndexes.length || selectedIndexVersionId)" class="advanced-config">
        <summary>高级：改选历史索引版本</summary>
        <div class="field">
          <label for="playground-index">索引版本</label>
          <select id="playground-index" v-model="selectedIndexVersionId">
            <option v-if="defaults" :value="defaults.indexVersionId">当前活跃索引</option>
            <option v-for="item in historicalIndexes" :key="item.indexVersionId" :value="item.indexVersionId">
              v{{ item.versionNo }} · {{ item.state }}
            </option>
          </select>
        </div>
        <p class="muted">检索档仍使用当前空间活跃版本；改选仅影响本次试检索。</p>
      </details>
      <div class="form-actions">
        <button type="submit" :disabled="loading || !selectedSpaceId || !usingActive">
          {{ loading ? "检索中…" : "试检索" }}
        </button>
      </div>
    </form>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <div v-if="experiment" class="results" aria-live="polite">
      <div class="result-summary card">
        <div>
          <span class="card-label">检索完成</span>
          <p>问题：{{ experiment.query }}</p>
        </div>
        <div class="safe-result">
          <span>当前配置</span>
          <strong>{{ experiment.activeProfileUnchanged ? "未改动" : "异常" }}</strong>
        </div>
      </div>
      <div class="side-grid">
        <article v-if="experiment.profileA" class="side-panel">
          <div class="side-header"><h3>命中结果</h3><span>只读实验</span></div>
          <div v-for="stage in traceStages" :key="stage.key" class="trace-block">
            <div class="trace-title">
              <h4>{{ stage.title }}</h4>
              <span
                >{{ experiment.profileA.trace[stage.key].metrics.candidateCount }} 条 ·
                {{ experiment.profileA.trace[stage.key].metrics.latencyMs }} ms</span
              >
            </div>
            <div
              v-for="hit in experiment.profileA.trace[stage.key].items"
              :key="`${stage.key}-${hit.childChunkId}-${hit.rank}`"
              class="hit-row"
            >
              <span class="rank">#{{ hit.rank }}</span>
              <div><strong>命中 {{ hit.rank }}</strong><small>相关分 {{ hit.score }}</small></div>
              <button type="button" class="quiet-button" @click="openHit(hit)">看来源</button>
            </div>
          </div>
          <div class="trace-block evidence-block">
            <div class="trace-title"><h4>可引用证据</h4></div>
            <div v-for="item in experiment.profileA.trace.evidence.items" :key="item.evidenceId" class="evidence-row">
              <span>{{ formatAnchor(item.anchor) }}</span>
              <button type="button" class="quiet-button" @click="openHit(item)">看来源</button>
            </div>
          </div>
        </article>
      </div>
    </div>
    <div v-else class="empty-state card">
      <strong>输入问题后试检索</strong>
      <span>适合在提问前确认知识库能不能召回相关片段。</span>
    </div>
  </section>
</template>
