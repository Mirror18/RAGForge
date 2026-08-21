import { apiFetch } from "./api";

export type AnswerEventType =
  | "answer.delta"
  | "answer.citation"
  | "answer.abstention"
  | "answer.tool"
  | "answer.usage"
  | "answer.error"
  | "answer.done"
  | "run.snapshot";

export type AnswerDoneStatus = "COMPLETED" | "ABSTAINED" | "FAILED" | "CANCELLED";
export type AnswerErrorCode =
  | "PROVIDER_UNAVAILABLE"
  | "TIMEOUT"
  | "SPACE_EGRESS_DENIED"
  | "EVIDENCE_INVALID"
  | "TOOL_FAILURE"
  | "CANCELLED"
  | "INTERNAL_ERROR";

export interface CitationAnchor {
  headingPath: string[];
  tokenStart: number;
  tokenEnd: number;
  charStart: number;
  charEnd: number;
  pageNumber: number | null;
  sheet: string | null;
  slideNumber: number | null;
  lineStart: number | null;
  lineEnd: number | null;
  tableCell: string | null;
}

export interface AnswerCitation {
  evidenceId: string;
  spaceId: string;
  correlationId: string;
  runId: string;
  evidenceBundleId: string;
  indexVersionId: string;
  documentRevisionId: string;
  parentChunkId: string;
  childChunkId: string;
  contentRef: string;
  textHash: string;
  anchor: CitationAnchor;
  citationAllowed: true;
}

export interface AnswerDeltaPayload {
  answerId: string;
  delta: string;
}

export interface AnswerCitationPayload {
  answerId: string;
  claimId: string;
  citation: AnswerCitation;
}

export type AnswerAbstentionReason =
  | "NO_EVIDENCE"
  | "LOW_CONFIDENCE"
  | "EVIDENCE_CONFLICT"
  | "POLICY_BLOCKED"
  | "SPACE_ACCESS_DENIED"
  | "TOOL_UNAUTHORIZED"
  | "TOOL_FAILURE"
  | "PROVIDER_UNAVAILABLE"
  | "CANCELLED";

export interface AnswerAbstentionPayload {
  answerId: string;
  reasonCode: AnswerAbstentionReason;
  evidenceIds: string[];
}

export interface AnswerToolPayload {
  answerId: string;
  toolCallId: string;
  toolName: "knowledge.search" | "document.read" | "web.fetch";
  status: "REQUESTED" | "SUCCEEDED" | "FAILED" | "REJECTED" | "TIMEOUT" | "CANCELLED";
}

export interface AnswerUsagePayload {
  answerId: string;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  toolCallCount: number;
  estimatedCostMicros: number | null;
  providerReported: boolean;
}

export interface AnswerErrorPayload {
  answerId: string;
  code: AnswerErrorCode;
  retryable: boolean;
}

export interface AnswerDonePayload {
  answerId: string;
  status: AnswerDoneStatus;
}

export type AnswerEventPayload =
  | AnswerDeltaPayload
  | AnswerCitationPayload
  | AnswerAbstentionPayload
  | AnswerToolPayload
  | AnswerUsagePayload
  | AnswerErrorPayload
  | AnswerDonePayload
  | { status?: string };

export interface AnswerEvent {
  schemaVersion: string;
  eventId: string;
  eventType: AnswerEventType;
  sequence: number;
  idempotencyKey: string;
  spaceId: string;
  correlationId: string;
  runId: string;
  occurredAt: string;
  payload: AnswerEventPayload;
}

export interface ConversationProjection {
  conversationId?: string;
  id?: string;
  spaceId: string;
}

export interface RunProjection {
  runId: string;
  spaceId: string;
  conversationId: string;
  status: string;
  correlationId: string;
  modelRouteId?: string;
  routeVersionId?: string;
  promptVersionId: string;
  cancelRequested?: boolean;
}

export interface StartAnswerRequest {
  routeVersionId: string;
  profileVersionId: string;
  providerConnectionId: string;
  promptVersionId: string;
  model: string;
  message: string;
  timeoutSeconds: number;
  datasetHash: string;
  configHash: string;
  maxContextTokens: number;
}

export interface CitationPreviewResult {
  available: boolean;
}

type RawRecord = Record<string, unknown>;

