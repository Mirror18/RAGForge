<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ApiError, configureGitSource, listSources, operateSourceTask, type GitSourceView, type PlatformRole, type SpaceRole, type SourceRecord, type TaskActionOperation } from "./api";
import { formatDateTime } from "./format";

const props = defineProps<{
  selectedSpaceId: string;
  currentRole: SpaceRole | PlatformRole | string;
}>();

const emit = defineEmits<{
  "sources-loaded": [sources: GitSourceView[]];
}>();

const sources = ref<GitSourceView[]>([]);
const nextCursor = ref<string | null>(null);
const cursor = ref<string | null>(null);
const connectorType = ref("");
const sourceState = ref("");
const searchQuery = ref("");
const displayName = ref("");
const remote = ref("");
const branch = ref("main");
const include = ref("**/*.md");
const loading = ref(false);
const saving = ref(false);
const actionKey = ref("");
const error = ref("");
const notice = ref("");
const actionReason = ref("");
const canManage = computed(() => props.currentRole === "SPACE_ADMIN" || props.currentRole === "PLATFORM_ADMIN" || props.currentRole === "EDITOR");
const storageKey = computed(() => `ragforge:source-library:${props.selectedSpaceId}`);

type SourceLibraryState = { cursor: string | null; previousCursors: string[]; connectorType: string; sourceState: string; searchQuery: string };
const previousCursors = ref<string[]>([]);
function restoreState(): void {
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey.value) ?? "null") as Partial<SourceLibraryState> | null;
    if (saved) {
      cursor.value = saved.cursor ?? null;
      previousCursors.value = saved.previousCursors ?? [];
      connectorType.value = saved.connectorType ?? "";
      sourceState.value = saved.sourceState ?? "";
      searchQuery.value = saved.searchQuery ?? "";
    } else { cursor.value = null; previousCursors.value = []; connectorType.value = ""; sourceState.value = ""; searchQuery.value = ""; }
  } catch { /* sessionStorage is optional and never part of the security boundary. */ }
}
function persistState(): void {
  try { sessionStorage.setItem(storageKey.value, JSON.stringify({ cursor: cursor.value, previousCursors: previousCursors.value, connectorType: connectorType.value, sourceState: sourceState.value, searchQuery: searchQuery.value } satisfies SourceLibraryState)); } catch { /* private browsing may reject storage. */ }
}
function describeError(value: unknown): string {
  if (!(value instanceof ApiError)) return "来源读取失败，请稍后重试。";
  if (value.status === 401) return "登录状态已失效，请重新登录。";
  if (value.status === 403) return "当前角色无权读取或操作此空间来源。";
  if (value.status === 404) return "来源不存在，或不属于当前空间。";
  if (value.status === 409 || value.status === 412 || value.status === 428) return "来源版本已变化，请刷新后再操作。";
  return value.problem?.detail ?? value.message;
}
function sourceVersion(item: GitSourceView): number { return item.source.version ?? item.source.versionNo ?? 0; }
function sourceLabel(source: SourceRecord): string { return `${source.displayName} · ${source.connectorType ?? "Git"}`; }
function sourceStateLabel(source: SourceRecord): string { return source.sourceState ?? source.state ?? "ACTIVE"; }

async function load(): Promise<void> {
  if (!props.selectedSpaceId) return;
  const spaceIdAtStart = props.selectedSpaceId;
  loading.value = true; error.value = "";
  try {
    const page = await listSources(spaceIdAtStart, { cursor: cursor.value, limit: 10, connectorType: connectorType.value, sourceState: sourceState.value, q: searchQuery.value.trim() || undefined });
    if (props.selectedSpaceId !== spaceIdAtStart) return;
    sources.value = page.items;
    nextCursor.value = page.nextCursor;
    emit("sources-loaded", page.items);
    persistState();
  } catch (value) { error.value = describeError(value); }
  finally { loading.value = false; }
}
function applyFilters(): void { cursor.value = null; previousCursors.value = []; persistState(); void load(); }
function nextPage(): void { if (!nextCursor.value) return; previousCursors.value.push(cursor.value ?? ""); cursor.value = nextCursor.value; persistState(); void load(); }
function previousPage(): void { if (!cursor.value) return; cursor.value = previousCursors.value.pop() || null; persistState(); void load(); }

