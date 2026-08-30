import type { Page, Route } from "@playwright/test";

export const SYNTHETIC = Object.freeze({
  spaceId: "0190f5c2-7c1e-7abc-8def-1234567890ab",
  otherSpaceId: "0190f5c2-7c1e-7abc-8def-1234567890ac",
  userId: "0190f5c2-7c1e-7abc-8def-1234567890ad",
  sessionId: "0190f5c2-7c1e-7abc-8def-1234567890ae",
  csrfToken: "synthetic-csrf-token",
  routeId: "0190f5c2-7c1e-7abc-8def-1234567890af",
  profileId: "0190f5c2-7c1e-7abc-8def-1234567890b0",
  providerId: "0190f5c2-7c1e-7abc-8def-1234567890b1",
  promptTemplateId: "0190f5c2-7c1e-7abc-8def-1234567890b2",
  promptVersionId: "0190f5c2-7c1e-7abc-8def-1234567890b3",
  indexId: "0190f5c2-7c1e-7abc-8def-1234567890b4",
  sourceId: "0190f5c2-7c1e-7abc-8def-1234567890b5",
  revisionId: "0190f5c2-7c1e-7abc-8def-1234567890b6",
  jobId: "0190f5c2-7c1e-7abc-8def-1234567890b7",
  conversationId: "0190f5c2-7c1e-7abc-8def-1234567890b8",
  runId: "0190f5c2-7c1e-7abc-8def-1234567890b9",
  correlationId: "0190f5c2-7c1e-7abc-8def-1234567890ba",
  answerId: "0190f5c2-7c1e-7abc-8def-1234567890bb",
  evidenceId: "0190f5c2-7c1e-7abc-8def-1234567890bc",
  question: "synthetic-question",
  datasetHash: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  configHash: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  textHash: "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
});

export type SyntheticRole = "SPACE_ADMIN" | "VIEWER";
export type SyntheticRequest = { method: string; path: string; body: string | null };
export type SyntheticApi = {
  install(page: Page): Promise<void>;
  requests: SyntheticRequest[];
  snapshot(): { uploaded: boolean; published: boolean; cancelled: boolean; conversationStatus: string };
};

