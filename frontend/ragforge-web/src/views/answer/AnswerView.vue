<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ApiError, ANSWER_READINESS_LABELS, apiFetch, getAnswerReadiness, listAllCursorPages, type AnswerDefaults, type AnswerReadiness, type ModelProfile, type ModelRoute, type ProvenanceContext, type SpaceBinding } from "../../api";
import { AnswerStreamError, archiveAnswerConversation, cancelAnswerRun, consumeAnswerStream, createAnswer, createAnswerConversation, createAnswerRun, deleteAnswerConversation, getAnswerProjection, listAnswerConversations, listConversationRuns, previewCitation, renameAnswerConversation, submitAnswerFeedback, type AnswerAbstentionReason, type AnswerCitation, type AnswerDonePayload, type AnswerErrorCode, type AnswerEvent, type AnswerToolPayload, type AnswerUsagePayload, type CitationPreviewResult, type ConversationHistoryItem, type ConversationRunItem } from "../../answer";
import { type RunProjection } from "../../answer";

const props = defineProps<{ selectedSpaceId: string; defaults?: AnswerDefaults | null; initialConversationId?: string; initialRunId?: string }>();
const emit = defineEmits<{ "run-created": [runId: string]; "conversation-created": [conversationId: string]; "open-context": [context: ProvenanceContext]; "follow-gap": [path: string] }>();

type UiStatus = "empty" | "loading" | "reconnecting" | "completed" | "abstained" | "failed" | "cancelled" | "degraded" | "timeout" | "cancelling";

interface RunContext {
  spaceId: string;
  runId: string;
  correlationId: string;
}

interface EventLogEntry {
  eventType: AnswerEvent["eventType"];
  sequence: number;
  eventId: string;
  spaceId: string;
  runId: string;
  correlationId: string;
}

interface AbstentionState {
  reasonCode: AnswerAbstentionReason;
  context: RunContext;
}

interface ErrorState {
  code: AnswerErrorCode;
  retryable: boolean;
  context: RunContext;
}

interface UsageState {
  payload: AnswerUsagePayload;
  context: RunContext;
}

interface ToolState {
  payload: AnswerToolPayload;
  context: RunContext;
}

const question = ref("");
const expertOpen = ref(false);
const readiness = ref<AnswerReadiness | null>(null);
const readinessError = ref("");
const conversationId = ref(props.initialConversationId ?? "");
const routeVersionId = ref("");
const profileVersionId = ref("");
const providerConnectionId = ref("");
const promptVersionId = ref("");
const model = ref("");
const datasetHash = ref("");
const configHash = ref("");
const allowCloudEgress = ref(false);
const timeoutSeconds = ref(120);
const status = ref<UiStatus>("empty");
const answerText = ref("");
const citations = ref<AnswerCitation[]>([]);
const eventLog = ref<EventLogEntry[]>([]);
const abstention = ref<AbstentionState | null>(null);
const errorState = ref<ErrorState | null>(null);
const usage = ref<UsageState | null>(null);
const tools = ref<ToolState[]>([]);
const runContext = ref<RunContext | null>(null);
const lastEventId = ref<string | null>(null);
const lastSequence = ref(0);
const notice = ref("");
const formError = ref("");
const cancelError = ref("");
const previewNotice = ref("");
const previewResult = ref<CitationPreviewResult | null>(null);
const previewingEvidenceId = ref<string | null>(null);
const feedbackNotice = ref("");
const feedbackBusy = ref<string | null>(null);
const cancelRequested = ref(false);
const streamAttempts = ref(0);
const history = ref<ConversationHistoryItem[]>([]);
const historyRuns = ref<ConversationRunItem[]>([]);
const includeArchived = ref(false);
const historyLoading = ref(false);
const historyError = ref("");
const historySearch = ref("");
const pageSize = 10;
const historyPage = ref(1);
const historyRunsPage = ref(1);
const filteredHistory = computed(() => {
  const query = historySearch.value.trim().toLocaleLowerCase();
  if (!query) return history.value;
  return history.value.filter((item) => [item.id, item.title, item.status].join(" ").toLocaleLowerCase().includes(query));
});
const historyPageCount = computed(() => Math.max(1, Math.ceil(filteredHistory.value.length / pageSize)));
const visibleHistory = computed(() => filteredHistory.value.slice((historyPage.value - 1) * pageSize, historyPage.value * pageSize));
const historyRunsPageCount = computed(() => Math.max(1, Math.ceil(historyRuns.value.length / pageSize)));
const visibleHistoryRuns = computed(() => historyRuns.value.slice((historyRunsPage.value - 1) * pageSize, historyRunsPage.value * pageSize));
const selectedHistory = computed(() => history.value.find((item) => item.id === conversationId.value) ?? null);
const selectedConversationArchived = computed(() => selectedHistory.value?.status === "ARCHIVED");

watch(() => props.defaults, (defaults) => {
  if (!defaults) return;
  routeVersionId.value = defaults.routeVersionId;
  profileVersionId.value = defaults.profileVersionId;
  providerConnectionId.value = defaults.providerConnectionId;
  promptVersionId.value = defaults.promptVersionId;
  model.value = defaults.model;
  datasetHash.value = defaults.datasetHash;
  configHash.value = defaults.configHash;
  allowCloudEgress.value = defaults.allowCloudEgress;
  timeoutSeconds.value = defaults.allowCloudEgress ? 60 : 120;
}, { immediate: true });

let abortController: AbortController | null = null;
let cancelPromise: Promise<void> | null = null;
let runIdempotencyKey = "";
let cancelIdempotencyKey = "";
const seenEventIds = new Set<string>();

const isActive = computed(() => status.value === "loading" || status.value === "reconnecting" || status.value === "degraded" || status.value === "cancelling");
const nextGap = computed(() => readiness.value?.gaps[0] ?? null);
const spaceReady = computed(() => Boolean(readiness.value?.ready));
const canSubmit = computed(() => Boolean(props.selectedSpaceId && spaceReady.value && !isActive.value && !selectedConversationArchived.value));
const statusLabel = computed(() => {
  if (status.value === "abstained") return abstentionTitle(abstention.value?.reasonCode ?? "");
  return ({ empty: "等待提问", loading: "回答生成中", reconnecting: "连接恢复中", completed: "已完成", abstained: "无法安全回答", failed: "回答失败", cancelled: "已取消", degraded: "服务降级", timeout: "请求超时", cancelling: "正在取消" })[status.value];
});
const contextLabel = computed(() => runContext.value ? `space ${runContext.value.spaceId} · run ${runContext.value.runId} · correlation ${runContext.value.correlationId}` : "尚未创建 run");
const hasRuntimeDefaults = computed(() => Boolean(routeVersionId.value && profileVersionId.value && providerConnectionId.value && promptVersionId.value && model.value && datasetHash.value && configHash.value));

