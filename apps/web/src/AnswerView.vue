<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { ApiError, type AnswerDefaults } from "./api";
import { AnswerStreamError, cancelAnswerRun, consumeAnswerStream, createAnswer, createAnswerConversation, createAnswerRun, previewCitation, type AnswerAbstentionReason, type AnswerCitation, type AnswerDonePayload, type AnswerErrorCode, type AnswerEvent, type AnswerToolPayload, type AnswerUsagePayload, type CitationPreviewResult } from "./answer";
import { type RunProjection } from "./answer";

const props = defineProps<{ selectedSpaceId: string; defaults?: AnswerDefaults | null }>();

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
const conversationId = ref("");
const routeVersionId = ref("");
const profileVersionId = ref("");
const providerConnectionId = ref("");
const promptVersionId = ref("");
const model = ref("");
const datasetHash = ref("");
const configHash = ref("");
const timeoutSeconds = ref(30);
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
const previewingEvidenceId = ref<string | null>(null);
const feedbackNotice = ref("");
const cancelRequested = ref(false);
const streamAttempts = ref(0);

watch(() => props.defaults, (defaults) => {
  if (!defaults) return;
  routeVersionId.value = defaults.routeVersionId;
  profileVersionId.value = defaults.profileVersionId;
  providerConnectionId.value = defaults.providerConnectionId;
  promptVersionId.value = defaults.promptVersionId;
  model.value = defaults.model;
  datasetHash.value = defaults.datasetHash;
  configHash.value = defaults.configHash;
}, { immediate: true });

let abortController: AbortController | null = null;
let cancelPromise: Promise<void> | null = null;
let runIdempotencyKey = "";
let cancelIdempotencyKey = "";
const seenEventIds = new Set<string>();

const isActive = computed(() => status.value === "loading" || status.value === "reconnecting" || status.value === "degraded" || status.value === "cancelling");
const statusLabel = computed(() => ({ empty: "等待提问", loading: "回答生成中", reconnecting: "连接恢复中", completed: "已完成", abstained: "安全拒答", failed: "回答失败", cancelled: "已取消", degraded: "服务降级", timeout: "请求超时", cancelling: "正在取消" })[status.value]);
const contextLabel = computed(() => runContext.value ? `space ${runContext.value.spaceId} · run ${runContext.value.runId} · correlation ${runContext.value.correlationId}` : "尚未创建 run");

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
  feedbackNotice.value = "";
  cancelRequested.value = false;
  streamAttempts.value = 0;
}