const now = "2026-08-30T00:00:00.000Z";
const problem = (status: number, code: string, detail: string, path: string): object => ({
  type: "https://errors.ragforge.local/problems/" + code.toLowerCase(),
  title: status === 403 ? "Forbidden" : "Unauthorized", status, detail, instance: path, code, correlationId: SYNTHETIC.correlationId,
});
const session = (): object => ({
  session: { sessionId: SYNTHETIC.sessionId, userId: SYNTHETIC.userId, expiresAt: "2026-08-31T00:00:00.000Z", csrfToken: SYNTHETIC.csrfToken },
  user: { userId: SYNTHETIC.userId, email: "synthetic@example.invalid", displayName: "Synthetic User", platformRole: "USER" },
});
const space = (role: SyntheticRole): object => ({
  spaceId: SYNTHETIC.spaceId, name: "Synthetic Space", description: "isolated fixture", status: "ACTIVE", role, createdAt: now, version: 1,
});
const job = (status: string): object => ({
  job: { id: SYNTHETIC.jobId, spaceId: SYNTHETIC.spaceId, sourceId: SYNTHETIC.sourceId, sourceDocumentId: "0190f5c2-7c1e-7abc-8def-1234567890bd", documentRevisionId: SYNTHETIC.revisionId, status, version: 1, error: null, createdAt: now, updatedAt: now },
  steps: [{ id: "0190f5c2-7c1e-7abc-8def-1234567890be", stepName: "parse", status: status === "SUCCEEDED" ? "SUCCEEDED" : "RUNNING", errorCode: null, errorMessage: null, errorDetail: null, startedAt: now, finishedAt: status === "SUCCEEDED" ? now : null }],
});
const citation = (): object => ({
  evidenceId: SYNTHETIC.evidenceId, claimId: "0190f5c2-7c1e-7abc-8def-1234567890bf", spaceId: SYNTHETIC.spaceId, correlationId: SYNTHETIC.correlationId, runId: SYNTHETIC.runId,
  evidenceBundleId: "0190f5c2-7c1e-7abc-8def-1234567890c0", evidenceBundleVersion: 1, evidenceBundleHash: SYNTHETIC.textHash, indexVersionId: SYNTHETIC.indexId, retrievalProfileId: SYNTHETIC.profileId, retrievalProfileVersion: 1, documentRevisionId: SYNTHETIC.revisionId, parentChunkId: "0190f5c2-7c1e-7abc-8def-1234567890c1", childChunkId: "0190f5c2-7c1e-7abc-8def-1234567890c2", contentRef: "synthetic-space/revision/chunk", textHash: SYNTHETIC.textHash,
  anchor: { headingPath: ["Synthetic heading"], pageNumber: null, sheet: null, slideNumber: null, lineStart: 1, lineEnd: 2, tableCell: null }, answerCharStart: 0, answerCharEnd: 24, citationAllowed: true,
});
const answer = (status = "COMPLETED"): object => ({
  schemaVersion: "v1", answerId: SYNTHETIC.answerId, spaceId: SYNTHETIC.spaceId, correlationId: SYNTHETIC.correlationId, runId: SYNTHETIC.runId, idempotencyKey: "synthetic-idempotency-key", status, answerText: status === "COMPLETED" ? "synthetic answer" : null, claims: [], citations: status === "COMPLETED" ? [citation()] : [], abstention: null, toolCallIds: [],
  provenance: { schemaVersion: "v1", spaceId: SYNTHETIC.spaceId, correlationId: SYNTHETIC.correlationId, runId: SYNTHETIC.runId, idempotencyKey: "synthetic-idempotency-key", evidenceBundleId: "0190f5c2-7c1e-7abc-8def-1234567890c0", evidenceBundleVersion: 1, evidenceBundleHash: SYNTHETIC.textHash, evidenceBundleRef: "synthetic-space/evidence", indexVersionId: SYNTHETIC.indexId, retrievalProfileId: SYNTHETIC.profileId, retrievalProfileVersion: 1, ragPromptVersionId: SYNTHETIC.promptVersionId, promptHash: SYNTHETIC.configHash, modelRouteVersionId: SYNTHETIC.routeId, modelProfileVersionId: SYNTHETIC.profileId, modelVersion: "synthetic-model", toolSchemaVersionsJson: "{}", datasetHash: SYNTHETIC.datasetHash, configHash: SYNTHETIC.configHash, traceId: SYNTHETIC.correlationId },
  createdAt: now,
});