function createKey(prefix: string): string {
  return `${prefix}-${crypto.randomUUID().replaceAll("-", "")}`;
}

function safeApiError(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError)) return fallback;
  if (error.status === 401) return "当前会话已失效，请重新登录。";
  if (error.status === 403) return "当前空间不允许此操作；权限由服务端最终裁决。";
  if (error.status === 404) return "请求的空间或运行不存在，请确认当前空间和版本配置。";
  if (error.status === 409) return "请求状态已变化，请重新开始一次回答。";
  if (error.status === 422) return "回答配置未通过契约校验，请检查版本 ID。";
  return fallback;
}

function contextOf(event: AnswerEvent): RunContext {
  return { spaceId: event.spaceId, runId: event.runId, correlationId: event.correlationId };
}

function isTerminal(): boolean {
  return status.value === "completed" || status.value === "abstained" || status.value === "failed" || status.value === "cancelled" || status.value === "timeout";
}

function eventLabel(eventType: AnswerEvent["eventType"]): string {
  return ({ "answer.delta": "回答增量", "answer.citation": "结构化引用", "answer.abstention": "安全拒答", "answer.tool": "只读工具", "answer.usage": "用量记录", "answer.error": "安全错误", "answer.done": "回答完成", "run.snapshot": "运行快照" })[eventType];
}

function safeErrorLabel(code: AnswerErrorCode): string {
  return ({ PROVIDER_UNAVAILABLE: "本地服务暂不可用", TIMEOUT: "模型调用超时", SPACE_EGRESS_DENIED: "空间出境策略拒绝", EVIDENCE_INVALID: "证据校验失败", TOOL_FAILURE: "只读工具失败", CANCELLED: "运行已取消", INTERNAL_ERROR: "服务内部错误" })[code];
}

function safeAbstentionLabel(reasonCode: AnswerAbstentionReason | ""): string {
  return ({ NO_EVIDENCE: "当前空间没有足够证据", LOW_CONFIDENCE: "证据置信度不足", EVIDENCE_CONFLICT: "当前证据相互冲突", POLICY_BLOCKED: "当前问题被空间策略拒绝", SPACE_ACCESS_DENIED: "当前空间访问策略不允许回答", TOOL_UNAUTHORIZED: "所需只读工具未获授权", TOOL_FAILURE: "只读工具未能提供可靠结果", PROVIDER_UNAVAILABLE: "回答服务暂不可用", CANCELLED: "回答已取消" } satisfies Record<AnswerAbstentionReason, string>)[reasonCode as AnswerAbstentionReason] ?? "当前问题无法安全回答";
}

function abstentionTitle(reasonCode: AnswerAbstentionReason | ""): string {
  if (reasonCode === "NO_EVIDENCE") return "知识库没有相关证据";
  if (reasonCode === "LOW_CONFIDENCE") return "证据相关性不足";
  if (reasonCode === "POLICY_BLOCKED" || reasonCode === "SPACE_ACCESS_DENIED") return "策略拒答";
  return "安全拒答";
}

function safeAbstentionDetail(reasonCode: AnswerAbstentionReason | ""): string {
  return ({ NO_EVIDENCE: "没有足够的当前空间证据支持安全回答。", LOW_CONFIDENCE: "检索到的证据置信度不足，客户端不会补写或猜测答案。", EVIDENCE_CONFLICT: "检索到的证据存在冲突，客户端不会替用户选择未经验证的版本。", POLICY_BLOCKED: "当前问题触发了服务端空间策略拒答。", SPACE_ACCESS_DENIED: "当前会话无法访问回答所需的空间内容。", TOOL_UNAUTHORIZED: "回答所需的只读工具未通过服务端授权。", TOOL_FAILURE: "只读工具执行失败，客户端不会把不完整结果当作答案依据。", PROVIDER_UNAVAILABLE: "回答服务当前不可用，客户端不会静默切换到云端路由。", CANCELLED: "回答在完成前被取消，后续增量不会继续展示。" } satisfies Record<AnswerAbstentionReason, string>)[reasonCode as AnswerAbstentionReason] ?? "客户端未收到可安全解释的拒答原因。";
}

function anchorLabel(anchor: AnswerCitation["anchor"]): string {
  const parts = [anchor.headingPath.join(" / ") || "未提供标题路径"];
  if (anchor.pageNumber !== null) parts.push(`第 ${anchor.pageNumber} 页`);
  if (anchor.sheet) parts.push(`工作表 ${anchor.sheet}`);
  if (anchor.slideNumber !== null) parts.push(`第 ${anchor.slideNumber} 张幻灯片`);
  if (anchor.lineStart !== null && anchor.lineEnd !== null) parts.push(`行 ${anchor.lineStart}–${anchor.lineEnd}`);
  if (anchor.tableCell) parts.push(`单元格 ${anchor.tableCell}`);
  return parts.join(" · ");
}

function processEvent(event: AnswerEvent): void {
  const activeContext = runContext.value;
  if (!activeContext || event.spaceId !== activeContext.spaceId || event.runId !== activeContext.runId) return;
  if (seenEventIds.has(event.eventId) || event.sequence <= lastSequence.value) return;
  seenEventIds.add(event.eventId);
  lastSequence.value = event.sequence;
  lastEventId.value = event.eventId;
  eventLog.value.push({ eventType: event.eventType, sequence: event.sequence, eventId: event.eventId, spaceId: event.spaceId, runId: event.runId, correlationId: event.correlationId });
  const eventContext = contextOf(event);
  if (event.eventType === "run.snapshot") return;
  if (event.eventType === "answer.delta") {
    if (!cancelRequested.value && !isTerminal() && "delta" in event.payload) answerText.value += event.payload.delta;
    return;
  }
  if (event.eventType === "answer.citation") {
    if (!cancelRequested.value && !isTerminal() && "citation" in event.payload && !citations.value.some((citation) => citation.evidenceId === event.payload.citation.evidenceId)) citations.value.push(event.payload.citation);
    return;
  }
  if (event.eventType === "answer.abstention" && "reasonCode" in event.payload) {
    abstention.value = { reasonCode: event.payload.reasonCode, context: eventContext };
    return;
  }
  if (event.eventType === "answer.tool" && "toolName" in event.payload) {
    tools.value.push({ payload: event.payload, context: eventContext });
    return;
  }
  if (event.eventType === "answer.usage" && "inputTokens" in event.payload) {
    usage.value = { payload: event.payload, context: eventContext };
    return;
  }
  if (event.eventType === "answer.error" && "code" in event.payload) {
    errorState.value = { code: event.payload.code, retryable: event.payload.retryable, context: eventContext };
    if (event.payload.code === "TIMEOUT") status.value = "timeout";
    else if (event.payload.code === "PROVIDER_UNAVAILABLE" || event.payload.code === "SPACE_EGRESS_DENIED") status.value = "degraded";
    else if (event.payload.code !== "CANCELLED") status.value = "failed";
    if (event.payload.code !== "PROVIDER_UNAVAILABLE") abortController?.abort();
    return;
  }
  if (event.eventType === "answer.done" && "status" in event.payload) {
    applyDone(event.payload);
  }
}

