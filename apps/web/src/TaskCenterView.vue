<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ApiError, getTaskJob, listAllCursorPages, listTaskJobs, operateSourceTask, type GitSourceView, type IngestionJobView, type PlatformRole, type SpaceRole, type TaskActionOperation } from "./api";
import { formatDateTime } from "./format";

const props = defineProps<{
  selectedSpaceId: string;
  currentRole: SpaceRole | PlatformRole | string;
}>();

const jobs = ref<IngestionJobView[]>([]);
const sourceOptions = ref<GitSourceView[]>([]);
const nextCursor = ref<string | null>(null);
const cursor = ref<string | null>(null);
const status = ref("");
const sourceId = ref("");
const searchQuery = ref("");
const loading = ref(false);
const actionKey = ref("");
const error = ref("");
const notice = ref("");
const actionReason = ref("");
const pollTimer = ref<number | null>(null);
const canManage = computed(() => props.currentRole === "SPACE_ADMIN" || props.currentRole === "PLATFORM_ADMIN" || props.currentRole === "EDITOR");
const storageKey = computed(() => `ragforge:task-center:${props.selectedSpaceId}`);
const activeStatuses = new Set(["REQUESTED", "RUNNING", "RETRY_SCHEDULED"]);
const terminalStatuses = new Set(["FAILED", "DLQ", "DEAD_LETTER", "CANCELLED"]);

type TaskCenterState = { cursor: string | null; previousCursors: string[]; status: string; sourceId: string; searchQuery?: string };
const previousCursors = ref<string[]>([]);
function restoreState(): void {
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey.value) ?? "null") as Partial<TaskCenterState> | null;
    if (saved) { cursor.value = saved.cursor ?? null; previousCursors.value = saved.previousCursors ?? []; status.value = saved.status ?? ""; sourceId.value = saved.sourceId ?? ""; searchQuery.value = saved.searchQuery ?? sessionStorage.getItem(`${storageKey.value}:search`) ?? ""; }
    else { cursor.value = null; previousCursors.value = []; status.value = ""; sourceId.value = ""; searchQuery.value = ""; }
  } catch { /* sessionStorage is optional. */ }
}
function persistState(): void {
  try { sessionStorage.setItem(`${storageKey.value}:search`, searchQuery.value); } catch { /* search persistence is optional. */ }
  try { sessionStorage.setItem(storageKey.value, JSON.stringify({ cursor: cursor.value, previousCursors: previousCursors.value, status: status.value, sourceId: sourceId.value } satisfies TaskCenterState)); } catch { /* private browsing may reject storage. */ }
}
function describeError(value: unknown): string {
  if (!(value instanceof ApiError)) return "任务读取失败，请稍后重试。";
  if (value.status === 401) return "登录状态已失效，请重新登录。";
  if (value.status === 403) return "当前角色无权读取或操作此空间任务。";
  if (value.status === 404) return "任务不存在，或不属于当前空间。";
  if (value.status === 409 || value.status === 412 || value.status === 428) return "任务版本已变化，请刷新后再操作。";
  return value.problem?.detail ?? value.message;
}
function jobVersion(item: IngestionJobView): number { return item.job.version ?? item.job.versionNo ?? 0; }
function statusLabel(value: string): string {
  return ({ REQUESTED: "已提交", RUNNING: "处理中", RETRY_SCHEDULED: "等待重试", SUCCEEDED: "已完成", FAILED: "失败", DLQ: "死信", DEAD_LETTER: "死信", CANCELLED: "已取消" } as Record<string, string>)[value] ?? value;
}
function isTerminal(item: IngestionJobView): boolean { return !activeStatuses.has(item.job.status); }
function isRetryable(item: IngestionJobView): boolean { return terminalStatuses.has(item.job.status); }
function stepError(step: IngestionJobView["steps"][number]): string {
  return [step.errorCode, step.errorMessage, step.errorDetail].filter(Boolean).join("：");
}
function jobError(item: IngestionJobView): string {
  return [item.job.error?.code, item.job.error?.message, item.job.error?.detail].filter(Boolean).join("：");
}

