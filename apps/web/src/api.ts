export type PlatformRole = "PLATFORM_ADMIN" | "USER";
export type SpaceRole = "SPACE_ADMIN" | "EDITOR" | "VIEWER";

export interface ProblemDetails {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance: string;
  code: string;
  correlationId: string;
  fieldErrors?: Array<{ field: string; code: string; message: string }>;
  retryable?: boolean;
  retryAfterSeconds?: number;
}

export class ApiError extends Error {
  readonly problem: ProblemDetails | null;
  readonly status: number;
  readonly correlationId: string | null;

  constructor(message: string, status: number, problem: ProblemDetails | null, correlationId: string | null) {
    super(message);
    this.name = "ApiError";
    this.problem = problem;
    this.status = status;
    this.correlationId = correlationId;
  }
}

export interface CurrentSession {
  session: {
    sessionId: string;
    userId: string;
    expiresAt: string;
    csrfToken: string;
  };
  user: {
    userId: string;
    email: string;
    displayName: string;
    platformRole: PlatformRole;
  };
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface Space {
  spaceId: string;
  name: string;
  description?: string;
  status: "ACTIVE" | "ARCHIVED";
  role: SpaceRole;
  createdAt: string;
  version?: number;
}

export interface SpacePage {
  items: Space[];
  nextCursor: string | null;
}

export interface ManagedUser {
  userId: string;
  email: string;
  displayName: string;
  platformRole: PlatformRole;
  status: "ACTIVE" | "DISABLED";
  createdAt: string;
  updatedAt: string;
}

export interface SpaceMember {
  spaceId: string;
  userId: string;
  email: string;
  displayName: string;
  role: SpaceRole;
  version: number;
}

export interface ProviderConnection {
  providerConnectionId: string;
  spaceId: string;
  version: number;
  providerType: "OLLAMA" | "OPENAI_COMPATIBLE" | "MIMO" | "AI_RUNTIME";
  egressClass: "LOCAL" | "CLOUD";
  endpoint: string;
  status: "DRAFT" | "ACTIVE" | "DISABLED" | "UNHEALTHY";
  createdAt: string;
  updatedAt: string;
}

export interface ModelProfile {
  modelProfileId: string;
  spaceId: string;
  version: number;
  providerConnectionId: string;
  purpose: "CHAT" | "EMBEDDING" | "RERANK";
  modelName: string;
  capabilities: string[];
  contextWindow: number;
  maxOutputTokens: number;
  embeddingDimension: number | null;
  usageReporting: "PROVIDER_REPORTED" | "LOCAL_ESTIMATE";
  status: "DRAFT" | "PUBLISHED" | "DISABLED";
  createdAt: string;
  updatedAt: string;
}

export interface ModelRoute {
  modelRouteId: string;
  spaceId: string;
  version: number;
  purpose: "CHAT" | "EMBEDDING" | "RERANK";
  egressClass: "LOCAL" | "CLOUD";
  failoverPolicy: "NONE" | "SAME_EGRESS_ONLY";
  candidates: Array<{ modelProfileId: string; priority: number; egressClass: "LOCAL" | "CLOUD" }>;
  status: "DRAFT" | "ACTIVE" | "DISABLED";
  createdAt: string;
  updatedAt: string;
}

export interface PromptTemplate {
  promptTemplateId: string;
  spaceId: string;
  name: string;
  purpose: "CHAT" | "EMBEDDING" | "RERANK";
  currentVersion: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface PromptVersion {
  promptVersionId: string;
  spaceId: string;
  promptTemplateId: string;
  version: number;
  state: "DRAFT" | "PUBLISHED";
  messages: Array<{ role: "SYSTEM" | "USER" | "ASSISTANT" | "TOOL"; content: string }>;
  variableSchema: Record<string, unknown>;
  outputContract: Record<string, unknown>;
  contentHash: string;
  immutableAfterPublish: boolean;
  publishedAt: string | null;
  createdAt: string;
}

export interface AnswerDefaults {
  routeVersionId: string;
  profileVersionId: string;
  providerConnectionId: string;
  promptVersionId: string;
  model: string;
  datasetHash: string;
  configHash: string;
  allowCloudEgress: boolean;
}

export interface SpaceBinding {
  spaceBindingId: string;
  spaceId: string;
  version: number;
  chatRouteId: string;
  embeddingRouteId: string;
  rerankRouteId: string;
  promptVersionId: string;
  cloudEgressEnabled: boolean;
  cloudEgressAuthorization?: {
    approvalId: string;
    approvedBy: string;
    approvedAt: string;
    expiresAt: string;
    scope: "CHAT" | "EMBEDDING" | "RERANK" | "ALL";
  } | null;
  createdAt: string;
  updatedAt: string;
}

export interface SourceDocument {
  id: string;
  spaceId: string;
  sourceId: string;
  stableSourceObjectId: string;
  canonicalSourcePath: string;
  basename: string;
  versionNo: number;
  state: string;
  activeRevisionId: string | null;
}

export interface IngestionJobView {
  job: { id: string; spaceId: string; sourceId: string; sourceDocumentId: string | null; documentRevisionId: string | null; status: string; createdAt: string; updatedAt: string };
  attempts: Array<{ id: string; attemptNo: number; status: string; startedAt: string; finishedAt: string | null }>;
  steps: Array<{ id: string; stepName: string; status: string; errorCode: string | null; startedAt: string; finishedAt: string | null }>;
}

export interface ParseReportView {
  parseReportId: string;
  documentRevisionId: string;
  status: string;
  mediaType: string;
  pageCount: number;
  characterCount: number;
  tokenCount: number;
  parserName: string;
  parserVersion: string;
  durationMs: number;
  warnings: string;
  errors: string;
  extractedTextArtifactId: string | null;
  createdAt: string;
}

export interface IndexView {
  indexVersionId: string;
  spaceId: string;
  versionNo: number;
  state: string;
  candidateCollection: string;
  embeddingProfileVersion: string;
  chunkingStrategyVersion: string;
  documentRevisionCount: number;
  childChunkCount: number;
  validationVectorDimension: number | null;
  sampleRetrievalPassed: boolean | null;
  spaceFilterPassed: boolean | null;
  activatedAt: string | null;
  createdAt: string;
  datasetHash?: string;
}

export interface ActiveIndexView { pointer: { activeIndexVersionId: string }; index: IndexView | null; datasetHash: string | null }

export interface RunSnapshot {
  runId: string;
  spaceId: string;
  conversationId: string;
  version: number;
  status: string;
  modelRouteId: string;
  promptVersionId: string;
  usageLedgerId: string | null;
  cancelRequested: boolean;
  error: { errorClass: string; retryable: boolean; message: string; correlationId: string; retryAfterSeconds: number | null } | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  lastSequence: number;
  steps: Array<{ stepId: string; type: string; status: string; sequence: number; attempt: number; createdAt: string; finishedAt: string | null; error: RunSnapshot["error"] }>;
}

export interface Anchor {
  headingPath: string[];
  pageNumber?: number;
  sheet?: string;
  slideNumber?: number;
  lineRange?: { startLine: number; endLine: number };
  tableCell?: string;
}

export interface ChunkStudioProjection {
  spaceId: string;
  documentRevisionId: string;
  childChunkId: string;
  parentChunkId: string;
  contentRef: string;
  textHash: string;
  parentChild: {
    parentChunkId: string;
    childChunkId: string;
    relationship: "CHILD_OF";
    parentContentRef: string;
    childIndex: number;
  };
  provenance: {
    sourceId: string;
    documentId: string;
    documentRevisionId: string;
    sourcePath: string;
    revisionVersion: number;
  };
  anchor: Anchor;
  vectorStatus: {
    state: "NOT_INDEXED" | "PENDING" | "INDEXED" | "STALE" | "FAILED";
    indexVersionId: string | null;
    vectorDimension: number | null;
    updatedAt: string;
  };
  override: {
    overrideId: string | null;
    state: "NONE" | "ACTIVE" | "NEEDS_REVIEW" | "DISCARDED";
    version: number;
    reason: string | null;
    createdBy: string | null;
    createdAt: string | null;
    updatedAt: string | null;
  };
}

export interface ChunkOverrideResponse {
  spaceId: string;
  documentRevisionId: string;
  childChunkId: string;
  contentRef: string;
  textHash: string;
  override: {
    overrideId: string;
    state: "NONE" | "ACTIVE" | "NEEDS_REVIEW" | "DISCARDED";
    version: number;
    source: "MANUAL";
    reason: string;
    replacedTextHash?: string;
    createdBy: string;
    createdAt: string;
    updatedAt: string;
  };
}

export interface CreateChunkOverrideRequest {
  documentRevisionId: string;
  contentRef: string;
  textHash: string;
  reason: string;
}

export type ChunkOverrideTargetState = "ACTIVE" | "NEEDS_REVIEW" | "DISCARDED";

export interface TransitionChunkOverrideRequest {
  targetState: ChunkOverrideTargetState;
  expectedVersion: number;
  reason: string;
}

export interface RetrievalHit {
  childChunkId: string;
  documentRevisionId: string;
  rank: number;
  score: number;
  contentRef: string;
  textHash: string;
}

export interface StageTrace {
  items: RetrievalHit[];
  metrics: { candidateCount: number; latencyMs: number };
}

export interface RetrievalTrace {
  dense: StageTrace;
  bm25: StageTrace;
  rrf: StageTrace;
  rerank: StageTrace;
  context: { childChunkIds: string[]; totalTokens: number; maxContextTokens: number; truncated: boolean };
  evidence: {
    items: Array<{
      evidenceId: string;
      spaceId: string;
      childChunkId: string;
      documentRevisionId: string;
      contentRef: string;
      textHash: string;
      anchor: Anchor;
      citationAllowed: true;
    }>;
    allowListVersion: string;
  };
}

export interface RetrievalSide {
  indexVersionId: string;
  profile: { profileId: string; version: number; candidateOnly: true };
  trace: RetrievalTrace;
  metrics: { latencyMs: number; evidenceCount: number };
}

export interface RetrievalExperiment {
  experimentId: string;
  spaceId: string;
  query: string;
  normalizedQuery: string;
  indexVersionId: string;
  profileA: RetrievalSide;
  profileB: RetrievalSide | null;
  abstention: {
    profileA: { abstained: boolean; reasonCode: "NO_EVIDENCE" | "LOW_CONFIDENCE" | "POLICY_BLOCKED" | null };
    profileB: { abstained: boolean; reasonCode: "NO_EVIDENCE" | "LOW_CONFIDENCE" | "POLICY_BLOCKED" | null } | null;
  };
  activeProfileUnchanged: true;
}

type ApiFetchOptions = Omit<RequestInit, "body" | "headers" | "method"> & {
  method?: string;
  body?: unknown;
  headers?: HeadersInit;
  idempotencyKey?: string;
  correlationId?: string;
};

let csrfToken: string | null = null;

function uuidV7(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  const timestamp = Date.now();
  for (let index = 5; index >= 0; index -= 1) {
    bytes[index] = timestamp / 2 ** (8 * (5 - index)) % 256;
  }
  bytes[6] = bytes[6] & 0x0f | 0x70;
  bytes[8] = bytes[8] & 0x3f | 0x80;
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function idempotencyKey(): string {
  return crypto.randomUUID().replaceAll("-", "");
}

async function parseResponse(response: Response): Promise<unknown> {
  if (response.status === 204) return null;
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("json")) return response.json();
  return response.text();
}

async function readProblem(response: Response): Promise<ProblemDetails | null> {
  try {
    const payload = await parseResponse(response);
    if (typeof payload === "object" && payload !== null && "status" in payload && "detail" in payload) {
      return payload as ProblemDetails;
    }
  } catch {
    // The caller still receives the HTTP status and a safe generic message.
  }
  return null;
}

async function authFetch<T>(path: string, body: RegisterRequest | LoginRequest): Promise<T> {
  const response = await fetch(path, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "X-Correlation-Id": uuidV7(),
      "Idempotency-Key": idempotencyKey(),
    },
    credentials: "include",
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const problem = await readProblem(response);
    throw new ApiError(problem?.detail ?? `请求失败（HTTP ${response.status}）`, response.status, problem,
      problem?.correlationId ?? response.headers.get("X-Correlation-Id"));
  }
  return (await parseResponse(response)) as T;
}

export async function registerUser(request: RegisterRequest): Promise<void> {
  await authFetch("/api/v1/auth/register", request);
}

export async function loginUser(request: LoginRequest): Promise<CurrentSession> {
  const current = await authFetch<CurrentSession>("/api/v1/auth/login", request);
  csrfToken = current.session.csrfToken;
  return current;
}

export async function logoutCurrentSession(): Promise<void> {
  await apiFetch<void>("/api/v1/sessions/current", { method: "DELETE" });
  csrfToken = null;
}

export async function apiFetch<T>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  const method = (options.method ?? "GET").toUpperCase();
  const mutating = !["GET", "HEAD", "OPTIONS"].includes(method);
  if (mutating && !csrfToken) {
    const session = await fetchCurrentSession();
    csrfToken = session.session.csrfToken;
  }

  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  headers.set("X-Correlation-Id", options.correlationId ?? uuidV7());
  if (mutating) {
    headers.set("X-CSRF-Token", csrfToken ?? "");
    headers.set("Idempotency-Key", options.idempotencyKey ?? idempotencyKey());
  }
  if (options.body !== undefined) headers.set("Content-Type", "application/json");

