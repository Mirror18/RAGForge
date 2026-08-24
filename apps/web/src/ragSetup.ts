import { ApiError, apiFetch, type ModelProfile, type ModelRoute, type PromptTemplate, type PromptVersion, type ProviderConnection } from "./api";

function path(spaceId: string, suffix: string): string {
  return `/api/v1/spaces/${encodeURIComponent(spaceId)}${suffix}`;
}

const structuredAnswerPrompt = [
  { role: "SYSTEM" as const, content: "Use only the supplied evidence. Return ONLY valid JSON with this exact shape: {\"answer_text\":\"brief answer\",\"claims\":[{\"claim_text\":\"one supported claim\",\"citation_tokens\":[\"exact evidence UUIDv7 from the evidence id attribute\"]}]}. Do not use Markdown, code fences, extra keys, character offsets, or invented IDs. Every claim_text must be copied as an exact contiguous substring of answer_text, including punctuation and spacing. Every citation token must copy an exact evidence UUIDv7 from the supplied evidence block. If evidence is insufficient, return a short answer and make claim_text the exact matching substring from answer_text." },
  { role: "USER" as const, content: "{{query}}" },
];

export async function initializeLocalRag(spaceId: string): Promise<void> {
  const providerPage = await apiFetch<{ items: ProviderConnection[] }>(path(spaceId, "/provider-connections?limit=100"));
  let localProvider = providerPage.items.find((item) => item.providerType === "OLLAMA" && item.egressClass === "LOCAL" && item.status === "ACTIVE");
  if (!localProvider) {
    localProvider = await apiFetch<ProviderConnection>(path(spaceId, "/provider-connections"), {
      method: "POST",
      body: { displayName: "本地 Ollama", providerType: "OLLAMA", egressClass: "LOCAL", endpoint: "http://127.0.0.1:11434", credentialRef: "local-ollama", status: "ACTIVE" },
    });
  }

  const profilePage = await apiFetch<{ items: ModelProfile[] }>(path(spaceId, "/model-profiles?limit=100"));
  const profileDefinitions = [
    { purpose: "CHAT" as const, modelName: "qwen3.5:9b", capabilities: ["CHAT", "STREAMING", "TOOLS", "USAGE_REPORTING"], embeddingDimension: null },
    { purpose: "EMBEDDING" as const, modelName: "nomic-embed-text:latest", capabilities: ["EMBEDDING"], embeddingDimension: 768 },
    { purpose: "RERANK" as const, modelName: "qwen3.5:9b", capabilities: ["RERANK"], embeddingDimension: null },
  ];
  const profiles: Record<string, ModelProfile> = {};
  for (const definition of profileDefinitions) {
    const existing = profilePage.items.find((item) => item.providerConnectionId === localProvider.providerConnectionId && item.purpose === definition.purpose && item.modelName === definition.modelName && item.status === "PUBLISHED" && item.embeddingDimension === definition.embeddingDimension);
    profiles[definition.purpose] = existing ?? await apiFetch<ModelProfile>(path(spaceId, "/model-profiles"), {
      method: "POST",
      body: { providerConnectionId: localProvider.providerConnectionId, ...definition, contextWindow: 8192, maxOutputTokens: 1024, usageReporting: definition.purpose === "CHAT" ? "PROVIDER_REPORTED" : "LOCAL_ESTIMATE", status: "PUBLISHED" },
    });
  }

  const routePage = await apiFetch<{ items: ModelRoute[] }>(path(spaceId, "/model-routes?limit=100"));
  const routes: Record<string, ModelRoute> = {};
  for (const purpose of ["CHAT", "EMBEDDING", "RERANK"] as const) {
    const profile = profiles[purpose];
    const existing = routePage.items.find((item) => item.purpose === purpose && item.status === "ACTIVE" && item.candidates.some((candidate) => candidate.modelProfileId === profile.modelProfileId));
    routes[purpose] = existing ?? await apiFetch<ModelRoute>(path(spaceId, "/model-routes"), {
      method: "POST",
      body: { purpose, egressClass: "LOCAL", failoverPolicy: "NONE", candidates: [{ modelProfileId: profile.modelProfileId, priority: 1, egressClass: "LOCAL" }], status: "ACTIVE" },
    });
  }

  const promptPage = await apiFetch<{ items: PromptTemplate[] }>(path(spaceId, "/prompt-templates"));
  let prompt = promptPage.items.find((item) => item.purpose === "CHAT");
  if (!prompt) prompt = await apiFetch<PromptTemplate>(path(spaceId, "/prompt-templates"), { method: "POST", body: { name: "RAGForge 问答 Prompt", purpose: "CHAT" } });
  let promptVersion: PromptVersion | null = prompt.currentVersion ? await apiFetch<PromptVersion>(path(spaceId, `/prompt-templates/${prompt.promptTemplateId}/versions/${prompt.currentVersion}`)) : null;
  const structuredPromptReady = promptVersion?.state === "PUBLISHED" && promptVersion.messages.some((message) => message.content.includes("answer_text") && message.content.includes("citation_tokens") && message.content.includes("exact contiguous substring of answer_text"));
  if (!structuredPromptReady) {
    promptVersion = await apiFetch<PromptVersion>(path(spaceId, `/prompt-templates/${prompt.promptTemplateId}/versions`), {
      method: "POST",
      body: { messages: structuredAnswerPrompt, variableSchema: { type: "object", required: ["context", "question"] }, outputContract: { type: "object", required: ["answer", "citations"] }, changeDescription: "初始化 RAGForge 问答 Prompt" },
    });
    if (!promptVersion) throw new Error("Prompt version 初始化失败");
    promptVersion = await apiFetch<PromptVersion>(path(spaceId, `/prompt-templates/${prompt.promptTemplateId}/versions/${promptVersion.version}/publish`), { method: "POST" });
  }
  if (!promptVersion) throw new Error("Prompt version 不可用");

  let binding: { version: number } | null = null;
  try { binding = await apiFetch<{ version: number }>(path(spaceId, "/space-bindings")); } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 404) throw error;
  }
  await apiFetch(path(spaceId, "/space-bindings"), {
    method: "PUT",
    headers: { "If-Match": `"${binding?.version ?? 0}"` },
    body: { version: binding?.version ?? 1, chatRouteId: routes.CHAT.modelRouteId, embeddingRouteId: routes.EMBEDDING.modelRouteId, rerankRouteId: routes.RERANK.modelRouteId, promptVersionId: promptVersion.promptVersionId, cloudEgressEnabled: false, cloudEgressAuthorization: null },
  });
}

