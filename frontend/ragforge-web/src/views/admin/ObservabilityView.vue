<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ApiError, getManagementCostUsage, getManagementHealth, getRunSnapshot, listAllCursorPages, listSpaceRuns, retryRun, type ManagementAuditItem, type ManagementFeedbackItem, type ManagementHealth, type ManagementUsageCost, type PlatformRole, type RunSnapshot, type SpaceRole, type SpaceRunItem } from "../../api";
import { formatDateTime } from "../../format";
import { stateLabel } from "../../i18n";

type ObserveSection = "overview" | "traces" | "audit";

const props = defineProps<{
  selectedSpaceId: string;
  currentRole: SpaceRole | PlatformRole | string;
  platformRole: PlatformRole;
  initialSection?: string;
  initialRunId?: string;
}>();
const emit = defineEmits<{ "select-section": [section: ObserveSection] }>();

const section = ref<ObserveSection>(normalize(props.initialSection));
const loading = ref(false);
const error = ref("");
const notice = ref("");
const managementHealth = ref<ManagementHealth | null>(null);
const managementCost = ref<ManagementUsageCost | null>(null);
const managementFeedback = ref<ManagementFeedbackItem[]>([]);
const managementAudit = ref<ManagementAuditItem[]>([]);
const managementFrom = ref(new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString());
const managementTo = ref(new Date().toISOString());
const runs = ref<SpaceRunItem[]>([]);
const runsCursor = ref<string | null>(null);
const statusFilter = ref("");
const runSnapshot = ref<RunSnapshot | null>(null);
const selectedRunId = ref(props.initialRunId ?? "");

const canManage = computed(() => props.currentRole === "SPACE_ADMIN" || props.platformRole === "PLATFORM_ADMIN");
const formatDate = formatDateTime;

function normalize(value: string | undefined): ObserveSection {
  if (value === "traces" || value === "audit" || value === "overview") return value;
  return "overview";
}
function path(suffix: string): string { return `/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}${suffix}`; }
function describeError(value: unknown, fallback: string): string {
  if (value instanceof Error && !(value instanceof ApiError)) return value.message;
  if (!(value instanceof ApiError)) return fallback;
  const permission = value.status === 403 ? "当前角色无权执行此操作。" : value.status === 404 ? "资源不存在或不属于当前空间。" : "请稍后重试。";
  const correlation = value.correlationId ? ` 相关 ID：${value.correlationId}` : "";
  return `${value.problem?.code ? `${value.problem.code}：` : ""}${value.problem?.detail ?? value.message} ${permission}${correlation}`;
}
function ensureSpace(): boolean {
  if (props.selectedSpaceId) return true;
  error.value = "请先选择当前空间。";
  return false;
}

async function loadSection(): Promise<void> {
  error.value = "";
  notice.value = "";
  if (!ensureSpace()) return;
  loading.value = true;
  try {
    if (section.value === "overview") {
      const [health, cost] = await Promise.all([
        getManagementHealth(props.selectedSpaceId, { from: managementFrom.value, to: managementTo.value }),
        getManagementCostUsage(props.selectedSpaceId, { from: managementFrom.value, to: managementTo.value }),
      ]);
      managementHealth.value = health;
      managementCost.value = cost;
    } else if (section.value === "traces") {
      const page = await listSpaceRuns(props.selectedSpaceId, {
        limit: 20,
        status: statusFilter.value || undefined,
        from: managementFrom.value,
        to: managementTo.value,
      });
      runs.value = page.items;
      runsCursor.value = page.nextCursor;
      if (selectedRunId.value) await loadRun(selectedRunId.value, false);
    } else {
      const [feedback, audit] = await Promise.all([
        listAllCursorPages<ManagementFeedbackItem>(path("/management/feedback"), { from: managementFrom.value, to: managementTo.value, limit: 10 }),
        listAllCursorPages<ManagementAuditItem>(path("/management/audit/export"), { from: managementFrom.value, to: managementTo.value, limit: 10 }),
      ]);
      managementFeedback.value = feedback;
      managementAudit.value = audit;
    }
  } catch (value) {
    error.value = describeError(value, "运行观测数据加载失败。");
  } finally {
    loading.value = false;
  }
}

async function loadMoreRuns(): Promise<void> {
  if (!runsCursor.value || !ensureSpace()) return;
  loading.value = true;
  try {
    const page = await listSpaceRuns(props.selectedSpaceId, {
      limit: 20,
      cursor: runsCursor.value,
      status: statusFilter.value || undefined,
      from: managementFrom.value,
      to: managementTo.value,
    });
    runs.value = [...runs.value, ...page.items];
    runsCursor.value = page.nextCursor;
  } catch (value) {
    error.value = describeError(value, "继续加载追踪失败。");
  } finally {
    loading.value = false;
  }
}