  const response = await fetch(path, {
    ...options,
    method,
    headers,
    credentials: "include",
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  if (!response.ok) {
    const problem = await readProblem(response);
    const correlationId = problem?.correlationId ?? response.headers.get("X-Correlation-Id");
    const detail = problem?.detail ?? `请求失败（HTTP ${response.status}）`;
    throw new ApiError(detail, response.status, problem, correlationId);
  }
  return (await parseResponse(response)) as T;
}

async function fetchCurrentSession(): Promise<CurrentSession> {
  const response = await fetch("/api/v1/sessions/current", {
    headers: { Accept: "application/json", "X-Correlation-Id": uuidV7() },
    credentials: "include",
  });
  if (!response.ok) {
    const problem = await readProblem(response);
    throw new ApiError(problem?.detail ?? "当前会话不可用，请重新登录。", response.status, problem, problem?.correlationId ?? null);
  }
  const session = (await parseResponse(response)) as CurrentSession;
  csrfToken = session.session.csrfToken;
  return session;
}

export async function getCurrentSession(): Promise<CurrentSession> {
  return fetchCurrentSession();
}

export function listUsers(): Promise<{ items: ManagedUser[]; nextCursor: string | null }> {
  return apiFetch("/api/v1/users?limit=100");
}

export function createManagedUser(payload: { email: string; displayName: string; password: string }): Promise<ManagedUser> {
  return apiFetch("/api/v1/users", { method: "POST", body: payload });
}

export function updateManagedUser(userId: string, payload: { displayName: string; platformRole: PlatformRole; status: ManagedUser["status"]; password?: string }): Promise<ManagedUser> {
  return apiFetch(`/api/v1/users/${encodeURIComponent(userId)}`, { method: "PUT", body: payload });
}

export function disableManagedUser(userId: string): Promise<ManagedUser> {
  return apiFetch(`/api/v1/users/${encodeURIComponent(userId)}`, { method: "DELETE" });
}

export function listSpaceMembers(spaceId: string): Promise<{ items: SpaceMember[] }> {
  return apiFetch(`/api/v1/spaces/${encodeURIComponent(spaceId)}/members`);
}

export function updateSpace(spaceId: string, payload: { name: string; description: string; version: number }): Promise<Space> {
  return apiFetch(`/api/v1/spaces/${encodeURIComponent(spaceId)}`, { method: "PUT", body: payload });
}

export function archiveSpace(spaceId: string, version: number): Promise<void> {
  return apiFetch(`/api/v1/spaces/${encodeURIComponent(spaceId)}`, { method: "DELETE", body: { version } });
}

export function updateSpaceMember(spaceId: string, userId: string, role: SpaceRole): Promise<unknown> {
  return apiFetch(`/api/v1/spaces/${encodeURIComponent(spaceId)}/members/${encodeURIComponent(userId)}`, { method: "PUT", body: { role } });
}

export function removeSpaceMember(spaceId: string, userId: string): Promise<void> {
  return apiFetch(`/api/v1/spaces/${encodeURIComponent(spaceId)}/members/${encodeURIComponent(userId)}`, { method: "DELETE" });
}

export function clearCsrfToken(): void {
  csrfToken = null;
}

export async function uploadMarkdown(spaceId: string, file: File, relativePath?: string): Promise<{ jobId: string; documentRevisionId: string; sourceId: string }> {
  if (!csrfToken) await fetchCurrentSession();
  const form = new FormData();
  form.append("file", file, relativePath || file.name);
  const response = await fetch(`/api/v1/spaces/${encodeURIComponent(spaceId)}/sources/uploads`, {
    method: "POST", credentials: "include", body: form,
    headers: { Accept: "application/json", "X-Correlation-Id": uuidV7(), "X-CSRF-Token": csrfToken ?? "", "Idempotency-Key": idempotencyKey() },
  });
  if (!response.ok) {
    const problem = await readProblem(response);
    throw new ApiError(problem?.detail ?? `上传失败（HTTP ${response.status}）`, response.status, problem, problem?.correlationId ?? null);
  }
  return (await parseResponse(response)) as { jobId: string; documentRevisionId: string; sourceId: string };
}

export function ingestWebSource(spaceId: string, url: string, allowCloudEgress: boolean): Promise<{ jobId: string; documentRevisionId: string; sourceId: string }> {
  return apiFetch("/api/v1/spaces/" + encodeURIComponent(spaceId) + "/sources/web", {
    method: "POST",
    body: { url, allowCloudEgress },
  });
}

export function listIngestionJobs(spaceId: string): Promise<IngestionJobView[]> {
  return apiFetch(`/api/v1/spaces/${encodeURIComponent(spaceId)}/ingestion-jobs`);
}

export function getIngestionJob(spaceId: string, jobId: string): Promise<IngestionJobView> {
  return apiFetch(`/api/v1/spaces/${encodeURIComponent(spaceId)}/ingestion-jobs/${encodeURIComponent(jobId)}`);
}

export function getParseReport(spaceId: string, revisionId: string): Promise<ParseReportView> {
  return apiFetch(`/api/v1/spaces/${encodeURIComponent(spaceId)}/document-revisions/${encodeURIComponent(revisionId)}/parse-report`);
}

export function listIndexes(spaceId: string): Promise<IndexView[]> {
  return apiFetch(`/api/v1/spaces/${encodeURIComponent(spaceId)}/indexes`);
}

export function getActiveIndex(spaceId: string): Promise<ActiveIndexView | null> {
  return apiFetch(`/api/v1/spaces/${encodeURIComponent(spaceId)}/indexes/active`);
}

export function publishIndex(spaceId: string, indexVersionId: string): Promise<{ activeIndexVersionId: string }> {
  return apiFetch(`/api/v1/spaces/${encodeURIComponent(spaceId)}/indexes/${encodeURIComponent(indexVersionId)}/publish`, { method: "POST" });
}