function applyDone(payload: AnswerDonePayload): void {
  if (payload.status === "COMPLETED") status.value = "completed";
  if (payload.status !== "COMPLETED") {
    // Provider deltas are provisional until the server validates the complete
    // structured answer and its citation allow-list. Never retain them for a
    // refused, failed or cancelled terminal projection.
    answerText.value = "";
    citations.value = [];
  }
  if (payload.status === "ABSTAINED") status.value = "abstained";
  if (payload.status === "FAILED") status.value = "failed";
  if (payload.status === "CANCELLED") {
    status.value = "cancelled";
    cancelRequested.value = true;
  }
  abortController?.abort();
}

function resetAnswer(): void {
  abortController?.abort();
  abortController = null;
  cancelPromise = null;
  runIdempotencyKey = "";
  cancelIdempotencyKey = "";
  seenEventIds.clear();
  status.value = "empty";
  answerText.value = "";
  citations.value = [];
  eventLog.value = [];
  abstention.value = null;
  errorState.value = null;
  usage.value = null;
  tools.value = [];
  runContext.value = null;
  lastEventId.value = null;
  lastSequence.value = 0;
  notice.value = "";
  formError.value = "";
  cancelError.value = "";
  previewNotice.value = "";
  previewResult.value = null;
  feedbackNotice.value = "";
  feedbackBusy.value = null;
  cancelRequested.value = false;
  streamAttempts.value = 0;
}

function setHistoryPage(nextPage: number): void {
  historyPage.value = Math.min(Math.max(nextPage, 1), historyPageCount.value);
}

function setHistoryRunsPage(nextPage: number): void {
  historyRunsPage.value = Math.min(Math.max(nextPage, 1), historyRunsPageCount.value);
}

async function loadHistory(): Promise<void> {
  if (!props.selectedSpaceId) return;
  historyLoading.value = true; historyError.value = "";
  try {
    history.value = await listAnswerConversations(props.selectedSpaceId, includeArchived.value);
    setHistoryPage(historyPage.value);
    if (conversationId.value) {
      historyRuns.value = await listConversationRuns(props.selectedSpaceId, conversationId.value);
      setHistoryRunsPage(historyRunsPage.value);
    }
  } catch (error) { historyError.value = safeApiError(error, "问答历史加载失败。"); }
  finally { historyLoading.value = false; }
}

async function loadHistoricalAnswer(runId: string, conversationIdHint?: string): Promise<void> {
  try {
    const answer = await getAnswerProjection(props.selectedSpaceId, runId);
    if (answer.spaceId !== props.selectedSpaceId || answer.runId !== runId) throw new Error("历史回答不属于当前空间");
    conversationId.value = conversationIdHint ?? conversationId.value;
    runContext.value = { spaceId: answer.spaceId, runId: answer.runId, correlationId: answer.correlationId };
    answerText.value = answer.answerText ?? "";
    citations.value = (answer.citations ?? []).filter((citation) => citation.spaceId === props.selectedSpaceId && citation.runId === runId && citation.citationAllowed === true);
    status.value = answer.status === "COMPLETED" ? "completed" : answer.status === "ABSTAINED" ? "abstained" : answer.status === "CANCELLED" ? "cancelled" : "failed";
    notice.value = selectedConversationArchived.value ? "已载入归档会话的历史回答；归档会话不能继续提问。" : "已载入历史回答，可以在当前会话继续追问。";
  } catch (error) { historyError.value = safeApiError(error, "历史回答尚未生成或无法读取。请刷新后重试。"); }
}

async function selectHistory(item: ConversationHistoryItem): Promise<void> {
  if (isActive.value) return;
  resetAnswer(); conversationId.value = item.id; historyRuns.value = []; historyRunsPage.value = 1;
  try {
    historyRuns.value = await listConversationRuns(props.selectedSpaceId, item.id);
    setHistoryRunsPage(historyRunsPage.value);
    if (historyRuns.value[0]) await loadHistoricalAnswer(historyRuns.value[0].runId, item.id);
    else notice.value = "已打开会话，但还没有可查看的运行记录。";
  }
  catch (error) { historyError.value = safeApiError(error, "会话运行记录加载失败。"); }
}

async function loadHistoricalRun(run: ConversationRunItem): Promise<void> {
  await loadHistoricalAnswer(run.runId, run.conversationId);
}

async function archiveHistory(item: ConversationHistoryItem): Promise<void> {
  if (item.status === "ARCHIVED") return;
  try {
    await archiveAnswerConversation(props.selectedSpaceId, item.id, createKey("archive-conversation"));
    if (conversationId.value === item.id) { resetAnswer(); conversationId.value = ""; }
    await loadHistory();
  } catch (error) { historyError.value = safeApiError(error, "会话归档失败。"); }
}

async function renameHistory(item: ConversationHistoryItem): Promise<void> {
  const title = window.prompt("重命名会话", item.title)?.trim();
  if (!title || title === item.title) return;
  try {
    await renameAnswerConversation(props.selectedSpaceId, item.id, title, item.version ?? 0, createKey("rename-conversation"));
    await loadHistory();
  } catch (error) { historyError.value = safeApiError(error, "会话重命名失败；版本可能已变化，请刷新后重试。"); }
}

async function deleteHistory(item: ConversationHistoryItem): Promise<void> {
  if (!window.confirm("删除会话会将其从默认历史列表移除，并保留审计记录。继续吗？")) return;
  try {
    await deleteAnswerConversation(props.selectedSpaceId, item.id, item.version ?? 0, createKey("delete-conversation"));
    if (conversationId.value === item.id) { resetAnswer(); conversationId.value = ""; }
    await loadHistory();
  } catch (error) { historyError.value = safeApiError(error, "会话删除失败；版本可能已变化，请刷新后重试。"); }
}

