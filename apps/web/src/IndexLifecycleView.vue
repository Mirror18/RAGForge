<script setup lang="ts">
import { computed } from "vue";
import { formatDateTime } from "./format";
import type { ActiveIndexView, IndexView } from "./api";

const props = defineProps<{
  indexes: IndexView[];
  activeIndex: ActiveIndexView | null;
  canManage: boolean;
  busyIndexId: string;
}>();

const emit = defineEmits<{
  publish: [index: IndexView];
  rollback: [indexVersionId: string];
  retire: [index: IndexView];
}>();

const current = computed(() => props.activeIndex?.pointer.activeIndexVersionId ?? null);
const previous = computed(() => props.activeIndex?.pointer.previousIndexVersionId ?? null);
const previousIndex = computed(() => props.indexes.find((item) => item.indexVersionId === previous.value) ?? null);

const stateLabels: Record<string, string> = {
  BUILDING: "candidate · 构建中",
  VALIDATING: "candidate · 验证中",
  READY: "candidate · 可发布",
  ACTIVE: "active · 已激活",
  RETIRED: "retired · 已退役",
  FAILED: "candidate · 验证失败",
};

function stateLabel(state: string): string {
  return stateLabels[state] ?? `candidate · ${state}`;
}

function validationLabel(value: boolean | null): string {
  return value === true ? "通过" : value === false ? "失败" : "未记录";
}

function isCurrent(index: IndexView): boolean {
  return index.indexVersionId === current.value;
}

function isPrevious(index: IndexView): boolean {
  return index.indexVersionId === previous.value;
}

function canRetire(index: IndexView): boolean {
  return props.canManage && index.state === "ACTIVE" && !isCurrent(index);
}
</script>

<template>
  <div class="index-lifecycle" aria-labelledby="index-lifecycle-heading">
    <div class="index-lifecycle-heading">
      <div>
        <label id="index-lifecycle-heading">索引生命周期</label>
        <p class="muted">candidate 先构建并验证，只有 READY candidate 可以发布为 active；旧版本可回滚或退役。</p>
      </div>
      <div class="pointer-summary">
        <span>current pointer</span>
        <strong>{{ activeIndex?.pointer.activeIndexVersionId || "—" }}</strong>
        <small>pointer v{{ activeIndex?.pointer.versionNo ?? "—" }}</small>
      </div>
    </div>

    <div class="pointer-grid">
      <div><span>当前 active</span><strong>{{ current ? `v${indexes.find((item) => item.indexVersionId === current)?.versionNo ?? "?"}` : "—" }}</strong><small>{{ current || "尚未发布" }}</small></div>
      <div><span>previous pointer</span><strong>{{ previous ? `v${previousIndex?.versionNo ?? "?"}` : "—" }}</strong><small>{{ previous || "没有上一版" }}</small></div>
      <button v-if="previous && canManage" type="button" class="quiet-button" :disabled="busyIndexId === previous" @click="emit('rollback', previous)">
        {{ busyIndexId === previous ? "回滚中…" : "回滚上一版" }}
      </button>
    </div>

    <div v-if="indexes.length" class="index-lifecycle-list">
      <article v-for="item in indexes" :key="item.indexVersionId" class="lifecycle-row" :class="{ current: isCurrent(item), previous: isPrevious(item) }">
        <div class="lifecycle-main">
          <div class="lifecycle-title"><strong>v{{ item.versionNo }}</strong><span class="state-pill" :class="item.state.toLowerCase()">{{ stateLabel(item.state) }}</span><span v-if="isCurrent(item)" class="pointer-pill">current pointer</span><span v-if="isPrevious(item)" class="pointer-pill previous-pill">previous pointer</span></div>
          <small class="lifecycle-id">{{ item.indexVersionId }}</small>
          <div class="evidence-grid">
            <span>构建依据：{{ item.documentRevisionCount }} revisions · {{ item.childChunkCount }} child chunks</span>
            <span>验证依据：{{ item.validationVectorDimension ?? "—" }} dimensions · sample retrieval {{ validationLabel(item.sampleRetrievalPassed) }} · space filter {{ validationLabel(item.spaceFilterPassed) }}</span>
            <span>配置：{{ item.embeddingProfileVersion }} · {{ item.chunkingStrategyVersion }}</span>
          </div>
          <small v-if="item.activatedAt" class="muted">激活于 {{ formatDateTime(item.activatedAt) }}</small>
        </div>
        <div class="lifecycle-actions">
          <button v-if="item.state === 'READY'" type="button" class="quiet-button" :disabled="!canManage || busyIndexId === item.indexVersionId" @click="emit('publish', item)">
            {{ busyIndexId === item.indexVersionId ? "发布中…" : "发布为 active" }}
          </button>
          <button v-if="canRetire(item)" type="button" class="quiet-button danger-button" :disabled="busyIndexId === item.indexVersionId" @click="emit('retire', item)">
            {{ busyIndexId === item.indexVersionId ? "退役中…" : "退役此版本" }}
          </button>
        </div>
      </article>
    </div>
    <p v-else class="muted">尚未生成 candidate index。</p>
  </div>