async function loadRun(runId: string, setLoading = true): Promise<void> {
  if (!ensureSpace() || !runId.trim()) return;
  selectedRunId.value = runId.trim();
  if (setLoading) loading.value = true;
  error.value = "";
  try {
    runSnapshot.value = await getRunSnapshot(props.selectedSpaceId, selectedRunId.value);
  } catch (value) {
    runSnapshot.value = null;
    error.value = describeError(value, "运行记录加载失败。");
  } finally {
    if (setLoading) loading.value = false;
  }
}

async function retrySelected(): Promise<void> {
  if (!ensureSpace() || !runSnapshot.value || !canManage.value) return;
  loading.value = true; error.value = ""; notice.value = "";
  try {
    await retryRun(props.selectedSpaceId, runSnapshot.value.runId);
    notice.value = "重试已提交。";
    await loadRun(runSnapshot.value.runId, false);
    await loadSection();
  } catch (value) { error.value = describeError(value, "重试失败。"); }
  finally { loading.value = false; }
}

watch(() => props.initialSection, (value) => { section.value = normalize(value); });
watch(section, (value) => emit("select-section", value));
watch(() => props.initialRunId, (value) => {
  if (!value || value === selectedRunId.value) return;
  selectedRunId.value = value;
  if (section.value === "traces") void loadRun(value);
});
watch(() => [props.selectedSpaceId, section.value, statusFilter.value], () => { void loadSection(); }, { immediate: true });
</script>