function validateStart(): string | null {
  if (!props.selectedSpaceId) return "请先在页面顶部选择当前空间。";
  if (selectedConversationArchived.value) return "当前会话已归档，只能查看历史回答；请点击“新会话”后继续提问。";
  if (!question.value.trim()) return "请输入问题。";
  if (!spaceReady.value) return nextGap.value ? ANSWER_READINESS_LABELS[nextGap.value.code] : "当前空间还不能提问。";
  if (expertOpen.value) {
    if (!routeVersionId.value.trim() || !profileVersionId.value.trim() || !providerConnectionId.value.trim() || !promptVersionId.value.trim() || !model.value.trim()) {
      return "专家设置开启后需要填写 route、profile、provider connection、prompt 和 model。";
    }
  }
  if (!Number.isInteger(timeoutSeconds.value) || timeoutSeconds.value < 1 || timeoutSeconds.value > 120) return "timeoutSeconds 必须是 1–120 的整数。";
  return null;
}

async function loadReadiness(): Promise<void> {
  if (!props.selectedSpaceId) {
    readiness.value = null;
    return;
  }
  readinessError.value = "";
  try {
    readiness.value = await getAnswerReadiness(props.selectedSpaceId);
    if (readiness.value.ready) await bindSilentDefaults(props.selectedSpaceId);
  } catch (error) {
    readiness.value = null;
    readinessError.value = safeApiError(error, "无法读取回答就绪状态。");
  }
}

async function bindSilentDefaults(spaceId: string): Promise<void> {
  const binding = await apiFetch<SpaceBinding>(`/api/v1/spaces/${encodeURIComponent(spaceId)}/space-bindings`);
  if (!routeVersionId.value) routeVersionId.value = binding.chatRouteId;
  if (!promptVersionId.value) promptVersionId.value = binding.promptVersionId;
  allowCloudEgress.value = binding.cloudEgressEnabled;
  const [routes, profiles] = await Promise.all([
    listAllCursorPages<ModelRoute>(`/api/v1/spaces/${encodeURIComponent(spaceId)}/model-routes`, { limit: 10 }),
    listAllCursorPages<ModelProfile>(`/api/v1/spaces/${encodeURIComponent(spaceId)}/model-profiles`, { limit: 10 }),
  ]);
  const route = routes.find((item) => item.modelRouteId === binding.chatRouteId);
  const candidate = route?.candidates[0];
  if (candidate && !profileVersionId.value) profileVersionId.value = candidate.modelProfileId;
  const profile = profiles.find((item) => item.modelProfileId === profileVersionId.value);
  if (profile) {
    if (!providerConnectionId.value) providerConnectionId.value = profile.providerConnectionId;
    if (!model.value) model.value = profile.modelName;
  }
}

function conversationIdentifier(projection: { conversationId?: string; id?: string }): string | null {
  return projection.conversationId ?? projection.id ?? null;
}

function runIdentifier(projection: RunProjection): string | null {
  return typeof projection.runId === "string" ? projection.runId : null;
}

async function startAnswer(): Promise<void> {
  formError.value = "";
  cancelError.value = "";
  notice.value = "";
  if (isActive.value) return;
  const validationError = validateStart();
  if (validationError) { formError.value = validationError; return; }
  const spaceIdAtStart = props.selectedSpaceId;
  resetAnswer();
  status.value = "loading";
  runIdempotencyKey = createKey("answer-run");
  try {
    let activeConversationId = conversationId.value.trim();
    if (!activeConversationId) {
      const conversation = await createAnswerConversation(spaceIdAtStart, `${runIdempotencyKey}-conversation`);
      activeConversationId = conversationIdentifier(conversation) ?? "";
    }
    if (!activeConversationId) throw new Error("conversation unavailable");
    conversationId.value = activeConversationId;
    emit("conversation-created", activeConversationId);
    if (props.selectedSpaceId !== spaceIdAtStart) throw new Error("space changed during answer start");
    const runRequest = {
      routeVersionId: routeVersionId.value.trim(),
      profileVersionId: profileVersionId.value.trim(),
      providerConnectionId: providerConnectionId.value.trim(),
      promptVersionId: promptVersionId.value.trim(),
      model: model.value.trim(),
      message: question.value.trim(),
      timeoutSeconds: timeoutSeconds.value,
      allowCloudEgress: allowCloudEgress.value,
    };
    const answerRequest = expertOpen.value
      ? { ...runRequest, maxContextTokens: 4000 }
      : { message: question.value.trim(), timeoutSeconds: timeoutSeconds.value, allowCloudEgress: allowCloudEgress.value };
    const run = await createAnswerRun(spaceIdAtStart, activeConversationId, runRequest, runIdempotencyKey);
    const runId = runIdentifier(run);
    if (!runId || run.spaceId !== spaceIdAtStart || props.selectedSpaceId !== spaceIdAtStart || !run.correlationId) throw new Error("run context unavailable");
    emit("run-created", runId);
    runContext.value = { spaceId: spaceIdAtStart, runId, correlationId: run.correlationId };
    cancelIdempotencyKey = createKey(`answer-cancel-${runId}`);
    // Open SSE before the synchronous answer projection is created. The
    // server may publish terminal answer events during createAnswer; opening
    // first preserves those events for the live subscriber and avoids a
    // late-stream snapshot hiding the completed result.
    const stream = streamRun(runContext.value, timeoutSeconds.value);
    await createAnswer(spaceIdAtStart, { ...answerRequest, runId, correlationId: run.correlationId }, createKey(`answer-create-${runId}`));
    await stream;
    await loadHistory();
  } catch (error) {
    abortController?.abort();
    if (abortController?.signal.aborted && isTerminal()) return;
    status.value = "failed";
    formError.value = safeApiError(error, "回答启动失败；未显示服务端原始响应。请检查配置后重试。");
  }
}

function waitForReconnect(delayMs: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = window.setTimeout(resolve, delayMs);
    signal.addEventListener("abort", () => { window.clearTimeout(timer); resolve(); }, { once: true });
  });
}

async function streamRun(context: RunContext, timeout: number): Promise<void> {
  abortController = new AbortController();
  const signal = abortController.signal;
  const timeoutTimer = window.setTimeout(() => {
    if (!isTerminal() && !cancelRequested.value) {
      status.value = "timeout";
      notice.value = "回答连接已超时；服务端运行未被前端静默改路由，请按需取消或重新发起。";
      abortController?.abort();
    }
  }, Math.max(1, timeout) * 1000);
  try {
    while (!signal.aborted && !isTerminal()) {
      try {
        await consumeAnswerStream({ spaceId: context.spaceId, runId: context.runId, correlationId: context.correlationId, lastEventId: lastEventId.value, signal, onEvent: processEvent });
        if (signal.aborted || isTerminal()) return;
        throw new AnswerStreamError(null);
      } catch (error) {
        if (signal.aborted || isTerminal()) return;
        streamAttempts.value += 1;
        if (streamAttempts.value > 3) {
          status.value = "failed";
          notice.value = "事件连接多次恢复失败；可以使用相同空间配置重新发起回答。";
          return;
        }
        status.value = "degraded";
        notice.value = error instanceof AnswerStreamError && error.status === 403 ? "当前空间拒绝事件流访问。" : "事件连接短暂中断，正在使用 Last-Event-ID 恢复。";
        await waitForReconnect(500 * 2 ** (streamAttempts.value - 1), signal);
        if (!signal.aborted) status.value = "reconnecting";
      }
    }
  } finally {
    window.clearTimeout(timeoutTimer);
  }
}