</template>

<style scoped>
.index-lifecycle { display: grid; gap: 13px; width: 100%; margin-top: 15px; padding: 18px; border: 1px solid #dce8f6; border-radius: 13px; background: #fff; }.index-lifecycle-heading { display: flex; align-items: start; justify-content: space-between; gap: 18px; }.index-lifecycle-heading label { color: #405f8b; font-size: .74rem; font-weight: 900; letter-spacing: .08em; text-transform: uppercase; }.index-lifecycle-heading .muted { margin: 6px 0 0; }.pointer-summary { min-width: 190px; padding: 10px 12px; border: 1px solid #cfe0f1; border-radius: 9px; background: #f5faff; text-align: right; }.pointer-summary span, .pointer-summary small, .pointer-grid span, .pointer-grid small { display: block; color: #70819b; font-size: .7rem; }.pointer-summary strong { display: block; margin: 3px 0; color: #225887; font-size: .76rem; overflow-wrap: anywhere; }.pointer-grid { display: grid; grid-template-columns: 1fr 1fr auto; align-items: center; gap: 10px; padding: 11px; border: 1px solid #dce8f6; border-radius: 10px; background: #fbfdff; }.pointer-grid strong { display: block; margin: 2px 0; color: #284572; }.pointer-grid small { overflow-wrap: anywhere; }.index-lifecycle-list { display: grid; gap: 8px; }.lifecycle-row { display: flex; align-items: start; justify-content: space-between; gap: 14px; padding: 12px; border: 1px solid #e1e8f2; border-radius: 10px; background: #fff; }.lifecycle-row.current { border-color: #9ccfb2; background: #fbfffc; }.lifecycle-row.previous { border-color: #bed2ec; }.lifecycle-main { min-width: 0; }.lifecycle-title { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; color: #284572; }.state-pill, .pointer-pill { padding: 3px 7px; border-radius: 999px; font-size: .68rem; font-weight: 800; }.state-pill { background: #edf2f8; color: #5b6e88; }.state-pill.ready { background: #fff3d8; color: #8b621e; }.state-pill.active { background: #ddf2e5; color: #1b754d; }.state-pill.retired { background: #f0f1f3; color: #68717d; }.state-pill.failed { background: #ffe5e5; color: #a43e3e; }.pointer-pill { background: #e5f1ff; color: #2f659c; }.previous-pill { background: #f1ebff; color: #71509a; }.lifecycle-id { display: block; margin-top: 5px; color: #71809a; overflow-wrap: anywhere; }.evidence-grid { display: grid; gap: 3px; margin-top: 8px; color: #526b92; font-size: .73rem; line-height: 1.4; }.lifecycle-main > .muted { display: block; margin-top: 6px; font-size: .7rem; }.lifecycle-actions { display: flex; flex: 0 0 auto; flex-wrap: wrap; justify-content: end; gap: 7px; }.danger-button { color: #9a4d4d; }.index-lifecycle > .muted { margin: 0; }
@media (max-width: 700px) { .index-lifecycle-heading, .lifecycle-row { flex-direction: column; }.pointer-summary { width: 100%; text-align: left; }.pointer-grid { grid-template-columns: 1fr; }.lifecycle-actions { justify-content: start; } }
</style>