function isRecord(value: unknown): value is RawRecord {
  return typeof value === "object" && value !== null;
}

function stringValue(record: RawRecord, ...keys: string[]): string | null {
  for (const key of keys) {
    const value = record[key];
    if (typeof value === "string" && value.length > 0) return value;
  }
  return null;
}

function numberValue(record: RawRecord, ...keys: string[]): number | null {
  for (const key of keys) {
    const value = record[key];
    if (typeof value === "number" && Number.isFinite(value)) return value;
  }
  return null;
}

function nullableNumber(record: RawRecord, ...keys: string[]): number | null {
  const value = numberValue(record, ...keys);
  return value === null ? null : Math.max(0, Math.trunc(value));
}

function uuidLike(value: string | null): value is string {
  return value !== null && value.length >= 8 && value.length <= 80;
}

function normalizeAnchor(raw: unknown): CitationAnchor | null {
  if (!isRecord(raw)) return null;
  const headingPathRaw = raw.heading_path ?? raw.headingPath;
  const headingPath = Array.isArray(headingPathRaw)
    ? headingPathRaw.filter((item): item is string => typeof item === "string" && item.length > 0).slice(0, 20)
    : [];
  const tokenStart = numberValue(raw, "token_start", "tokenStart");
  const tokenEnd = numberValue(raw, "token_end", "tokenEnd");
  const charStart = numberValue(raw, "char_start", "charStart");
  const charEnd = numberValue(raw, "char_end", "charEnd");
  if (tokenStart === null || tokenEnd === null || charStart === null || charEnd === null) return null;
  if (tokenStart < 0 || tokenEnd < tokenStart || charStart < 0 || charEnd < charStart) return null;
  return {
    headingPath,
    tokenStart: Math.trunc(tokenStart),
    tokenEnd: Math.trunc(tokenEnd),
    charStart: Math.trunc(charStart),
    charEnd: Math.trunc(charEnd),
    pageNumber: nullableNumber(raw, "page_number", "pageNumber"),
    sheet: stringValue(raw, "sheet"),
    slideNumber: nullableNumber(raw, "slide_number", "slideNumber"),
    lineStart: nullableNumber(raw, "line_start", "lineStart"),
    lineEnd: nullableNumber(raw, "line_end", "lineEnd"),
    tableCell: stringValue(raw, "table_cell", "tableCell"),
  };
}

function normalizeCitation(raw: unknown, envelope: Pick<AnswerEvent, "spaceId" | "correlationId" | "runId">): AnswerCitation | null {
  if (!isRecord(raw)) return null;
  const citation = isRecord(raw.citation) ? raw.citation : raw;
  const forbiddenFields = new Set(["filename", "url", "quote", "citationtext", "fulltext", "rawtext", "documentcontent"]);
  if ([...Object.keys(citation)].some((key) => forbiddenFields.has(key.replace(/[^A-Za-z0-9]/g, "").toLowerCase()))) return null;
  const evidenceId = stringValue(citation, "evidence_id", "evidenceId");
  const spaceId = stringValue(citation, "space_id", "spaceId");
  const correlationId = stringValue(citation, "correlation_id", "correlationId");
  const runId = stringValue(citation, "run_id", "runId");
  const evidenceBundleId = stringValue(citation, "evidence_bundle_id", "evidenceBundleId");
  const indexVersionId = stringValue(citation, "index_version_id", "indexVersionId");
  const documentRevisionId = stringValue(citation, "document_revision_id", "documentRevisionId");
  const parentChunkId = stringValue(citation, "parent_chunk_id", "parentChunkId");
  const childChunkId = stringValue(citation, "child_chunk_id", "childChunkId");
  const contentRef = stringValue(citation, "content_ref", "contentRef");
  const textHash = stringValue(citation, "text_hash", "textHash");
  const anchor = normalizeAnchor(citation.anchor);
  const citationAllowed = citation.citation_allowed ?? citation.citationAllowed;
  if (!uuidLike(evidenceId) || spaceId !== envelope.spaceId || correlationId !== envelope.correlationId || runId !== envelope.runId) return null;
  if (!uuidLike(evidenceBundleId) || !uuidLike(indexVersionId) || !uuidLike(documentRevisionId) || !uuidLike(parentChunkId) || !uuidLike(childChunkId) || !contentRef) return null;
  if (!/^[A-Za-z0-9._:/-]+$/.test(contentRef) || !textHash || !/^[0-9a-fA-F]{64}$/.test(textHash) || !anchor || citationAllowed !== true) return null;
  return { evidenceId, spaceId, correlationId, runId, evidenceBundleId, indexVersionId, documentRevisionId, parentChunkId, childChunkId, contentRef, textHash, anchor, citationAllowed: true };
}