async function cancelAnswer(): Promise<void> {
  if (!runContext.value || cancelPromise || cancelRequested.value || !isActive.value) return;
  const context = runContext.value;
  cancelRequested.value = true;
  status.value = "cancelling";
  cancelError.value = "";
  notice.value = "取消请求已提交；后续回答增量会被丢弃，等待服务端确认。";
  cancelPromise = (async () => {
    try {
      const response = await cancelAnswerRun(context.spaceId, context.runId, cancelIdempotencyKey);
      if (response.spaceId !== context.spaceId || response.runId !== context.runId) throw new Error("cancel context mismatch");
      if (response.status === "CANCELLED") applyDone({ answerId: "unknown-answer", status: "CANCELLED" });
    } catch (error) {
      cancelRequested.value = false;
      status.value = "failed";
      cancelError.value = safeApiError(error, "取消请求未确认；未显示服务端原始响应，请稍后重试。重复点击不会创建新的取消意图。" );
    } finally {
      cancelPromise = null;
    }
  })();
  await cancelPromise;
}

async function previewCitationWithAuthorization(citation: AnswerCitation): Promise<CitationPreviewResult | null> {
  if (!runContext.value || previewingEvidenceId.value) return null;
  previewingEvidenceId.value = citation.evidenceId;
  previewNotice.value = "";
  previewResult.value = null;
  try {
    const result: CitationPreviewResult = await previewCitation(runContext.value.spaceId, runContext.value.runId, citation.evidenceId);
    previewResult.value = result;
    previewNotice.value = "服务端已重新鉴权该引用；以下仅显示结构化 provenance，不渲染正文。";
    return result;
  } catch (error) {
    previewNotice.value = safeApiError(error, "引用暂不可打开；当前权限或证据版本不允许预览。");
    return null;
  } finally {
    previewingEvidenceId.value = null;
  }
}

async function openCitation(citation: AnswerCitation): Promise<void> {
  await previewCitationWithAuthorization(citation);
}

async function openCitationInStudio(citation: AnswerCitation): Promise<void> {
  const preview = await previewCitationWithAuthorization(citation);
  if (!preview) return;
  emit("open-context", { target: "studio", spaceId: preview.spaceId, childChunkId: preview.childChunkId,
    documentRevisionId: preview.documentRevisionId, contentRef: preview.contentRef, textHash: preview.textHash });
}

async function submitFeedback(citation: AnswerCitation, sentiment: "HELPFUL" | "NOT_HELPFUL"): Promise<void> {
  const context = runContext.value;
  if (!context || context.spaceId !== props.selectedSpaceId || feedbackBusy.value) return;
  feedbackBusy.value = citation.evidenceId;
  feedbackNotice.value = "";
  try {
    await submitAnswerFeedback(context.spaceId, context.runId, { evidenceId: citation.evidenceId, sentiment }, createKey("answer-feedback"));
    feedbackNotice.value = "反馈已保存到当前空间的该次回答与证据。";
  } catch (error) {
    feedbackNotice.value = safeApiError(error, "反馈未保存；服务端会拒绝跨空间或不存在的证据。");
  } finally { feedbackBusy.value = null; }
}

function newConversation(): void {
  if (isActive.value) return;
  resetAnswer();
  conversationId.value = "";
  historyRuns.value = [];
  historyRunsPage.value = 1;
  question.value = "";
  notice.value = "已开启新会话；提交问题后服务端会创建会话。";
}

async function restoreInitialRun(): Promise<void> {
  await loadHistory();
  if (props.initialRunId && props.selectedSpaceId) await loadHistoricalAnswer(props.initialRunId, conversationId.value || undefined);
}

watch(() => props.selectedSpaceId, (next, previous) => {
  if (next !== previous) { resetAnswer(); conversationId.value = ""; historyRuns.value = []; historyPage.value = 1; historyRunsPage.value = 1; void loadHistory(); void loadReadiness(); }
});
watch(historySearch, () => { historyPage.value = 1; });
watch(includeArchived, () => { historyPage.value = 1; historyRunsPage.value = 1; void loadHistory(); });
watch(() => props.initialRunId, (next) => { if (next && props.selectedSpaceId) void loadHistoricalAnswer(next, conversationId.value || undefined); });
onMounted(() => { void restoreInitialRun(); void loadReadiness(); });
onBeforeUnmount(() => abortController?.abort());
</script>