async function loadSources(): Promise<void> {
  if (!props.selectedSpaceId) return;
  const spaceIdAtStart = props.selectedSpaceId;
  try {
    const result = await listAllCursorPages<GitSourceView>(`/api/v1/spaces/${encodeURIComponent(spaceIdAtStart)}/sources`, { limit: 20 });
    if (props.selectedSpaceId === spaceIdAtStart) sourceOptions.value = result;
  }
  catch (value) { if (!error.value) error.value = describeError(value); }
}
async function load(): Promise<void> {
  if (!props.selectedSpaceId) return;
  const spaceIdAtStart = props.selectedSpaceId;
  loading.value = true; error.value = "";
  try {
    const query = searchQuery.value.trim().toLocaleLowerCase();
    if (query) {
      const allJobs = await listAllCursorPages<IngestionJobView>(`/api/v1/spaces/${encodeURIComponent(spaceIdAtStart)}/jobs`, { limit: 50, status: status.value, sourceId: sourceId.value || undefined });
      if (props.selectedSpaceId !== spaceIdAtStart) return;
      jobs.value = allJobs.filter((item) => [item.job.id, item.job.sourceId, item.job.sourceDocumentId, item.job.documentRevisionId, item.job.status, item.job.error?.code, item.job.error?.message, item.job.error?.detail, ...item.steps.flatMap((step) => [step.stepName, step.status, step.errorCode, step.errorMessage, step.errorDetail])].filter(Boolean).join(" ").toLocaleLowerCase().includes(query));
      nextCursor.value = null;
      persistState();
      schedulePolling();
      return;
    }
    const page = await listTaskJobs(spaceIdAtStart, { cursor: cursor.value, limit: 20, status: status.value, sourceId: sourceId.value || undefined });
    if (props.selectedSpaceId !== spaceIdAtStart) return;
    jobs.value = page.items; nextCursor.value = page.nextCursor; persistState();
  } catch (value) { error.value = describeError(value); }
  finally { loading.value = false; }
}
function applyFilters(): void { cursor.value = null; previousCursors.value = []; persistState(); void load(); }
function nextPage(): void { if (!nextCursor.value) return; previousCursors.value.push(cursor.value ?? ""); cursor.value = nextCursor.value; persistState(); void load(); }
function previousPage(): void { if (!cursor.value) return; cursor.value = previousCursors.value.pop() || null; persistState(); void load(); }
function stopPolling(): void { if (pollTimer.value !== null) { window.clearTimeout(pollTimer.value); pollTimer.value = null; } }
function schedulePolling(): void {
  stopPolling();
  if (!jobs.value.some((item) => activeStatuses.has(item.job.status))) return;
  pollTimer.value = window.setTimeout(async () => { await load(); schedulePolling(); }, 4000);
}

async function refreshJob(item: IngestionJobView): Promise<void> {
  try {
    const current = await getTaskJob(props.selectedSpaceId, item.job.id);
    const index = jobs.value.findIndex((candidate) => candidate.job.id === item.job.id);
    if (index >= 0) jobs.value.splice(index, 1, current);
  } catch (value) { error.value = describeError(value); }
}
async function operate(item: IngestionJobView, operation: TaskActionOperation): Promise<void> {
  if (!canManage.value) { error.value = "当前角色没有任务写权限。"; return; }
  if (["RETRY", "REPLAY", "RESYNC"].includes(operation) && !isRetryable(item)) return;
  if (operation === "DELETE" && !window.confirm(`确定删除任务 ${item.job.id} 吗？`)) return;
  const key = `${operation}:${item.job.id}`;
  actionKey.value = key; error.value = ""; notice.value = "";
  try {
    await operateSourceTask(props.selectedSpaceId, "JOB", item.job.id, operation, jobVersion(item), actionReason.value);
    actionReason.value = ""; notice.value = `${operation === "ARCHIVE" ? "归档" : operation === "DELETE" ? "删除" : operation === "RESYNC" ? "重新同步" : operation === "REPLAY" ? "重放" : "重试"}请求已提交。`; await load(); schedulePolling();
  } catch (value) { error.value = describeError(value); }
  finally { actionKey.value = ""; }
}