async function save(): Promise<void> {
  if (!canManage.value) { error.value = "当前角色没有来源写权限。"; return; }
  if (!displayName.value.trim() || !remote.value.trim()) { error.value = "请填写来源名称和 remote。"; return; }
  saving.value = true; error.value = ""; notice.value = "";
  try {
    await configureGitSource(props.selectedSpaceId, { displayName: displayName.value.trim(), remote: remote.value.trim(), branch: branch.value.trim() || "main", include: include.value.split(",").map((item) => item.trim()).filter(Boolean), exclude: [] });
    displayName.value = ""; remote.value = ""; notice.value = "来源已提交，稍后可在任务中心查看同步进度。"; await load();
  } catch (value) { error.value = describeError(value); }
  finally { saving.value = false; }
}

async function operate(item: GitSourceView, operation: TaskActionOperation): Promise<void> {
  if (!canManage.value) { error.value = "当前角色没有来源写权限。"; return; }
  if (operation === "DELETE" && !window.confirm(`确定删除来源“${item.source.displayName}”吗？`)) return;
  const key = `${operation}:${item.source.sourceId}`;
  actionKey.value = key; error.value = ""; notice.value = "";
  try {
    await operateSourceTask(props.selectedSpaceId, "SOURCE", item.source.sourceId, operation, sourceVersion(item), actionReason.value);
    actionReason.value = ""; notice.value = `${operation === "ARCHIVE" ? "归档" : operation === "DELETE" ? "删除" : operation === "RESYNC" ? "重新同步" : operation === "REPLAY" ? "重放" : "重试"}请求已提交。`; await load();
  } catch (value) { error.value = describeError(value); }
  finally { actionKey.value = ""; }
}

watch(() => props.selectedSpaceId, () => { if (props.selectedSpaceId) { restoreState(); void load(); } });
onMounted(() => { if (props.selectedSpaceId) { restoreState(); void load(); } });
</script>