export function createSyntheticApi(options: { role?: SyntheticRole; hasSpace?: boolean; largeCollections?: boolean } = {}): SyntheticApi {
  const role = options.role ?? "SPACE_ADMIN";
  let authenticated = false;
  let hasSpace = options.hasSpace ?? true;
  let uploaded = false;
  let published = false;
  let cancelled = false;
  let conversationStatus = "ACTIVE";
  let jobPolls = 0;
  const requests: SyntheticRequest[] = [];
  const conversations = [{ id: SYNTHETIC.conversationId, title: "Synthetic conversation", status: "ACTIVE", version: 1, createdAt: now, updatedAt: now }];
  const page = <T>(items: T[], url: URL): { items: T[]; nextCursor: string | null } => {
    const size = Math.min(Math.max(Number(url.searchParams.get("limit") ?? 20) || 20, 1), 20);
    const cursor = url.searchParams.get("cursor");
    const start = cursor?.startsWith("synthetic-") ? Number(cursor.slice("synthetic-".length)) || 0 : 0;
    const result = items.slice(start, start + size);
    return { items: result, nextCursor: start + result.length < items.length ? `synthetic-${start + result.length}` : null };
  };
  const largeSources = Array.from({ length: 120 }, (_, index) => ({ source: { sourceId: `0190f5c2-7c1e-7abc-8def-${String(2000 + index).padStart(12, "0")}`, spaceId: SYNTHETIC.spaceId, displayName: `Synthetic source ${index + 1}`, connectorType: "GIT", rootRef: `synthetic/source/${index + 1}`, gitBranch: "main", versionNo: 1, sourceState: "ACTIVE" }, checkpoint: null }));
  const largeJobs = Array.from({ length: 7 }, (_, index) => ({ ...job("SUCCEEDED"), job: { ...job("SUCCEEDED").job, id: `0190f5c2-7c1e-7abc-8def-${String(3000 + index).padStart(12, "0")}` } }));
  const largeIndexes = Array.from({ length: 6 }, (_, index) => ({ indexVersionId: `0190f5c2-7c1e-7abc-8def-${String(4000 + index).padStart(12, "0")}`, spaceId: SYNTHETIC.spaceId, versionNo: index + 1, state: index === 0 && published ? "ACTIVE" : "READY", childChunkCount: 1, validationVectorDimension: 3 }));
  const fulfill = async (route: Route, status: number, body: object | string, contentType = "application/json"): Promise<void> => {
    await route.fulfill({ status, contentType, headers: { "X-Correlation-Id": SYNTHETIC.correlationId }, body: typeof body === "string" ? body : JSON.stringify(body) });
  };
  const handle = async (route: Route): Promise<void> => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    requests.push({ method: request.method(), path, body: request.postData() });
    if (path === "/api/v1/sessions/current") {
      if (!authenticated) return fulfill(route, 401, problem(401, "UNAUTHORIZED", "synthetic session required", path));
      return fulfill(route, 200, session());
    }
    if (path === "/api/v1/bootstrap/platform-admin") return fulfill(route, 200, { required: false, available: false });
    if (path === "/api/v1/auth/login" && request.method() === "POST") { authenticated = true; return fulfill(route, 200, session()); }
    if (path === "/api/v1/auth/register" && request.method() === "POST") return fulfill(route, 201, {});
    const spaceMatch = path.match(/\/api\/v1\/spaces\/([^/]+)/);
    const requestedSpace = spaceMatch?.[1];
    if (requestedSpace === SYNTHETIC.otherSpaceId || (requestedSpace && requestedSpace !== SYNTHETIC.spaceId)) return fulfill(route, 403, problem(403, "SPACE_ACCESS_DENIED", "synthetic space access denied", path));
    if (!authenticated) return fulfill(route, 401, problem(401, "UNAUTHORIZED", "synthetic session required", path));
    if (role === "VIEWER" && request.method() !== "GET" && path.includes("/sources")) return fulfill(route, 403, problem(403, "SPACE_ACCESS_DENIED", "viewer cannot mutate synthetic space", path));
    if (path === "/api/v1/spaces" && request.method() === "GET") return fulfill(route, 200, { items: hasSpace ? [space(role)] : [], nextCursor: null });
    if (path === "/api/v1/spaces" && request.method() === "POST") { hasSpace = true; return fulfill(route, 201, space(role)); }
    if (path.endsWith("/events") && request.method() === "GET") {
      const status = cancelled ? "CANCELLED" : "COMPLETED";
      const makeEvent = (eventType: string, sequence: number, payload: object): string => "id: " + SYNTHETIC.correlationId + "-" + sequence + "\\ndata: " + JSON.stringify({ schema_version: "v1", event_id: SYNTHETIC.correlationId + "-" + sequence, event_type: eventType, sequence, idempotency_key: "synthetic-idempotency-key", space_id: SYNTHETIC.spaceId, correlation_id: SYNTHETIC.correlationId, run_id: SYNTHETIC.runId, occurred_at: now, payload }) + "\\n\\n";
      const stream = status === "COMPLETED" ? makeEvent("answer.delta", 1, { delta: "synthetic answer" }) + makeEvent("answer.citation", 2, { citation: citation() }) + makeEvent("answer.done", 3, { answerId: SYNTHETIC.answerId, status }) : makeEvent("answer.done", 1, { answerId: SYNTHETIC.answerId, status });
      return fulfill(route, 200, stream, "text/event-stream");
    }
    if (path.endsWith("/provider-connections")) return fulfill(route, 200, { items: [{ providerConnectionId: SYNTHETIC.providerId, providerType: "OLLAMA", endpoint: "http://synthetic.local", status: "ACTIVE", egressClass: "LOCAL", version: 1 }] });
    if (path.endsWith("/model-profiles")) return fulfill(route, 200, { items: [{ modelProfileId: SYNTHETIC.profileId, providerConnectionId: SYNTHETIC.providerId, purpose: "CHAT", modelName: "synthetic-model", capabilities: ["CHAT"], verifiedCapabilities: ["CHAT"], status: "PUBLISHED", version: 1 }] });
    if (path.endsWith("/model-routes")) return fulfill(route, 200, { items: [{ modelRouteId: SYNTHETIC.routeId, purpose: "CHAT", egressClass: "LOCAL", failoverPolicy: "NONE", status: "ACTIVE", version: 1, candidates: [{ modelProfileId: SYNTHETIC.profileId, priority: 1, egressClass: "LOCAL" }] }] });
    if (path.endsWith("/prompt-templates")) return fulfill(route, 200, { items: [{ promptTemplateId: SYNTHETIC.promptTemplateId, name: "Synthetic template", purpose: "CHAT", currentVersion: 1 }] });
    if (path.includes("/prompt-templates/") && path.endsWith("/versions/1")) return fulfill(route, 200, { promptTemplateId: SYNTHETIC.promptTemplateId, promptVersionId: SYNTHETIC.promptVersionId, version: 1, state: "PUBLISHED", contentHash: SYNTHETIC.configHash, messages: [], variableSchema: {}, outputContract: {} });
    if (path.endsWith("/indexes/active")) return fulfill(route, 200, published ? { pointer: { activeIndexVersionId: SYNTHETIC.indexId }, index: { indexVersionId: SYNTHETIC.indexId, versionNo: 1, state: "ACTIVE", childChunkCount: 1, validationVectorDimension: 3 }, datasetHash: SYNTHETIC.datasetHash } : null);
    if (path.endsWith("/indexes") && request.method() === "GET") return fulfill(route, 200, page(options.largeCollections ? (uploaded ? largeIndexes : []) : (uploaded ? [{ indexVersionId: SYNTHETIC.indexId, versionNo: 1, state: published ? "ACTIVE" : "READY", childChunkCount: 1, validationVectorDimension: 3 }] : []), url));
    if (path.includes("/indexes/" + SYNTHETIC.indexId + "/publish") && request.method() === "POST") { published = true; return fulfill(route, 200, { activeIndexVersionId: SYNTHETIC.indexId }); }
    if (path.endsWith("/sources/uploads") && request.method() === "POST") { uploaded = true; jobPolls = 0; return fulfill(route, 201, { jobId: SYNTHETIC.jobId, documentRevisionId: SYNTHETIC.revisionId, sourceId: SYNTHETIC.sourceId }); }
    if (path.includes("/ingestion-jobs/") && request.method() === "GET") { jobPolls += 1; return fulfill(route, 200, job(jobPolls > 0 ? "SUCCEEDED" : "RUNNING")); }
    if (path.endsWith("/ingestion-jobs") && request.method() === "GET") return fulfill(route, 200, uploaded ? [job("SUCCEEDED")] : []);
    if (path.includes("/document-revisions/") && path.endsWith("/parse-report")) return fulfill(route, 200, { spaceId: SYNTHETIC.spaceId, documentRevisionId: SYNTHETIC.revisionId, status: "SUCCEEDED", parserName: "synthetic-parser", parserVersion: "1", characterCount: 10, tokenCount: 3, errors: null });
    if (path.endsWith("/sources") && request.method() === "GET") return fulfill(route, 200, page(options.largeCollections ? largeSources : [], url));
    if (path.endsWith("/jobs") && request.method() === "GET") return fulfill(route, 200, page(options.largeCollections ? (uploaded ? largeJobs : []) : [], url));
    if (path.endsWith("/space-bindings") && request.method() === "GET") return fulfill(route, 200, { version: 1, chatRouteId: SYNTHETIC.routeId, embeddingRouteId: null, rerankRouteId: null, promptVersionId: SYNTHETIC.promptVersionId, cloudEgressEnabled: false, cloudEgressAuthorization: null });
    if (path.endsWith("/conversations") && request.method() === "GET") return fulfill(route, 200, { items: conversationStatus === "ARCHIVED" && url.searchParams.get("includeArchived") !== "true" ? [] : conversations.map((item) => ({ ...item, status: conversationStatus })), nextCursor: null });
    if (path.endsWith("/conversations") && request.method() === "POST") return fulfill(route, 201, { id: SYNTHETIC.conversationId, conversationId: SYNTHETIC.conversationId, title: "Synthetic conversation", status: "ACTIVE", version: 1, createdAt: now, updatedAt: now, spaceId: SYNTHETIC.spaceId });
    if (path.includes("/conversations/" + SYNTHETIC.conversationId + "/archive") && request.method() === "POST") { conversationStatus = "ARCHIVED"; return fulfill(route, 200, { id: SYNTHETIC.conversationId, status: "ARCHIVED" }); }
    if (path.includes("/conversations/" + SYNTHETIC.conversationId + "/runs") && request.method() === "GET") return fulfill(route, 200, { items: [{ runId: SYNTHETIC.runId, conversationId: SYNTHETIC.conversationId, spaceId: SYNTHETIC.spaceId, status: cancelled ? "CANCELLED" : "COMPLETED", createdAt: now }], nextCursor: null });
    if (path.includes("/conversations/" + SYNTHETIC.conversationId + "/runs") && request.method() === "POST") return fulfill(route, 202, { runId: SYNTHETIC.runId, conversationId: SYNTHETIC.conversationId, spaceId: SYNTHETIC.spaceId, version: 1, status: cancelled ? "CANCELLED" : "IN_PROGRESS", correlationId: SYNTHETIC.correlationId, modelRouteId: SYNTHETIC.routeId, promptVersionId: SYNTHETIC.promptVersionId, usageLedgerId: null, cancelRequested: false, error: null, createdAt: now, startedAt: now, finishedAt: null });
    if (path.includes("/answers/" + SYNTHETIC.runId + "/cancel") && request.method() === "POST") { cancelled = true; return fulfill(route, 202, { runId: SYNTHETIC.runId, spaceId: SYNTHETIC.spaceId, status: "CANCELLED", firstCancellation: true, eventId: null, correlationId: SYNTHETIC.correlationId, reason: "synthetic cancellation" }); }
    if (path.includes("/answers/" + SYNTHETIC.runId + "/feedback") && request.method() === "POST") return fulfill(route, 201, { id: SYNTHETIC.evidenceId, spaceId: SYNTHETIC.spaceId, runId: SYNTHETIC.runId, evidenceId: SYNTHETIC.evidenceId, actorUserId: SYNTHETIC.userId, sentiment: "HELPFUL", version: 1, createdAt: now, updatedAt: now });
    if (path.includes("/answers/" + SYNTHETIC.runId) && request.method() === "GET") return fulfill(route, 200, answer(cancelled ? "CANCELLED" : "COMPLETED"));
    if (path.endsWith("/answers") && request.method() === "POST") return fulfill(route, 202, answer(cancelled ? "CANCELLED" : "COMPLETED"));
    if (path.includes("/citations/") && path.endsWith("/preview")) return fulfill(route, 200, citation());
    if (path.includes("/runs/") && path.endsWith("/cancel") && request.method() === "POST") { cancelled = true; return fulfill(route, 202, { runId: SYNTHETIC.runId, spaceId: SYNTHETIC.spaceId, status: "CANCELLED", firstCancellation: true, correlationId: SYNTHETIC.correlationId }); }
    return fulfill(route, request.method() === "GET" ? 200 : 201, request.method() === "GET" ? { items: [], nextCursor: null } : {});
  };
  return { requests, install: async (page) => { await page.route("**/api/v1/**", handle); }, snapshot: () => ({ uploaded, published, cancelled, conversationStatus }) };
}