<template>
  <section class="view-section control-center" aria-labelledby="control-center-heading">
    <div class="section-heading">
      <div>
        <p class="eyebrow">运行观测</p>
        <h2 id="control-center-heading">{{ section === "traces" ? "追踪" : section === "audit" ? "审计" : "概览" }}</h2>
        <p>{{ section === "traces" ? "当前空间的运行列表。不展示 Prompt、回答或证据正文。" : section === "audit" ? "脱敏反馈与审计索引，只保留标识、时间和摘要哈希。" : "同一时间窗内的健康、运行计数和用量。" }}</p>
      </div>
      <div class="read-only-note" :class="{ warning: !canManage }">{{ canManage ? "空间管理员：可重试失败运行" : "当前角色：只读" }}</div>
    </div>
    <nav class="control-nav" aria-label="运行观测">
      <button type="button" :class="{ active: section === 'overview' }" @click="section = 'overview'">概览</button>
      <button type="button" :class="{ active: section === 'traces' }" @click="section = 'traces'">追踪</button>
      <button type="button" :class="{ active: section === 'audit' }" @click="section = 'audit'">审计</button>
    </nav>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p v-if="notice" class="alert success" role="status">{{ notice }}</p>

    <div class="window-bar card">
      <div class="field"><label for="observe-from">从</label><input id="observe-from" v-model="managementFrom" /></div>
      <div class="field"><label for="observe-to">到</label><input id="observe-to" v-model="managementTo" /></div>
      <button type="button" class="secondary-button" :disabled="loading" @click="loadSection">刷新</button>
    </div>

    <div v-if="section === 'overview'" class="entry-grid">
      <article v-if="managementHealth" class="card entry-card"><span class="card-label">模型服务</span><strong>{{ managementHealth.providers.active }} / {{ managementHealth.providers.total }} 启用</strong><p>异常 {{ managementHealth.providers.unhealthy }} · 停用 {{ managementHealth.providers.disabled }}</p></article>
      <article v-if="managementHealth" class="card entry-card"><span class="card-label">运行</span><strong>{{ managementHealth.runs.succeeded }} 已完成</strong><p>失败 {{ managementHealth.runs.failed }} · 处理中 {{ managementHealth.runs.inFlight }} · 总数 {{ managementHealth.runs.total }}</p></article>
      <article class="card list-card span-2">
        <div class="card-title"><h3>用量</h3></div>
        <p v-if="!managementCost?.items.length" class="empty-state">当前时间窗没有用量记录。</p>
        <div v-for="item in managementCost?.items ?? []" :key="`${item.usageSource}-${item.currency}`" class="list-row">
          <div><strong>{{ item.usageSource }} · {{ item.currency }}</strong><span>{{ item.ledgerEntries }} 条 · {{ item.totalTokens }} 词元</span></div>
          <strong>{{ item.estimatedCost }}</strong>
        </div>
      </article>
    </div>

    <div v-else-if="section === 'traces'" class="trace-layout">
      <div class="card list-card">
        <div class="card-title">
          <h3>运行</h3>
          <select v-model="statusFilter" aria-label="按状态筛选">
            <option value="">全部状态</option>
            <option value="SUCCEEDED">已完成</option>
            <option value="FAILED">失败</option>
            <option value="RUNNING">运行中</option>
            <option value="CANCELLED">已取消</option>
            <option value="QUEUED">排队</option>
          </select>
        </div>
        <p v-if="!runs.length" class="empty-state">当前时间窗没有运行记录。</p>
        <button v-for="item in runs" :key="item.runId" type="button" class="list-row trace-row" :class="{ active: selectedRunId === item.runId }" @click="loadRun(item.runId)">
          <div>
            <strong>{{ stateLabel(item.status) }}</strong>
            <span>{{ formatDate(item.createdAt) }} · 会话 {{ item.conversationId.slice(0, 8) }}</span>
          </div>
          <span class="tag">{{ item.error?.errorClass ?? "OK" }}</span>
        </button>
        <button v-if="runsCursor" type="button" class="quiet-button" :disabled="loading" @click="loadMoreRuns">加载更多</button>
      </div>
      <article v-if="runSnapshot" class="card run-card">
        <div class="card-title"><div><span class="card-label">运行</span><code>{{ runSnapshot.runId }}</code></div><span class="state-pill">{{ stateLabel(runSnapshot.status) }}</span></div>
        <dl class="details">
          <dt>会话</dt><dd><code>{{ runSnapshot.conversationId }}</code></dd>
          <dt>时间</dt><dd>{{ formatDate(runSnapshot.createdAt) }} / {{ formatDate(runSnapshot.finishedAt) }}</dd>
        </dl>
        <p v-if="runSnapshot.error" class="alert error">{{ runSnapshot.error.message }}</p>
        <div class="button-row">
          <button type="button" :disabled="loading || !runSnapshot.error?.retryable || !canManage" @click="retrySelected">重试</button>
        </div>
        <h3 class="steps-heading">步骤</h3>
        <div v-for="step in runSnapshot.steps" :key="step.stepId" class="list-row">
          <div><strong>#{{ step.sequence }} · {{ step.type }}</strong><span>{{ formatDate(step.createdAt) }}</span></div>
          <span class="state-pill">{{ stateLabel(step.status) }}</span>
        </div>
      </article>
      <div v-else class="empty-state card"><strong>选择一条运行</strong><span>列表只含标识与状态，不含回答正文。</span></div>
    </div>

    <div v-else class="audit-grid">
      <div class="card list-card">
        <div class="card-title"><h3>答案反馈</h3></div>
        <p v-if="!managementFeedback.length" class="empty-state">没有反馈。</p>
        <div v-for="item in managementFeedback" :key="item.id" class="list-row">
          <div><strong>{{ item.sentiment }}</strong><span>运行 {{ item.runId }}</span></div>
          <span class="muted">{{ formatDate(item.createdAt) }}</span>
        </div>
      </div>
      <div class="card list-card">
        <div class="card-title"><h3>脱敏审计</h3></div>
        <p v-if="!managementAudit.length" class="empty-state">没有审计记录。</p>
        <div v-for="item in managementAudit" :key="item.id" class="list-row">
          <div><strong>{{ item.eventType }}</strong><span>{{ formatDate(item.occurredAt) }}</span></div>
          <span class="audit-hash">{{ item.payloadSha256 }}</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.control-center { margin-top: 0; }
.control-nav { display: flex; flex-wrap: wrap; gap: 6px; margin: 0 0 18px; }
.control-nav button { border: 1px solid #e5e7eb; background: #fff; color: #374151; box-shadow: none; }
.control-nav button.active { border-color: #111827; background: #111827; color: #fff; }
.window-bar { display: grid; grid-template-columns: 1fr 1fr auto; gap: 12px; align-items: end; margin-bottom: 16px; }
.entry-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.span-2 { grid-column: span 2; }
.trace-layout, .audit-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr); gap: 16px; }
.list-row { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 10px; padding: 12px; border: 1px solid #e3eaf4; border-radius: 10px; background: #f8fbff; }
.list-row strong, .list-row span { display: block; overflow-wrap: anywhere; }
.list-row span { margin-top: 4px; color: #71809a; font-size: .78rem; }
.trace-row { width: 100%; text-align: left; cursor: pointer; }
.trace-row.active { border-color: #111827; }
.audit-hash { max-width: 280px; overflow-wrap: anywhere; font-family: "Cascadia Code", Consolas, monospace; font-size: .72rem; color: #71809a; }
.steps-heading { margin-top: 24px; }
@media (max-width: 850px) {
  .window-bar, .entry-grid, .trace-layout, .audit-grid { grid-template-columns: 1fr; }
  .span-2 { grid-column: auto; }
}
</style>