function normalizePayload(eventType: AnswerEventType, raw: unknown, envelope: Pick<AnswerEvent, "spaceId" | "correlationId" | "runId">): AnswerEventPayload | null {
  const payload = isRecord(raw) ? raw : {};
  const answerId = stringValue(payload, "answer_id", "answerId") ?? "unknown-answer";
  if (eventType === "answer.delta") {
    const delta = stringValue(payload, "delta", "text");
    return delta ? { answerId, delta } : null;
  }
  if (eventType === "answer.citation") {
    const citation = normalizeCitation(payload, envelope);
    const claimId = stringValue(payload, "claim_id", "claimId") ?? "unknown-claim";
    return citation ? { answerId, claimId, citation } : null;
  }
  if (eventType === "answer.abstention") {
    const abstention = isRecord(payload.abstention) ? payload.abstention : payload;
    const reasonCode = stringValue(abstention, "reason_code", "reasonCode");
    const allowedReasons = ["NO_EVIDENCE", "LOW_CONFIDENCE", "EVIDENCE_CONFLICT", "POLICY_BLOCKED", "SPACE_ACCESS_DENIED", "TOOL_UNAUTHORIZED", "TOOL_FAILURE", "PROVIDER_UNAVAILABLE", "CANCELLED"] as const;
    if (!reasonCode || !allowedReasons.includes(reasonCode as (typeof allowedReasons)[number])) return null;
    const evidenceIdsRaw = abstention.evidence_ids ?? abstention.evidenceIds;
    const evidenceIds = Array.isArray(evidenceIdsRaw) ? evidenceIdsRaw.filter((item): item is string => typeof item === "string") : [];
    return { answerId, reasonCode: reasonCode as AnswerAbstentionReason, evidenceIds };
  }
  if (eventType === "answer.tool") {
    const toolName = stringValue(payload, "tool_name", "toolName");
    const status = stringValue(payload, "status");
    const allowedTools = ["knowledge.search", "document.read", "web.fetch"] as const;
    const allowedStatuses = ["REQUESTED", "SUCCEEDED", "FAILED", "REJECTED", "TIMEOUT", "CANCELLED"] as const;
    if (!toolName || !allowedTools.includes(toolName as (typeof allowedTools)[number]) || !status || !allowedStatuses.includes(status as (typeof allowedStatuses)[number])) return null;
    return { answerId, toolCallId: stringValue(payload, "tool_call_id", "toolCallId") ?? "unknown-tool-call", toolName: toolName as AnswerToolPayload["toolName"], status: status as AnswerToolPayload["status"] };
  }
  if (eventType === "answer.usage") {
    const inputTokens = numberValue(payload, "input_tokens", "inputTokens");
    const outputTokens = numberValue(payload, "output_tokens", "outputTokens");
    const totalTokens = numberValue(payload, "total_tokens", "totalTokens");
    const toolCallCount = numberValue(payload, "tool_call_count", "toolCallCount");
    if (inputTokens === null || outputTokens === null || totalTokens === null || toolCallCount === null) return null;
    const cost = numberValue(payload, "estimated_cost_micros", "estimatedCostMicros");
    return { answerId, inputTokens: Math.max(0, Math.trunc(inputTokens)), outputTokens: Math.max(0, Math.trunc(outputTokens)), totalTokens: Math.max(0, Math.trunc(totalTokens)), toolCallCount: Math.max(0, Math.trunc(toolCallCount)), estimatedCostMicros: cost === null ? null : Math.max(0, Math.trunc(cost)), providerReported: payload.provider_reported === true || payload.providerReported === true };
  }
  if (eventType === "answer.error") {
    const rawCode = stringValue(payload, "code", "error_code", "errorCode", "errorClass");
    const codeMap: Record<string, AnswerErrorCode> = { UNAVAILABLE: "PROVIDER_UNAVAILABLE", RATE_LIMIT: "PROVIDER_UNAVAILABLE", PROVIDER_UNAVAILABLE: "PROVIDER_UNAVAILABLE", TIMEOUT: "TIMEOUT", SPACE_EGRESS_DENIED: "SPACE_EGRESS_DENIED", EVIDENCE_INVALID: "EVIDENCE_INVALID", TOOL_FAILURE: "TOOL_FAILURE", CANCELLED: "CANCELLED", INTERNAL_ERROR: "INTERNAL_ERROR", INVALID_RESPONSE: "INTERNAL_ERROR" };
    const code = rawCode ? codeMap[rawCode] : undefined;
    return code ? { answerId, code, retryable: payload.retryable === true } : null;
  }
  if (eventType === "answer.done") {
    const status = stringValue(payload, "status");
    const normalizedStatus = status === "SUCCEEDED" ? "COMPLETED" : status;
    return normalizedStatus === "COMPLETED" || normalizedStatus === "ABSTAINED" || normalizedStatus === "FAILED" || normalizedStatus === "CANCELLED" ? { answerId, status: normalizedStatus } : null;
  }
  return { status: stringValue(payload, "status") ?? undefined };
}