export async function initializeMimoRag(spaceId: string, userId: string): Promise<void> {
  await initializeLocalRag(spaceId);
  const [providersPage, profilesPage, routesPage, binding] = await Promise.all([
    apiFetch<{ items: ProviderConnection[] }>(path(spaceId, "/provider-connections?limit=100")),
    apiFetch<{ items: ModelProfile[] }>(path(spaceId, "/model-profiles?limit=100")),
    apiFetch<{ items: ModelRoute[] }>(path(spaceId, "/model-routes?limit=100")),
    apiFetch<{ version: number; embeddingRouteId: string; rerankRouteId: string; promptVersionId: string }>(path(spaceId, "/space-bindings")),
  ]);
  let mimoProvider = providersPage.items.find((item) => item.providerType === "MIMO" && item.egressClass === "CLOUD" && item.status === "ACTIVE");
  if (!mimoProvider) mimoProvider = await apiFetch<ProviderConnection>(path(spaceId, "/provider-connections"), { method: "POST", body: { displayName: "Xiaomi MiMo 云端", providerType: "MIMO", egressClass: "CLOUD", endpoint: "https://api.xiaomimimo.com", credentialRef: "env:XIAOMI_API_KEY", status: "ACTIVE" } });
  const mimoProfile = profilesPage.items.find((item) => item.providerConnectionId === mimoProvider?.providerConnectionId && item.purpose === "CHAT" && item.modelName === "mimo-v2.5" && item.status === "PUBLISHED") ?? await apiFetch<ModelProfile>(path(spaceId, "/model-profiles"), { method: "POST", body: { providerConnectionId: mimoProvider.providerConnectionId, purpose: "CHAT", modelName: "mimo-v2.5", capabilities: ["CHAT", "USAGE_REPORTING"], contextWindow: 32768, maxOutputTokens: 2048, embeddingDimension: null, usageReporting: "PROVIDER_REPORTED", status: "PUBLISHED" } });
  const mimoRoute = routesPage.items.find((item) => item.purpose === "CHAT" && item.egressClass === "CLOUD" && item.status === "ACTIVE" && item.candidates.some((candidate) => candidate.modelProfileId === mimoProfile.modelProfileId)) ?? await apiFetch<ModelRoute>(path(spaceId, "/model-routes"), { method: "POST", body: { purpose: "CHAT", egressClass: "CLOUD", failoverPolicy: "NONE", candidates: [{ modelProfileId: mimoProfile.modelProfileId, priority: 1, egressClass: "CLOUD" }], status: "ACTIVE" } });
  const approvedAt = new Date();
  await apiFetch(path(spaceId, "/space-bindings"), { method: "PUT", headers: { "If-Match": `"${binding.version}"` }, body: { version: binding.version, chatRouteId: mimoRoute.modelRouteId, embeddingRouteId: binding.embeddingRouteId, rerankRouteId: binding.rerankRouteId, promptVersionId: binding.promptVersionId, cloudEgressEnabled: true, cloudEgressAuthorization: { approvalId: crypto.randomUUID(), approvedBy: userId, approvedAt: approvedAt.toISOString(), expiresAt: new Date(approvedAt.getTime() + 24 * 60 * 60 * 1000).toISOString(), scope: "CHAT" } } });
}