function validateStart(): string | null {
  if (!props.selectedSpaceId) return "请先在页面顶部选择当前空间。";
  if (!question.value.trim()) return "请输入问题。";
  if (!routeVersionId.value.trim() || !profileVersionId.value.trim() || !providerConnectionId.value.trim() || !promptVersionId.value.trim() || !model.value.trim()) return "需要填写 route、profile、provider connection、prompt 和 model。";
  if (!/^[0-9a-f]{64}$/i.test(datasetHash.value) || !/^[0-9a-f]{64}$/i.test(configHash.value)) return "需要填写 64 位 dataset/config hash，避免使用未版本化的运行配置。";
  if (!Number.isInteger(timeoutSeconds.value) || timeoutSeconds.value < 1 || timeoutSeconds.value > 120) return "timeoutSeconds 必须是 1–120 的整数。";
  return null;
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
  feedbackNotice.value = "";
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
    if (props.selectedSpaceId !== spaceIdAtStart) throw new Error("space changed during answer start");
    const request = { routeVersionId: routeVersionId.value.trim(), profileVersionId: profileVersionId.value.trim(), providerConnectionId: providerConnectionId.value.trim(), promptVersionId: promptVersionId.value.trim(), model: model.value.trim(), message: question.value.trim(), timeoutSeconds: timeoutSeconds.value, datasetHash: datasetHash.value.trim(), configHash: configHash.value.trim(), maxContextTokens: 4000 };
    const run = await createAnswerRun(spaceIdAtStart, activeConversationId, request, runIdempotencyKey);
    const runId = runIdentifier(run);
    if (!runId || run.spaceId !== spaceIdAtStart || props.selectedSpaceId !== spaceIdAtStart || !run.correlationId) throw new Error("run context unavailable");
    runContext.value = { spaceId: spaceIdAtStart, runId, correlationId: run.correlationId };
    cancelIdempotencyKey = createKey(`answer-cancel-${runId}`);
    await createAnswer(spaceIdAtStart, { ...request, runId, correlationId: run.correlationId }, createKey(`answer-create-${runId}`));
    await streamRun(runContext.value, timeoutSeconds.value);
  } catch (error) {
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

async function openCitation(citation: AnswerCitation): Promise<void> {
  if (!runContext.value || previewingEvidenceId.value) return;
  previewingEvidenceId.value = citation.evidenceId;
  previewNotice.value = "";
  try {
    const result: CitationPreviewResult = await previewCitation(runContext.value.spaceId, runContext.value.runId, citation.evidenceId);
    previewNotice.value = result.available ? "服务端已重新鉴权该引用；正文预览不在此客户端渲染。" : "引用暂不可打开；服务端预览接口不存在或当前权限不足。";
  } finally {
    previewingEvidenceId.value = null;
  }
}

function requestFeedback(category: "INCORRECT" | "MISSING_EVIDENCE" | "UNSAFE"): void {
  feedbackNotice.value = `反馈入口占位：${category}。仅记录安全分类，不保存问题、回答或原文。`;
}

watch(() => props.selectedSpaceId, (next, previous) => {
  if (next !== previous && (runContext.value || isActive.value)) resetAnswer();
});
onBeforeUnmount(() => abortController?.abort());
</script>

<template>
  <section class="view-section answer-view" aria-labelledby="answer-heading">
    <div class="section-heading"><div><p class="eyebrow">03 · Verifiable answer</p><h2 id="answer-heading">带引用问答</h2><p>回答增量、结构化 Citation 和运行状态来自当前空间的 SSE；模型提供的文件名、URL 和正文不会被当作引用。</p></div><div class="read-only-note" :class="{ warning: status === 'degraded' || status === 'timeout' }">{{ statusLabel }}</div></div>
    <form class="card answer-form" @submit.prevent="startAnswer">
      <div class="field wide"><label for="answer-question">问题</label><textarea id="answer-question" v-model="question" rows="4" maxlength="32000" placeholder="输入问题；不会写入 URL 或浏览器存储"></textarea></div>
      <details class="answer-config"><summary>运行版本配置</summary><p class="field-hint">当前服务器运行契约需要显式绑定不可变 route/profile/provider/prompt/model 版本与 dataset/config hash；请求路径始终使用页面顶部的当前空间。云端出境在本入口固定关闭。</p><div class="form-grid"><div class="field"><label for="answer-conversation">已有 conversationId（可选）</label><input id="answer-conversation" v-model="conversationId" autocomplete="off" placeholder="留空则创建新会话" /></div><div class="field"><label for="answer-route">routeVersionId</label><input id="answer-route" v-model="routeVersionId" autocomplete="off" required placeholder="UUIDv7" /></div><div class="field"><label for="answer-profile">profileVersionId</label><input id="answer-profile" v-model="profileVersionId" autocomplete="off" required placeholder="UUIDv7" /></div><div class="field"><label for="answer-provider">providerConnectionId</label><input id="answer-provider" v-model="providerConnectionId" autocomplete="off" required placeholder="UUIDv7" /></div><div class="field"><label for="answer-prompt">promptVersionId</label><input id="answer-prompt" v-model="promptVersionId" autocomplete="off" required placeholder="UUIDv7" /></div><div class="field"><label for="answer-model">model</label><input id="answer-model" v-model="model" autocomplete="off" required placeholder="已发布模型名" /></div><div class="field"><label for="answer-dataset-hash">datasetHash</label><input id="answer-dataset-hash" v-model="datasetHash" autocomplete="off" required placeholder="64 位 SHA-256" /></div><div class="field"><label for="answer-config-hash">configHash</label><input id="answer-config-hash" v-model="configHash" autocomplete="off" required placeholder="64 位 SHA-256" /></div><div class="field"><label for="answer-timeout">timeoutSeconds</label><input id="answer-timeout" v-model.number="timeoutSeconds" type="number" min="1" max="120" step="1" /></div></div></details>
      <div class="form-actions"><button type="submit" :disabled="isActive || !selectedSpaceId">{{ isActive ? "回答进行中…" : "开始回答" }}</button><button v-if="isActive" type="button" class="danger-button" :disabled="status === 'cancelling'" @click="cancelAnswer">{{ status === "cancelling" ? "取消确认中…" : "取消回答" }}</button><span class="muted">当前空间：{{ selectedSpaceId || "未选择" }} · 仅本地出境策略</span></div>
    </form>
    <p v-if="formError" class="alert error" role="alert">{{ formError }}</p><p v-if="cancelError" class="alert error" role="alert">{{ cancelError }}</p><p v-if="notice" class="alert" :class="status === 'failed' || status === 'timeout' ? 'error' : 'success'" role="status">{{ notice }}</p>

    <div v-if="runContext" class="answer-context card" aria-live="polite"><span class="card-label">本次运行上下文</span><code>{{ contextLabel }}</code><span class="muted">last sequence {{ lastSequence }} · last event {{ lastEventId ?? "—" }}</span></div>
    <article v-if="answerText || status === 'completed'" class="card answer-result" aria-live="polite"><div class="card-title"><h3>回答</h3><span class="state-pill" :class="status">{{ statusLabel }}</span></div><p class="answer-text">{{ answerText || "服务端未返回可安全展示的回答正文。" }}</p></article>
    <article v-if="status === 'abstained' || abstention" class="abstention answer-state"><strong>安全拒答</strong><span>{{ safeAbstentionLabel(abstention?.reasonCode ?? "") }}</span><small>{{ safeAbstentionDetail(abstention?.reasonCode ?? "") }}</small></article>
    <article v-if="status === 'timeout'" class="answer-state warning-state"><strong>请求超时</strong><span>回答连接超过客户端等待窗口，未自动切换到云端或其他空间。</span></article>
    <article v-if="status === 'degraded' || status === 'reconnecting'" class="answer-state warning-state"><strong>服务降级</strong><span>{{ notice || "事件连接正在恢复；已有事件将按 sequence 与 event_id 去重。" }}</span></article>
    <article v-if="status === 'cancelled'" class="answer-state"><strong>回答已取消</strong><span>服务端已确认取消；取消后的 answer delta 会被丢弃。</span></article>

    <div v-if="citations.length" class="citation-section"><div class="card-title"><h3>可核验引用</h3><span class="muted">{{ citations.length }} 条 · 仅服务端 Citation 投影</span></div><div class="citation-grid"><article v-for="citation in citations" :key="citation.evidenceId" class="card citation-card"><div class="citation-title"><strong>{{ citation.evidenceId }}</strong><button type="button" class="secondary-button citation-open" :disabled="previewingEvidenceId === citation.evidenceId" @click="openCitation(citation)">{{ previewingEvidenceId === citation.evidenceId ? "鉴权中…" : "请求引用预览" }}</button></div><dl class="citation-details"><dt>revision</dt><dd><code>{{ citation.documentRevisionId }}</code></dd><dt>parent / child</dt><dd><code>{{ citation.parentChunkId }}</code><code>{{ citation.childChunkId }}</code></dd><dt>contentRef</dt><dd><code>{{ citation.contentRef }}</code></dd><dt>textHash</dt><dd><code>{{ citation.textHash }}</code></dd><dt>anchor</dt><dd>{{ anchorLabel(citation.anchor) }}</dd></dl></article></div></div>
    <p v-if="previewNotice" class="alert" :class="previewNotice.startsWith('引用暂不可') ? 'error' : 'success'" role="status">{{ previewNotice }}</p>

    <div v-if="tools.length || usage || errorState || eventLog.length" class="answer-observability two-column"><article class="card"><div class="card-title"><h3>事件状态</h3><span class="muted">sequence 单调、event_id 去重</span></div><div class="event-list"><div v-for="event in eventLog" :key="event.eventId" class="event-row"><span class="state-pill">{{ eventLabel(event.eventType) }}</span><span>#{{ event.sequence }}</span><code>{{ event.eventId }}</code></div></div></article><article class="card"><div class="card-title"><h3>工具与用量</h3><span class="muted">服务端结构化记录</span></div><div v-for="tool in tools" :key="tool.payload.toolCallId" class="tool-row"><span>{{ tool.payload.toolName }}</span><strong>{{ tool.payload.status }}</strong></div><dl v-if="usage" class="details usage-details"><dt>tokens</dt><dd>{{ usage.payload.inputTokens }} in · {{ usage.payload.outputTokens }} out · {{ usage.payload.totalTokens }} total</dd><dt>tools</dt><dd>{{ usage.payload.toolCallCount }}</dd><dt>provider reported</dt><dd>{{ usage.payload.providerReported ? "是" : "否" }}</dd></dl><p v-if="errorState" class="permission-hint">{{ safeErrorLabel(errorState.code) }} · {{ errorState.retryable ? "可重试" : "请检查配置或权限" }}</p><p v-if="!tools.length && !usage && !errorState" class="muted">尚无工具或用量事件。</p></article></div>

    <div class="feedback-row"><span class="muted">反馈入口（占位）</span><button type="button" class="secondary-button" @click="requestFeedback('INCORRECT')">回答不准确</button><button type="button" class="secondary-button" @click="requestFeedback('MISSING_EVIDENCE')">缺少证据</button><button type="button" class="secondary-button" @click="requestFeedback('UNSAFE')">安全问题</button></div><p v-if="feedbackNotice" class="field-hint">{{ feedbackNotice }}</p>
    <div v-if="status === 'empty'" class="empty-state card"><strong>尚未开始回答</strong><span>选择当前空间，填写问题与不可变运行版本后开始。SSE 重连会携带 Last-Event-ID。</span></div>
  </section>
</template>

<style scoped>
.answer-form { display: block; }
.answer-form > .wide { margin-bottom: 14px; }
.answer-config { margin-top: 14px; padding: 13px 15px; border: 1px solid #dce5f2; border-radius: 10px; background: #fafcff; }
.answer-config summary { color: #345582; cursor: pointer; font-weight: 700; }
.answer-config .field-hint { margin: 10px 0 14px; }
.answer-config .form-grid { margin-top: 12px; }
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
.feedback-row { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 18px; }
.feedback-row .secondary-button { padding: 8px 10px; font-size: .78rem; }
.feedback-row + .field-hint { margin: 8px 0 0; }
@media (max-width: 850px) { .citation-grid { grid-template-columns: 1fr; } }
</style>