function normalizeEvent(raw: unknown, sseId: string | undefined, sseType: string | undefined): AnswerEvent | null {
  if (!isRecord(raw)) return null;
  const eventId = stringValue(raw, "event_id", "eventId") ?? sseId ?? null;
  const eventTypeRaw = stringValue(raw, "event_type", "eventType", "type") ?? sseType ?? null;
  const sequence = numberValue(raw, "sequence");
  const spaceId = stringValue(raw, "space_id", "spaceId");
  const correlationId = stringValue(raw, "correlation_id", "correlationId");
  const runId = stringValue(raw, "run_id", "runId");
  if (!eventId || !eventTypeRaw || sequence === null || sequence < 1 || !spaceId || !correlationId || !runId) return null;
  const typeMap: Record<string, AnswerEventType> = { "answer.delta": "answer.delta", "answer.citation": "answer.citation", "citation.added": "answer.citation", "answer.abstention": "answer.abstention", "answer.tool": "answer.tool", "answer.usage": "answer.usage", "usage.updated": "answer.usage", "answer.error": "answer.error", "run.error": "answer.error", "answer.done": "answer.done", "run.completed": "answer.done", "run.snapshot": "run.snapshot", "run.status": "run.snapshot" };
  let eventType = typeMap[eventTypeRaw];
  if (!eventType) return null;
  if (eventTypeRaw === "run.status") {
    const status = isRecord(raw.payload) ? stringValue(raw.payload, "status") : null;
    if (status === "CANCELLED" || status === "FAILED" || status === "SUCCEEDED" || status === "COMPLETED" || status === "ABSTAINED") eventType = "answer.done";
  }
  const envelope = { spaceId, correlationId, runId };
  const payload = normalizePayload(eventType, raw.payload, envelope);
  if (!payload) return null;
  return { schemaVersion: stringValue(raw, "schema_version", "schemaVersion") ?? "v1", eventId, eventType, sequence: Math.trunc(sequence), idempotencyKey: stringValue(raw, "idempotency_key", "idempotencyKey") ?? `legacy-${eventId}`, spaceId, correlationId, runId, occurredAt: stringValue(raw, "occurred_at", "occurredAt") ?? "", payload };
}

export function parseAnswerEvent(raw: unknown, sseId?: string, sseType?: string): AnswerEvent | null {
  return normalizeEvent(raw, sseId, sseType);
}

export async function createAnswerConversation(spaceId: string, idempotencyKey: string): Promise<ConversationProjection> {
  return apiFetch<ConversationProjection>(`/api/v1/spaces/${encodeURIComponent(spaceId)}/conversations`, { method: "POST", body: { title: "RAGForge Answer" }, idempotencyKey });
}

export async function createAnswerRun(spaceId: string, conversationId: string, request: StartAnswerRequest, idempotencyKey: string): Promise<RunProjection> {
  return apiFetch<RunProjection>(`/api/v1/spaces/${encodeURIComponent(spaceId)}/conversations/${encodeURIComponent(conversationId)}/runs`, { method: "POST", body: { ...request, allowCloudEgress: false }, idempotencyKey });
}