watch(() => props.selectedSpaceId, () => { if (props.selectedSpaceId) { restoreState(); void Promise.all([loadSources(), load()]); } });
watch([status, sourceId, searchQuery], () => { persistState(); });
onMounted(() => { if (props.selectedSpaceId) { restoreState(); void Promise.all([loadSources(), load()]); } });
onBeforeUnmount(stopPolling);
</script>

<template>
  <section class="task-center card" aria-labelledby="task-center-heading">
    <div class="section-heading"><div><span class="card-label">任务中心</span><h3 id="task-center-heading">摄取任务状态</h3><p class="muted">提交后自动轮询服务端，展示处理中、终态、逐项步骤和错误详情；不会伪造进度。</p></div><button type="button" class="quiet-button" :disabled="loading" @click="load">{{ loading ? "读取中…" : "刷新任务" }}</button></div>
    <div class="filter-row"><div class="field"><label for="task-status-filter">任务状态</label><select id="task-status-filter" v-model="status" @change="applyFilters"><option value="">全部状态</option><option value="REQUESTED">已提交</option><option value="RUNNING">处理中</option><option value="RETRY_SCHEDULED">等待重试</option><option value="SUCCEEDED">已完成</option><option value="FAILED">失败</option><option value="DLQ">死信</option></select></div><div class="field"><label for="task-source-filter">来源</label><select id="task-source-filter" v-model="sourceId" @change="applyFilters"><option value="">全部来源</option><option v-for="item in sourceOptions" :key="item.source.sourceId" :value="item.source.sourceId">{{ item.source.displayName }}</option></select></div><div class="field search-field"><label for="task-search">搜索任务</label><div class="search-control"><input id="task-search" v-model="searchQuery" maxlength="120" placeholder="任务、来源、步骤或错误" @keyup.enter="applyFilters" /><button type="button" class="secondary-button" :disabled="loading" @click="applyFilters">搜索</button></div></div><div class="field"><label for="task-action-reason">操作说明（可选）</label><input id="task-action-reason" v-model="actionReason" maxlength="500" placeholder="记录重试或清理原因" /></div></div>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <div v-if="jobs.length" class="job-list"><article v-for="item in jobs" :key="item.job.id" class="job-card"><div class="job-header"><div><strong>{{ statusLabel(item.job.status) }}</strong><span>任务 {{ item.job.id }}</span><small>创建于 {{ formatDateTime(item.job.createdAt) }} · 更新于 {{ formatDateTime(item.job.updatedAt) }}</small></div><span class="state-pill" :class="item.job.status.toLowerCase()">{{ item.job.status }}</span></div><p v-if="jobError(item)" class="job-error"><strong>任务错误：</strong>{{ jobError(item) }}</p><div class="step-list"><div v-for="step in item.steps" :key="step.id" class="step-row"><span class="step-state" :class="step.status.toLowerCase()">{{ step.status }}</span><strong>{{ step.stepName }}</strong><small>{{ stepError(step) || "无错误详情" }}</small></div><p v-if="!item.steps.length" class="muted">Worker 尚未报告步骤，任务仍由服务端排队。</p></div><details class="job-details"><summary>查看尝试与时间</summary><div v-for="attempt in item.attempts" :key="attempt.id" class="attempt-row"><span>Attempt {{ attempt.attemptNo }} · {{ attempt.status }}</span><small>{{ formatDateTime(attempt.startedAt) }} → {{ formatDateTime(attempt.finishedAt) }}</small></div></details><div class="button-row"><button type="button" class="quiet-button" :disabled="Boolean(actionKey) || !isRetryable(item) || !canManage" @click="operate(item, 'RETRY')">重试</button><button type="button" class="quiet-button" :disabled="Boolean(actionKey) || !isRetryable(item) || !canManage" @click="operate(item, 'REPLAY')">重放</button><button type="button" class="quiet-button" :disabled="Boolean(actionKey) || !isRetryable(item) || !canManage" @click="operate(item, 'RESYNC')">重新同步</button><button type="button" class="quiet-button" :disabled="Boolean(actionKey) || !canManage" @click="operate(item, 'ARCHIVE')">归档</button><button type="button" class="danger-button" :disabled="Boolean(actionKey) || !canManage" @click="operate(item, 'DELETE')">删除</button><button type="button" class="quiet-button" :disabled="loading" @click="refreshJob(item)">读取最新详情</button></div></article></div><div v-else-if="!loading" class="empty-state"><strong>{{ searchQuery.trim() ? "没有匹配的任务" : "暂无任务" }}</strong><span>{{ searchQuery.trim() ? "请换一个任务 ID、来源、步骤或错误关键词。" : "来源提交或同步后，任务会出现在这里；你可以稍后刷新。" }}</span></div>
    <div class="pagination"><button type="button" class="quiet-button" :disabled="loading || Boolean(searchQuery.trim()) || !cursor" @click="previousPage">上一页</button><span>{{ searchQuery.trim() ? "搜索结果" : `当前页 ${cursor ? "（cursor）" : "（首页）"}` }} · {{ jobs.length }} 条</span><button type="button" class="quiet-button" :disabled="loading || Boolean(searchQuery.trim()) || !nextCursor" @click="nextPage">下一页</button></div>
  </section>
