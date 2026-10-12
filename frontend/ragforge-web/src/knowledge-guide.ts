import { ANSWER_READINESS_LABELS, type AnswerReadinessCode, type AnswerReadinessGap } from "./api";

export type KnowledgeNextKind = "models" | "import" | "publish" | "ready" | "other";

export function publishedBindingChoiceNeeded(publishedRouteCount: number, publishedPromptCount: number): boolean {
  return publishedRouteCount > 1 || publishedPromptCount > 1;
}

export function knowledgeNextKind(code: AnswerReadinessCode | undefined): KnowledgeNextKind {
  if (!code) return "ready";
  if (code === "no_authorized_chat_route" || code === "no_published_prompt" || code === "cloud_egress_required" || code === "no_active_profile") {
    return "models";
  }
  if (code === "no_documents") return "import";
  if (code === "no_active_index") return "publish";
  return "other";
}

export function knowledgeNextStep(gaps: AnswerReadinessGap[]): { label: string; path: string; kind: KnowledgeNextKind } {
  const gap = gaps[0];
  if (!gap) {
    return { label: "输入问题，开始一次带引用问答", path: "?view=answer", kind: "ready" };
  }
  return { label: ANSWER_READINESS_LABELS[gap.code], path: gap.path, kind: knowledgeNextKind(gap.code) };
}