export async function createAnswer(spaceId: string, request: StartAnswerRequest & { runId: string }, idempotencyKey: string): Promise<unknown> {
  return apiFetch<unknown>(`/api/v1/spaces/${encodeURIComponent(spaceId)}/answers`, {
    method: "POST",
    body: {
      runId: request.runId,
      query: request.message,
      promptVersionId: request.promptVersionId,
      modelRouteVersionId: request.routeVersionId,
      modelProfileVersionId: request.profileVersionId,
      model: request.model,
      maxContextTokens: request.maxContextTokens,
      timeoutSeconds: request.timeoutSeconds,
      datasetHash: request.datasetHash,
      configHash: request.configHash,
      allowCloudEgress: false,
    },
    idempotencyKey,
  });
}

export async function cancelAnswerRun(spaceId: string, runId: string, idempotencyKey: string): Promise<RunProjection> {
  return apiFetch<RunProjection>(`/api/v1/spaces/${encodeURIComponent(spaceId)}/answers/${encodeURIComponent(runId)}/cancel`, { method: "POST", body: { reason: "user_cancelled" }, idempotencyKey });
}

export async function previewCitation(spaceId: string, runId: string, evidenceId: string): Promise<CitationPreviewResult> {
  // This is the server re-authorization seam. It intentionally accepts only server-issued IDs;
  // contentRef is never treated as a browser URL and any preview body is not rendered here.
  try {
    await apiFetch<unknown>(`/api/v1/spaces/${encodeURIComponent(spaceId)}/runs/${encodeURIComponent(runId)}/citations/${encodeURIComponent(evidenceId)}/preview`);
    return { available: true };
  } catch {
    return { available: false };
  }
}

export interface AnswerStreamOptions {
  spaceId: string;
  runId: string;
  correlationId: string;
  lastEventId: string | null;
  signal: AbortSignal;
  onEvent: (event: AnswerEvent) => void;
}

export class AnswerStreamError extends Error {
  constructor(readonly status: number | null) {
    super("answer stream unavailable");
    this.name = "AnswerStreamError";
  }
}

function parseSseBlock(block: string): { id?: string; type?: string; data?: string } | null {
  let id: string | undefined;
  let type: string | undefined;
  const data: string[] = [];
  for (const line of block.split("\n")) {
    if (line.startsWith(":")) continue;
    const separator = line.indexOf(":");
    const field = separator >= 0 ? line.slice(0, separator) : line;
    const value = separator >= 0 ? line.slice(separator + 1).replace(/^ /, "") : "";
    if (field === "id") id = value;
    if (field === "event") type = value;
    if (field === "data") data.push(value);
  }
  return data.length > 0 ? { id, type, data: data.join("\n") } : null;
}

export async function consumeAnswerStream(options: AnswerStreamOptions): Promise<void> {
  const headers = new Headers({ Accept: "text/event-stream", "Cache-Control": "no-cache", "X-Correlation-Id": options.correlationId });
  if (options.lastEventId) headers.set("Last-Event-ID", options.lastEventId);
  const response = await fetch(`/api/v1/spaces/${encodeURIComponent(options.spaceId)}/answers/${encodeURIComponent(options.runId)}/events`, { method: "GET", headers, credentials: "include", signal: options.signal });
  if (!response.ok) throw new AnswerStreamError(response.status);
  if (!response.body) throw new AnswerStreamError(null);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  const dispatch = (block: string): void => {
    const frame = parseSseBlock(block.replace(/\r/g, ""));
    if (!frame?.data) return;
    try {
      const event = parseAnswerEvent(JSON.parse(frame.data), frame.id, frame.type);
      if (event) options.onEvent(event);
    } catch {
      // Malformed provider/event data is ignored; no raw stream content reaches the UI.
    }
  };
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let boundary = buffer.indexOf("\n\n");
      while (boundary >= 0) {
        dispatch(buffer.slice(0, boundary));
        buffer = buffer.slice(boundary + 2);
        boundary = buffer.indexOf("\n\n");
      }
    }
    buffer += decoder.decode();
    if (buffer.trim()) dispatch(buffer);
  } finally {
    reader.releaseLock();
  }
}
