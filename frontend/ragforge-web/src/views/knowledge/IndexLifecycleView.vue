<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { formatDateTime } from "../../format";
import type { ActiveIndexView, IndexView } from "../../api";

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

const pageSize = 10;
const currentPage = ref(1);
const current = computed(() => props.activeIndex?.pointer.activeIndexVersionId ?? null);
const previous = computed(() => props.activeIndex?.pointer.previousIndexVersionId ?? null);
const previousIndex = computed(() => props.indexes.find((item) => item.indexVersionId === previous.value) ?? null);
const pageCount = computed(() => Math.max(1, Math.ceil(props.indexes.length / pageSize)));
const visibleIndexes = computed(() => props.indexes.slice((currentPage.value - 1) * pageSize, currentPage.value * pageSize));

const stateLabels: Record<string, string> = {
  BUILDING: "构建中",
  VALIDATING: "验证中",
  READY: "待发布",
  ACTIVE: "当前版本",
  RETIRED: "已退役",
  FAILED: "验证失败",
};

function stateLabel(state: string): string {
  return stateLabels[state] ?? state;
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

function setPage(nextPage: number): void {
  currentPage.value = Math.min(Math.max(nextPage, 1), pageCount.value);
}

watch(() => props.indexes.length, () => setPage(currentPage.value));
</script>

<template>
  <div class="index-lifecycle" aria-labelledby="index-lifecycle-heading">
    <div class="index-lifecycle-heading">
      <div>
        <label id="index-lifecycle-heading">索引生命周期</label>
        <p class="muted">索引先构建并验证，只有“待发布”版本可以成为当前版本；旧版本可回滚或退役。</p>
      </div>
      <div class="pointer-summary">
        <span>当前索引</span>
        <strong>{{ current ? `第 ${indexes.find((item) => item.indexVersionId === current)?.versionNo ?? "?"} 版` : "尚未发布" }}</strong>
        <small>{{ current ? "正在用于问答" : "处理完成后点发布" }}</small>
      </div>
    </div>

    <div class="pointer-grid">
      <div><span>当前版本</span><strong>{{ current ? `第 ${indexes.find((item) => item.indexVersionId === current)?.versionNo ?? "?"} 版` : "—" }}</strong><small>{{ current ? "用于问答" : "尚未发布" }}</small></div>
      <div><span>上一版本</span><strong>{{ previous ? `第 ${previousIndex?.versionNo ?? "?"} 版` : "—" }}</strong><small>{{ previous ? "可回滚" : "没有上一版" }}</small></div>
      <button v-if="previous && canManage" type="button" class="quiet-button" :disabled="busyIndexId === previous" @click="emit('rollback', previous)">
        {{ busyIndexId === previous ? "回滚中…" : "回滚上一版" }}
      </button>
    </div>

    <div v-if="indexes.length" class="lifecycle-data-list">
      <div class="data-list-heading"><div><span class="card-label">索引版本数据列表</span><p class="muted">每页展示 10 条，按版本号查看构建、验证和生命周期操作。</p></div><span class="data-list-count">共 {{ indexes.length }} 条</span></div>
      <div class="lifecycle-table-wrap">
        <table class="lifecycle-table" aria-label="索引生命周期列表">
          <thead><tr><th scope="col">版本</th><th scope="col">状态</th><th scope="col">构建依据</th><th scope="col">验证依据</th><th scope="col">配置</th><th scope="col">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in visibleIndexes" :key="item.indexVersionId" class="lifecycle-row" :class="{ current: isCurrent(item), previous: isPrevious(item) }">
              <td class="lifecycle-version"><div><strong>第 {{ item.versionNo }} 版</strong><span v-if="isCurrent(item)" class="pointer-pill">当前版本</span><span v-if="isPrevious(item)" class="pointer-pill previous-pill">上一版本</span></div></td>
              <td><span class="state-pill" :class="item.state.toLowerCase()">{{ stateLabel(item.state) }}</span></td>
              <td>{{ item.documentRevisionCount ?? "—" }} 个修订 · {{ item.childChunkCount ?? "—" }} 个子分块</td>
              <td>{{ item.validationVectorDimension ?? "—" }} 维 · 抽样检索 {{ validationLabel(item.sampleRetrievalPassed) }} · 空间过滤 {{ validationLabel(item.spaceFilterPassed) }}</td>
              <td><span>{{ item.embeddingProfileVersion || "—" }}</span><small>{{ item.chunkingStrategyVersion || "—" }}</small></td>
              <td><div class="lifecycle-actions"><button v-if="item.state === 'READY'" type="button" class="quiet-button" :disabled="!canManage || busyIndexId === item.indexVersionId" @click="emit('publish', item)">{{ busyIndexId === item.indexVersionId ? "发布中…" : "发布为当前版本" }}</button><button v-if="canRetire(item)" type="button" class="quiet-button danger-button" :disabled="busyIndexId === item.indexVersionId" @click="emit('retire', item)">{{ busyIndexId === item.indexVersionId ? "退役中…" : "退役此版本" }}</button><span v-if="item.state !== 'READY' && !canRetire(item)" class="muted">无操作</span></div></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pagination-bar" data-pagination="index-lifecycle"><span>第 {{ currentPage }} / {{ pageCount }} 页 · 共 {{ indexes.length }} 条</span><div><button type="button" class="quiet-button" :disabled="currentPage <= 1" aria-label="上一页" @click="setPage(currentPage - 1)">上一页</button><button type="button" class="quiet-button" :disabled="currentPage >= pageCount" aria-label="下一页" @click="setPage(currentPage + 1)">下一页</button></div></div>
    </div>
    <p v-else class="muted empty-index-list">尚未生成可用索引版本。</p>
  </div>
</template>

<style scoped>
.index-lifecycle { display: grid; gap: 12px; width: 100%; margin-top: 16px; padding: 0; border: 0; border-radius: 0; background: transparent; }.index-lifecycle-heading { display: flex; align-items: start; justify-content: space-between; gap: 18px; }.index-lifecycle-heading label { color: #405f8b; font-size: .74rem; font-weight: 900; letter-spacing: .08em; text-transform: uppercase; }.index-lifecycle-heading .muted { margin: 6px 0 0; }.pointer-summary { min-width: 190px; padding: 10px 12px; border: 1px solid #cfe0f1; border-radius: 9px; background: #f5faff; text-align: right; }.pointer-summary span, .pointer-summary small, .pointer-grid span, .pointer-grid small { display: block; color: #70819b; font-size: .7rem; }.pointer-summary strong { display: block; margin: 3px 0; color: #225887; font-size: .76rem; overflow-wrap: anywhere; }.pointer-grid { display: grid; grid-template-columns: 1fr 1fr auto; align-items: center; gap: 10px; padding: 11px; border: 1px solid #dce8f6; border-radius: 10px; background: #fbfdff; }.pointer-grid strong { display: block; margin: 2px 0; color: #284572; }.pointer-grid small { overflow-wrap: anywhere; }.lifecycle-data-list { display: grid; gap: 10px; }.data-list-heading { display: flex; align-items: end; justify-content: space-between; gap: 12px; }.data-list-heading .muted { margin: 5px 0 0; font-size: .74rem; }.data-list-count { color: #52709e; font-size: .72rem; font-weight: 800; }.lifecycle-table-wrap { overflow-x: auto; border: 1px solid #e1e8f2; border-radius: 10px; }.lifecycle-table { width: 100%; min-width: 1020px; border-collapse: collapse; text-align: left; }.lifecycle-table th { padding: 9px 10px; background: #f1f6fd; color: #71809a; font-size: .68rem; font-weight: 800; }.lifecycle-table td { padding: 10px; border-top: 1px solid #edf1f6; color: #526b92; font-size: .72rem; line-height: 1.4; vertical-align: middle; }.lifecycle-table tbody tr:first-child td { border-top: 0; }.lifecycle-row.current td { background: #fbfffc; }.lifecycle-row.previous td { background: #fbfdff; }.lifecycle-version { min-width: 180px; }.lifecycle-version > div { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }.lifecycle-version strong { color: #284572; }.lifecycle-version small, .lifecycle-table td > small { display: block; margin-top: 4px; overflow-wrap: anywhere; color: #7a8aa2; font-size: .64rem; }.lifecycle-table td:nth-child(3), .lifecycle-table td:nth-child(4) { min-width: 205px; }.lifecycle-table td:nth-child(5) { min-width: 145px; }.state-pill, .pointer-pill { display: inline-flex; padding: 3px 7px; border-radius: 999px; font-size: .68rem; font-weight: 800; }.state-pill { background: #edf2f8; color: #5b6e88; }.state-pill.ready { background: #fff3d8; color: #8b621e; }.state-pill.active { background: #ddf2e5; color: #1b754d; }.state-pill.retired { background: #f0f1f3; color: #68717d; }.state-pill.failed { background: #ffe5e5; color: #a43e3e; }.pointer-pill { background: #e5f1ff; color: #2f659c; }.previous-pill { background: #f1ebff; color: #71509a; }.lifecycle-actions { display: flex; flex-wrap: wrap; gap: 7px; min-width: 130px; }.lifecycle-actions button { white-space: nowrap; }.pagination-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #71809a; font-size: .72rem; }.pagination-bar > div { display: flex; gap: 7px; }.pagination-bar button { padding: 7px 10px; font-size: .7rem; }.empty-index-list { margin: 0; padding: 14px; border: 1px dashed #dce5f2; border-radius: 10px; }
@media (max-width: 700px) { .index-lifecycle-heading { flex-direction: column; }.pointer-summary { width: 100%; text-align: left; }.pointer-grid { grid-template-columns: 1fr; }.lifecycle-table { min-width: 920px; }.lifecycle-actions { justify-content: start; } }
</style>