<template>
  <div class="answer-workspace">
  <aside class="card answer-history" aria-label="问答历史"><div class="card-title"><div><span class="card-label">历史记录</span><h3>查找并继续已有会话</h3><p class="muted history-intro">点击一行会自动打开最近一次回答；归档记录仍可查看。</p></div><div class="history-actions"><button type="button" class="secondary-button" :disabled="isActive" @click="newConversation">新建会话</button><label class="history-toggle"><input v-model="includeArchived" type="checkbox" />显示归档</label></div></div><div class="history-search"><label for="answer-history-search">搜索会话</label><input id="answer-history-search" v-model="historySearch" maxlength="120" placeholder="按标题、状态或标识搜索" /></div><p v-if="historyError" class="alert error">{{ historyError }}</p><p v-if="historyLoading" class="muted">正在读取历史记录…</p><div v-else-if="!history.length" class="muted">还没有问答历史。点击“新建会话”后提交第一个问题，会话会自动出现在这里。</div><div v-else-if="!filteredHistory.length" class="muted">没有匹配的会话，请换一个关键词。</div><div v-else class="history-table-wrap"><table class="history-table" aria-label="问答历史表格"><thead><tr><th scope="col">会话</th><th scope="col">状态</th><th scope="col">最近更新</th><th scope="col">操作</th></tr></thead><tbody><template v-for="item in visibleHistory" :key="item.id"><tr :class="{ selected: conversationId === item.id }"><td><button type="button" class="history-select" @click="selectHistory(item)"><strong>{{ item.title }}</strong><small>{{ item.id }}</small></button></td><td><span class="history-status" :class="item.status.toLowerCase()">{{ item.status === "ARCHIVED" ? "已归档" : "可继续" }}</span></td><td><span class="history-date">{{ new Date(item.updatedAt).toLocaleString() }}</span></td><td><div class="history-actions"><button v-if='item.status !== "ARCHIVED"' type="button" class="quiet-button" @click="archiveHistory(item)">归档</button><button v-if='item.status !== "ARCHIVED"' type="button" class="quiet-button" @click="renameHistory(item)">重命名</button><button type="button" class="danger-button" @click="deleteHistory(item)">移除</button></div></td></tr><tr v-if="conversationId === item.id" class="history-detail-row"><td colspan="4"><div class="history-detail"><div class="history-detail-heading"><strong>{{ historyRuns.length ? "本会话的运行记录" : "本会话暂无运行记录" }}</strong><small v-if="selectedConversationArchived">归档会话仅支持查看</small></div><div v-if="historyRuns.length" class="run-history-table"><div class="run-history-head"><span>运行时间</span><span>状态</span><span>运行标识</span><span></span></div><button v-for="run in visibleHistoryRuns" :key="run.runId" type="button" class="run-history-row" @click="loadHistoricalRun(run)"><span>{{ run.createdAt ? new Date(run.createdAt).toLocaleString() : "时间未知" }}</span><span>{{ run.status === "COMPLETED" || run.status === "SUCCEEDED" ? "已完成" : run.status === "CANCELLED" ? "已取消" : run.status === "FAILED" ? "失败" : "处理中" }}</span><code>{{ run.runId }}</code><b>打开回答 →</b></button></div><div class="pagination-bar" data-pagination="conversation-runs"><span>运行记录：第 {{ historyRunsPage }} / {{ historyRunsPageCount }} 页 · 共 {{ historyRuns.length }} 条</span><div><button type="button" class="quiet-button" :disabled="historyRunsPage <= 1" aria-label="上一页" @click="setHistoryRunsPage(historyRunsPage - 1)">上一页</button><button type="button" class="quiet-button" :disabled="historyRunsPage >= historyRunsPageCount" aria-label="下一页" @click="setHistoryRunsPage(historyRunsPage + 1)">下一页</button></div></div></div></td></tr></template></tbody></table></div><div class="pagination-bar" data-pagination="conversation-history"><span>会话：第 {{ historyPage }} / {{ historyPageCount }} 页 · 共 {{ filteredHistory.length }} 条</span><div><button type="button" class="quiet-button" :disabled="historyPage <= 1" aria-label="上一页" @click="setHistoryPage(historyPage - 1)">上一页</button><button type="button" class="quiet-button" :disabled="historyPage >= historyPageCount" aria-label="下一页" @click="setHistoryPage(historyPage + 1)">下一页</button></div></div></aside>
  <section class="view-section answer-view" aria-labelledby="answer-heading">
    <div class="section-heading"><div><p class="eyebrow">问答</p><h2 id="answer-heading">带引用问答</h2><p>回答与引用只来自当前空间的结构化证据，不会把模型生成的文件名或网址当作引用。</p></div><div class="read-only-note" :class="{ warning: status === 'degraded' || status === 'timeout' }">{{ statusLabel }}</div></div>
    <form class="card answer-form" @submit.prevent="startAnswer">
      <div class="field wide"><label for="answer-question">问题</label><textarea id="answer-question" v-model="question" rows="4" maxlength="32000" placeholder="输入问题；不会写入 URL 或浏览器存储"></textarea></div>
      <p v-if="readinessError" class="alert error">{{ readinessError }}</p>
      <p v-else-if="nextGap" class="alert error">下一步：{{ ANSWER_READINESS_LABELS[nextGap.code] }} <button type="button" class="quiet-button" @click="emit('follow-gap', nextGap.path)">去处理</button></p>
      <label class="history-toggle"><input v-model="expertOpen" type="checkbox" />专家设置</label>
      <div v-if="expertOpen" class="answer-config">
        <p class="field-hint">覆盖当前空间的默认绑定。普通提问不需要填写这些字段，也不会提交 hash。</p>
        <div class="form-grid">
          <div class="field"><label for="answer-route">模型路线</label><input id="answer-route" v-model="routeVersionId" autocomplete="off" /></div>
          <div class="field"><label for="answer-profile">模型档案</label><input id="answer-profile" v-model="profileVersionId" autocomplete="off" /></div>
          <div class="field"><label for="answer-provider">模型连接</label><input id="answer-provider" v-model="providerConnectionId" autocomplete="off" /></div>
          <div class="field"><label for="answer-prompt">回答规则</label><input id="answer-prompt" v-model="promptVersionId" autocomplete="off" /></div>
          <div class="field"><label for="answer-model">模型名称</label><input id="answer-model" v-model="model" autocomplete="off" /></div>
          <div class="field timeout-field"><label for="answer-timeout">最长等待（秒）</label><input id="answer-timeout" v-model.number="timeoutSeconds" type="number" min="1" max="120" step="1" /></div>
        </div>
      </div>
      <div class="form-actions"><button type="submit" :disabled="!canSubmit">{{ selectedConversationArchived ? "归档会话仅可查看" : isActive ? "回答进行中…" : spaceReady ? "开始回答" : "空间未就绪" }}</button><button v-if="isActive" type="button" class="danger-button" :disabled="status === 'cancelling'" @click="cancelAnswer">{{ status === "cancelling" ? "取消确认中…" : "取消回答" }}</button><span class="muted">当前空间：{{ selectedSpaceId || "未选择" }}</span></div>
    </form>
    <p v-if="formError" class="alert error" role="alert">{{ formError }}</p><p v-if="cancelError" class="alert error" role="alert">{{ cancelError }}</p><p v-if="notice" class="alert" :class="status === 'failed' || status === 'timeout' ? 'error' : 'success'" role="status">{{ notice }}</p>

    <div v-if="runContext" class="answer-context card" aria-live="polite"><span class="card-label">本次运行上下文</span><code>{{ contextLabel }}</code><span class="muted">最后序号 {{ lastSequence }} · 最后事件 {{ lastEventId ?? "—" }}</span></div>
    <article v-if="answerText || status === 'completed'" class="card answer-result" aria-live="polite"><div class="card-title"><h3>回答</h3><span class="state-pill" :class="status">{{ statusLabel }}</span></div><p class="answer-text">{{ answerText || "服务端未返回可安全展示的回答正文。" }}</p></article>
    <article v-if="status === 'abstained' || abstention" class="abstention answer-state"><strong>{{ abstentionTitle(abstention?.reasonCode ?? "") }}</strong><span>{{ safeAbstentionLabel(abstention?.reasonCode ?? "") }}</span><small>{{ safeAbstentionDetail(abstention?.reasonCode ?? "") }}</small></article>
    <article v-if="status === 'timeout'" class="answer-state warning-state"><strong>请求超时</strong><span>回答连接超过客户端等待窗口，未自动切换到云端或其他空间。</span></article>
    <article v-if="status === 'degraded' || status === 'reconnecting'" class="answer-state warning-state"><strong>服务降级</strong><span>{{ notice || "事件连接正在恢复；已有事件将按 sequence 与 event_id 去重。" }}</span></article>
    <article v-if="status === 'cancelled'" class="answer-state"><strong>回答已取消</strong><span>服务端已确认取消；取消后的 answer delta 会被丢弃。</span></article>

    <div v-if="citations.length" class="citation-section"><div class="card-title"><h3>可核验引用</h3><span class="muted">{{ citations.length }} 条 · 仅服务端 引用投影</span></div><div class="citation-grid"><article v-for="citation in citations" :key="citation.evidenceId" class="card citation-card"><div class="citation-title"><strong>{{ citation.evidenceId }}</strong><span><button type="button" class="secondary-button citation-open" :disabled="previewingEvidenceId === citation.evidenceId" @click="openCitation(citation)">{{ previewingEvidenceId === citation.evidenceId ? "鉴权中…" : "请求引用预览" }}</button><button type="button" class="quiet-button" :disabled="previewingEvidenceId === citation.evidenceId" @click="openCitationInStudio(citation)">在内容证据中打开</button></span></div><dl class="citation-details"><dt>修订版本</dt><dd><code>{{ citation.documentRevisionId }}</code></dd><dt>父分块 / 子分块</dt><dd><code>{{ citation.parentChunkId }}</code><code>{{ citation.childChunkId }}</code></dd><dt>内容引用</dt><dd><code>{{ citation.contentRef }}</code></dd><dt>正文摘要</dt><dd><code>{{ citation.textHash }}</code></dd><dt>位置锚点</dt><dd>{{ anchorLabel(citation.anchor) }}</dd></dl><div class="feedback-actions"><span>这条证据是否有帮助？</span><button type="button" class="secondary-button" :disabled="feedbackBusy === citation.evidenceId" @click="submitFeedback(citation, 'HELPFUL')">有帮助</button><button type="button" class="secondary-button" :disabled="feedbackBusy === citation.evidenceId" @click="submitFeedback(citation, 'NOT_HELPFUL')">需改进</button></div></article></div></div>
    <p v-if="previewNotice" class="alert" :class="previewResult ? 'success' : 'error'" role="status">{{ previewNotice }}</p><article v-if="previewResult" class="card citation-preview" aria-live="polite"><div class="card-title"><h3>结构化引用预览</h3><span class="state-pill">允许展示： {{ previewResult.citationAllowed ? "true" : "false" }}</span></div><dl class="citation-details"><dt>证据 / 运行</dt><dd><code>{{ previewResult.evidenceId }} · {{ previewResult.runId }}</code></dd><dt>修订版本</dt><dd><code>{{ previewResult.documentRevisionId }}</code></dd><dt>内容引用</dt><dd><code>{{ previewResult.contentRef }}</code></dd><dt>正文摘要</dt><dd><code>{{ previewResult.textHash }}</code></dd><dt>位置锚点</dt><dd>{{ anchorLabel(previewResult.anchor) }}</dd></dl></article><p v-if="feedbackNotice" class="alert success" role="status">{{ feedbackNotice }}</p>

    <div v-if="tools.length || usage || errorState || eventLog.length" class="answer-observability two-column"><article class="card"><div class="card-title"><h3>事件状态</h3><span class="muted">按序号展示，重复事件自动去重</span></div><div class="event-list"><div v-for="event in eventLog" :key="event.eventId" class="event-row"><span class="state-pill">{{ eventLabel(event.eventType) }}</span><span>#{{ event.sequence }}</span><code>{{ event.eventId }}</code></div></div></article><article class="card"><div class="card-title"><h3>工具与用量</h3><span class="muted">服务端结构化记录</span></div><div v-for="tool in tools" :key="tool.payload.toolCallId" class="tool-row"><span>{{ tool.payload.toolName }}</span><strong>{{ tool.payload.status }}</strong></div><dl v-if="usage" class="details usage-details"><dt>令牌数</dt><dd>{{ usage.payload.inputTokens }} 输入 · {{ usage.payload.outputTokens }} 输出 · {{ usage.payload.totalTokens }} 合计</dd><dt>工具调用</dt><dd>{{ usage.payload.toolCallCount }}</dd><dt>服务端已上报</dt><dd>{{ usage.payload.providerReported ? "是" : "否" }}</dd></dl><p v-if="errorState" class="permission-hint">{{ safeErrorLabel(errorState.code) }} · {{ errorState.retryable ? "可以重试" : "请检查配置或权限" }}</p><p v-if="!tools.length && !usage && !errorState" class="muted">尚无工具或用量事件。</p></article></div>

    <div v-if="status === 'empty'" class="empty-state card"><strong>尚未开始回答</strong><span>在右侧输入问题后开始；历史会话保留在左侧列表中。</span></div>
  </section>
  </div>