</template>

<style scoped>
.task-center { margin-top: 15px; }.section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }.section-heading h3 { margin: 4px 0; }.muted { color: #687893; font-size: .8rem; line-height: 1.5; }.filter-row { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; align-items: end; margin-top: 16px; }.search-control { display: flex; gap: 7px; }.search-control input { min-width: 0; flex: 1; }.search-control button { white-space: nowrap; }.field label { display: block; margin-bottom: 6px; color: #314b77; font-size: .8rem; font-weight: 700; }.alert { margin: 12px 0; padding: 10px 12px; border-radius: 9px; }.alert.error { background: #ffeded; color: #a22f38; }.alert.success { background: #e7f7ee; color: #176b4b; }.job-list { display: grid; gap: 11px; margin-top: 15px; }.job-card { padding: 14px; border: 1px solid #e1e8f2; border-radius: 11px; background: #fafcff; }.job-header, .button-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }.job-header strong, .job-header span, .job-header small { display: block; }.job-header strong { color: #294d80; }.job-header span, .job-header small { margin-top: 4px; color: #71809a; font-size: .75rem; overflow-wrap: anywhere; }.state-pill { padding: 5px 8px; border-radius: 999px; background: #eef3fa; color: #52709e; font-size: .72rem; font-weight: 800; }.state-pill.running, .state-pill.requested, .state-pill.retry_scheduled { background: #fff4da; color: #94600d; }.state-pill.succeeded { background: #e7f7ee; color: #176b4b; }.state-pill.failed, .state-pill.dlq, .state-pill.dead_letter { background: #ffeded; color: #a22f38; }.job-error { margin: 12px 0 0; padding: 9px 10px; border-radius: 8px; background: #fff0f0; color: #9b3640; font-size: .78rem; line-height: 1.5; overflow-wrap: anywhere; }.step-list { display: grid; gap: 7px; margin-top: 12px; }.step-row { display: grid; grid-template-columns: 90px minmax(110px, .4fr) minmax(0, 1fr); align-items: center; gap: 8px; padding: 8px 9px; border-radius: 8px; background: #f0f5fb; }.step-state { font-size: .7rem; font-weight: 800; color: #52709e; }.step-state.succeeded, .step-state.completed { color: #19714c; }.step-state.failed { color: #a22f38; }.step-row strong, .step-row small { overflow-wrap: anywhere; }.step-row small { color: #71809a; font-size: .72rem; }.job-details { margin-top: 12px; color: #4f6b95; font-size: .75rem; }.job-details summary { cursor: pointer; font-weight: 700; }.attempt-row { display: flex; justify-content: space-between; gap: 10px; margin-top: 7px; color: #71809a; }.button-row { justify-content: flex-start; flex-wrap: wrap; margin-top: 13px; }.button-row button { padding: 8px 10px; font-size: .74rem; }.danger-button { background: #a73943; }.empty-state { display: flex; flex-direction: column; gap: 5px; margin-top: 15px; color: #687893; }.pagination { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 15px; color: #71809a; font-size: .78rem; }.pagination button { padding: 8px 10px; }
@media (max-width: 760px) { .filter-row { grid-template-columns: 1fr; }.job-header { flex-direction: column; }.step-row { grid-template-columns: 78px 1fr; }.step-row small { grid-column: 2; }.pagination { align-items: stretch; flex-direction: column; } }
</style>
