import type { ProvenanceContext } from "./api";

export type View = "home" | "flow" | "studio" | "playground" | "answer" | "control" | "profile";
export type ControlSection = "spaces" | "health" | "cost" | "feedback" | "audit" | "providers" | "models" | "prompts" | "runs";
const views = new Set<View>(["home", "flow", "studio", "playground", "answer", "control", "profile"]);
const sections = new Set<ControlSection>(["spaces", "health", "cost", "feedback", "audit", "providers", "models", "prompts", "runs"]);
const contextKeys = ["childChunkId", "documentRevisionId", "contentRef", "textHash", "indexVersionId", "profileId"] as const;

export interface RouteState {
  view: View;
  spaceId: string;
  controlSection: ControlSection;
  conversationId: string;
  runId: string;
  provenance: ProvenanceContext | null;
}

export function readRoute(search = typeof window === "undefined" ? "" : window.location.search): RouteState {
  const params = new URLSearchParams(search);
  const rawView = params.get("view") ?? params.get("tool") ?? "flow";
  const view = views.has(rawView as View) ? rawView as View : "flow";
  const rawSection = params.get("section") ?? "providers";
  const controlSection = sections.has(rawSection as ControlSection) ? rawSection as ControlSection : "providers";
  const spaceId = params.get("spaceId") ?? params.get("space_id") ?? "";
  const profileVersion = Number(params.get("profileVersion"));
  const provenance = (view === "studio" || view === "playground") && spaceId ? {
    target: view,
    spaceId,
    ...Object.fromEntries(contextKeys.flatMap((key) => params.get(key) ? [[key, params.get(key) as string]] : [])),
    ...(Number.isInteger(profileVersion) && profileVersion > 0 ? { profileVersion } : {}),
  } as ProvenanceContext : null;
  return { view, spaceId, controlSection, conversationId: params.get("conversationId") ?? "", runId: params.get("runId") ?? "", provenance };
}

export function routeSearch(state: RouteState): string {
  const params = new URLSearchParams();
  params.set("view", state.view);
  if (state.spaceId) params.set("spaceId", state.spaceId);
  if (state.view === "control") params.set("section", state.controlSection);
  if (state.conversationId) params.set("conversationId", state.conversationId);
  if (state.runId) params.set("runId", state.runId);
  if (state.provenance?.target === state.view) {
    for (const key of contextKeys) if (state.provenance[key]) params.set(key, state.provenance[key] as string);
    if (state.provenance.profileVersion) params.set("profileVersion", String(state.provenance.profileVersion));
  }
  return `?${params.toString()}`;
}