<template>
  <section class="source-library card" aria-labelledby="source-library-heading">
    <div class="section-heading"><div><span class="card-label">来源库</span><h3 id="source-library-heading">来源提交与生命周期</h3><p class="muted">来源属于当前空间；列表使用 cursor 分页，刷新后保留筛选位置。</p></div><button type="button" class="quiet-button" :disabled="loading" @click="load">{{ loading ? "读取中…" : "刷新来源" }}</button></div>
    <form class="source-form" @submit.prevent="save"><div class="field"><label for="source-display-name">名称</label><input id="source-display-name" v-model="displayName" maxlength="120" placeholder="团队知识仓库" /></div><div class="field"><label for="source-remote">Remote</label><input id="source-remote" v-model="remote" maxlength="512" placeholder="https://git.example.com/notes.git" /></div><div class="field"><label for="source-branch">分支</label><input id="source-branch" v-model="branch" maxlength="255" placeholder="main" /></div><div class="field"><label for="source-include">Include</label><input id="source-include" v-model="include" maxlength="512" placeholder="**/*.md,docs/**" /></div><button type="submit" :disabled="saving || !canManage">{{ saving ? "提交中…" : "提交来源" }}</button></form>
    <p v-if="!canManage" class="permission-hint">当前角色只能查看来源；写操作由服务端权限最终裁决。</p>
    <div class="filter-row"><div class="field"><label for="source-connector-filter">连接器</label><select id="source-connector-filter" v-model="connectorType" @change="applyFilters"><option value="">全部连接器</option><option value="GIT">Git</option></select></div><div class="field"><label for="source-state-filter">状态</label><select id="source-state-filter" v-model="sourceState" @change="applyFilters"><option value="">全部状态</option><option value="ACTIVE">ACTIVE</option><option value="PAUSED">PAUSED</option></select></div><div class="field search-field"><label for="source-search">搜索来源</label><div class="search-control"><input id="source-search" v-model="searchQuery" maxlength="120" placeholder="名称、仓库、分支或 ID" @keyup.enter="applyFilters" /><button type="button" class="secondary-button" :disabled="loading" @click="applyFilters">搜索</button></div></div><div class="field reason-field"><label for="source-action-reason">操作说明（可选）</label><input id="source-action-reason" v-model="actionReason" maxlength="500" placeholder="记录本次操作原因" /></div></div>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <div v-if="sources.length" class="source-list"><article v-for="item in sources" :key="item.source.sourceId" class="source-row"><div class="source-main"><strong>{{ sourceLabel(item.source) }}</strong><span>{{ item.source.rootRef }} · {{ item.source.gitBranch || "默认分支" }}</span><small>状态 {{ sourceStateLabel(item.source) }} · 版本 {{ sourceVersion(item) }} · checkpoint {{ item.checkpoint?.cursor || "未同步" }}</small><small v-if="item.checkpoint">checkpoint 更新于 {{ formatDateTime(item.checkpoint.updatedAt) }}</small></div><div class="button-row"><button type="button" class="quiet-button" :disabled="Boolean(actionKey) || !canManage" @click="operate(item, 'RETRY')">重试</button><button type="button" class="quiet-button" :disabled="Boolean(actionKey) || !canManage" @click="operate(item, 'REPLAY')">重放</button><button type="button" class="quiet-button" :disabled="Boolean(actionKey) || !canManage" @click="operate(item, 'RESYNC')">重新同步</button><button type="button" class="quiet-button" :disabled="Boolean(actionKey) || !canManage" @click="operate(item, 'ARCHIVE')">归档</button><button type="button" class="danger-button" :disabled="Boolean(actionKey) || !canManage" @click="operate(item, 'DELETE')">删除</button></div></article></div><div v-else-if="!loading" class="empty-state"><strong>{{ searchQuery.trim() ? "没有匹配的来源" : "暂无来源" }}</strong><span>{{ searchQuery.trim() ? "请换一个名称、仓库地址、分支或 ID。" : "提交 Git 来源后，服务端会在当前空间创建来源记录。" }}</span></div>
    <div class="pagination"><button type="button" class="quiet-button" :disabled="loading || !cursor" @click="previousPage">上一页</button><span>{{ searchQuery.trim() ? "搜索结果（每页 10 条）" : `当前页（每页 10 条） ${cursor ? "（cursor）" : "（首页）"}` }} · {{ sources.length }} 条</span><button type="button" class="quiet-button" :disabled="loading || !nextCursor" @click="nextPage">下一页</button></div>
  </section>
</template>

<style scoped>
.source-library { margin-top: 15px; }.section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }.section-heading h3 { margin: 4px 0; }.muted { color: #687893; font-size: .8rem; line-height: 1.5; }.source-form, .filter-row { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; align-items: end; margin-top: 16px; }.search-control { display: flex; gap: 7px; }.search-control input { min-width: 0; flex: 1; }.search-control button { white-space: nowrap; }.field label { display: block; margin-bottom: 6px; color: #314b77; font-size: .8rem; font-weight: 700; }.permission-hint { color: #94600d; font-size: .78rem; }.alert { margin: 12px 0; padding: 10px 12px; border-radius: 9px; }.alert.error { background: #ffeded; color: #a22f38; }.alert.success { background: #e7f7ee; color: #176b4b; }.source-list { display: grid; gap: 9px; margin-top: 15px; }.source-row { display: flex; align-items: center; justify-content: space-between; gap: 15px; padding: 13px; border: 1px solid #e1e8f2; border-radius: 11px; background: #fafcff; }.source-main { min-width: 0; }.source-main strong, .source-main span, .source-main small { display: block; overflow-wrap: anywhere; }.source-main strong { color: #294d80; }.source-main span, .source-main small { margin-top: 4px; color: #71809a; font-size: .75rem; }.button-row { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 7px; }.button-row button { padding: 8px 10px; font-size: .74rem; }.danger-button { background: #a73943; }.empty-state { display: flex; flex-direction: column; gap: 5px; margin-top: 15px; color: #687893; }.pagination { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 15px; color: #71809a; font-size: .78rem; }.pagination button { padding: 8px 10px; }
@media (max-width: 900px) { .source-form, .filter-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }.source-row { align-items: stretch; flex-direction: column; }.button-row { justify-content: flex-start; } } @media (max-width: 540px) { .source-form, .filter-row { grid-template-columns: 1fr; }.pagination { align-items: stretch; flex-direction: column; } }
</style>