</template>

<style scoped>
.answer-workspace { display: grid; grid-template-columns: minmax(280px, 34%) minmax(0, 1fr); gap: 16px; align-items: start; }
.answer-history { margin: 0; position: sticky; top: 16px; max-height: calc(100vh - 48px); overflow: auto; }
.answer-view { margin: 0; }
@media (max-width: 980px) { .answer-workspace { grid-template-columns: 1fr; } .answer-history { position: static; max-height: none; } }
.answer-history { margin-bottom: 0; }.history-search { display: grid; gap: 6px; margin-top: 14px; }.history-search label { color: #314b77; font-size: .8rem; font-weight: 700; }.history-toggle { display: flex; align-items: center; gap: 6px; color: #687893; font-size: .76rem; }.history-list { display: grid; gap: 8px; margin-top: 12px; }.history-item { display: grid; grid-template-columns: 1fr auto; gap: 6px; padding: 10px; border: 1px solid #e1e9f4; border-radius: 9px; background: #f8fbff; }.history-item.selected { border-color: #8eadd3; background: #f1f7ff; }.history-select { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; border: 0; background: transparent; color: #284c87; text-align: left; cursor: pointer; }.history-select small, .run-history-row small { color: #71809a; font-size: .72rem; }.history-actions { display: flex; align-items: start; }.history-runs { grid-column: 1 / -1; display: grid; gap: 5px; padding-top: 8px; border-top: 1px solid #e2eaf4; }.run-history-row { display: flex; justify-content: space-between; gap: 12px; padding: 7px 9px; border: 0; border-radius: 6px; background: #fff; color: #526b92; cursor: pointer; text-align: left; }.run-history-row:hover { background: #e9f2ff; }
.history-intro { margin: 4px 0 0; font-size: .78rem; }.history-table-wrap { margin-top: 14px; overflow-x: auto; border: 1px solid #e1e9f4; border-radius: 12px; }.history-table { width: 100%; min-width: 680px; border-collapse: collapse; text-align: left; }.history-table th { padding: 10px 12px; background: #f5f8fd; color: #71809a; font-size: .7rem; font-weight: 800; }.history-table td { padding: 11px 12px; border-top: 1px solid #edf1f6; color: #405979; font-size: .78rem; vertical-align: middle; }.history-table tbody > tr.selected > td { background: #f1f7ff; }.history-table .history-select { width: 100%; padding: 0; }.history-table .history-select strong { color: #284c87; }.history-date { white-space: nowrap; color: #687893; }.history-status { display: inline-flex; padding: 4px 7px; border-radius: 999px; background: #e5f6ee; color: #19714c; font-size: .68rem; font-weight: 800; }.history-status.archived { background: #f0f2f5; color: #778399; }.history-detail-row > td { padding: 0; background: #fbfdff; }.history-detail { padding: 12px 15px 14px; }.history-detail-heading { display: flex; justify-content: space-between; gap: 10px; margin-bottom: 9px; color: #355174; }.history-detail-heading small { color: #a05a14; font-size: .7rem; }.run-history-table { overflow: hidden; border: 1px solid #e3eaf4; border-radius: 9px; }.run-history-head, .run-history-row { display: grid; grid-template-columns: 1.15fr .7fr 1.4fr auto; align-items: center; gap: 12px; }.run-history-head { padding: 8px 10px; background: #f1f6fd; color: #8190a6; font-size: .68rem; font-weight: 800; }.run-history-row { width: 100%; padding: 9px 10px; border-top: 1px solid #edf1f6; border-radius: 0; color: #526b92; }.run-history-row:first-of-type { border-top: 0; }.run-history-row code { overflow-wrap: anywhere; color: #315b9a; font-size: .68rem; }.run-history-row b { color: #2f6096; font-size: .7rem; white-space: nowrap; }
.feedback-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 16px; color: #687893; font-size: .78rem; }.feedback-actions button { padding: 7px 10px; font-size: .74rem; }.citation-preview { margin-top: 15px; }.citation-preview .citation-details { margin-bottom: 0; }
.answer-form { display: block; }
.pagination-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 11px; color: #71809a; font-size: .72rem; }.pagination-bar > div { display: flex; gap: 7px; }.pagination-bar button { padding: 7px 10px; font-size: .7rem; }.history-detail .pagination-bar { padding-top: 9px; border-top: 1px solid #edf1f6; }
.answer-form > .wide { margin-bottom: 14px; }
.answer-config { margin-top: 14px; padding: 13px 15px; border: 1px solid #dce5f2; border-radius: 10px; background: #fafcff; }
.answer-config summary { color: #345582; cursor: pointer; font-weight: 700; }
.answer-config .field-hint { margin: 10px 0 14px; }
.answer-config .form-grid { margin-top: 12px; }
.runtime-summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-top: 12px; }
.runtime-summary > div { min-width: 0; padding: 9px 10px; border-radius: 8px; background: #f1f6fd; }
.runtime-summary span { display: block; margin-bottom: 4px; color: #71809a; font-size: .76rem; }
.runtime-summary code { display: block; overflow-wrap: anywhere; color: #274f91; font-size: .73rem; }
.timeout-field { max-width: 220px; margin-top: 12px; }
.answer-context { display: flex; flex-direction: column; gap: 7px; margin-top: 15px; }
.answer-context code { overflow-wrap: anywhere; color: #274f91; font-family: "Cascadia Code", Consolas, monospace; font-size: .8rem; }
.answer-result { margin-top: 15px; }
.answer-text { margin: 18px 0 0; white-space: pre-wrap; line-height: 1.8; color: #273b61; }
.answer-state { display: flex; flex-direction: column; gap: 6px; margin-top: 15px; padding: 15px 18px; border-radius: 12px; background: #e7f7ee; color: #19714c; }
.answer-state strong { color: inherit; }
.answer-state small { color: inherit; opacity: .82; }
.warning-state { background: #fff0dc; color: #a15c16; }
.citation-section { margin-top: 22px; }
.citation-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 15px; margin-top: 12px; }
.citation-card { min-width: 0; }
.citation-title { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.citation-title strong { overflow-wrap: anywhere; color: #284c87; font-family: "Cascadia Code", Consolas, monospace; font-size: .8rem; }
.citation-open { flex: 0 0 auto; padding: 8px 10px; font-size: .78rem; }
.citation-details { display: grid; grid-template-columns: minmax(92px, .35fr) 1fr; gap: 8px 12px; margin: 16px 0 0; }
.citation-details dt { color: #73829a; font-size: .8rem; }
.citation-details dd { min-width: 0; margin: 0; overflow-wrap: anywhere; color: #2e3e5f; font-size: .82rem; line-height: 1.5; }
.citation-details code { display: block; overflow-wrap: anywhere; color: #274f91; font-family: "Cascadia Code", Consolas, monospace; font-size: .75rem; }
.answer-observability { margin-top: 15px; }
.event-list { display: flex; flex-direction: column; gap: 7px; max-height: 240px; margin-top: 15px; overflow: auto; }
.event-row, .tool-row { display: flex; align-items: center; gap: 8px; min-width: 0; padding: 8px; border-radius: 8px; background: #f7faff; color: #637793; font-size: .76rem; }
.event-row code { min-width: 0; overflow: hidden; color: #52709e; font-family: "Cascadia Code", Consolas, monospace; text-overflow: ellipsis; }
.tool-row { justify-content: space-between; margin-top: 8px; }
.tool-row strong { color: #284c87; }
.usage-details { margin-top: 15px; }
@media (max-width: 850px) { .citation-grid, .runtime-summary { grid-template-columns: 1fr; } }
@media (max-width: 700px) { .answer-history .card-title { align-items: flex-start; flex-direction: column; }.history-table { min-width: 0; }.history-table thead { display: none; }.history-table, .history-table tbody, .history-table tr, .history-table td { display: block; width: 100%; }.history-table tbody > tr:not(.history-detail-row) { padding: 12px; }.history-table td { padding: 5px 0; border: 0; }.history-table td::before { display: block; margin-bottom: 3px; color: #9aa7b9; content: attr(data-label); font-size: .65rem; font-weight: 800; }.history-table .history-actions { justify-content: flex-start; }.history-detail-row > td { padding: 0; }.run-history-head { display: none; }.run-history-row { grid-template-columns: 1fr auto; }.run-history-row code { grid-column: 1 / -1; }.run-history-row b { grid-column: 2; grid-row: 1 / span 2; } }
</style>
