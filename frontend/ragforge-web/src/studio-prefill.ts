import { knowledgeNextStep } from "./knowledge-guide";
import type { AnswerReadinessGap, PlaygroundDefaults } from "./api";

export type PrefillKind = "no_space" | "ready" | "needs_setup";

export interface PrefillGuide {
  kind: PrefillKind;
  indexVersionId: string | null;
  profileId: string | null;
  profileVersion: number | null;
  nextStep: { label: string; path: string } | null;
  hint: string;
}

/** Resolve试检索 prefill from active defaults + readiness gaps. */
export function resolvePlaygroundPrefill(input: {
  spaceId: string;
  defaults: PlaygroundDefaults | null;
  gaps: AnswerReadinessGap[];
}): PrefillGuide {
  if (!input.spaceId.trim()) {
    return {
      kind: "no_space",
      indexVersionId: null,
      profileId: null,
      profileVersion: null,
      nextStep: null,
      hint: "请先选择空间。",
    };
  }
  if (input.defaults?.indexVersionId && input.defaults.profileId) {
    return {
      kind: "ready",
      indexVersionId: input.defaults.indexVersionId,
      profileId: input.defaults.profileId,
      profileVersion: input.defaults.profileVersion,
      nextStep: null,
      hint: "将使用本空间当前索引和检索档。",
    };
  }
  const next = knowledgeNextStep(input.gaps);
  return {
    kind: "needs_setup",
    indexVersionId: null,
    profileId: null,
    profileVersion: null,
    nextStep: { label: next.label, path: next.path },
    hint: "当前空间还没有可试检索的索引或检索档。",
  };
}

/** Resolve看来源 empty-state guidance from active index + readiness. */
export function resolveStudioPrefill(input: {
  spaceId: string;
  hasActiveIndex: boolean;
  gaps: AnswerReadinessGap[];
  citationForSpace: boolean;
}): PrefillGuide {
  if (!input.spaceId.trim()) {
    return {
      kind: "no_space",
      indexVersionId: null,
      profileId: null,
      profileVersion: null,
      nextStep: null,
      hint: "请先选择空间。",
    };
  }
  if (input.citationForSpace) {
    return {
      kind: "ready",
      indexVersionId: null,
      profileId: null,
      profileVersion: null,
      nextStep: null,
      hint: "已从引用打开，正在核对来源。",
    };
  }
  if (input.hasActiveIndex) {
    return {
      kind: "ready",
      indexVersionId: null,
      profileId: null,
      profileVersion: null,
      nextStep: null,
      hint: "当前空间已有发布索引。请从问答引用打开一条来源，不要手填内部编号。",
    };
  }
  const next = knowledgeNextStep(input.gaps);
  return {
    kind: "needs_setup",
    indexVersionId: null,
    profileId: null,
    profileVersion: null,
    nextStep: { label: next.label, path: next.path },
    hint: "当前空间还没有发布索引，看来源前请先完成资料导入与发布。",
  };
}

/** When the space changes, drop foreign citation context and previous defaults. */
export function prefillAfterSpaceChange(previousSpaceId: string, nextSpaceId: string): {
  clearCitation: boolean;
  reload: boolean;
} {
  if (!nextSpaceId.trim()) {
    return { clearCitation: true, reload: false };
  }
  if (previousSpaceId === nextSpaceId) {
    return { clearCitation: false, reload: false };
  }
  return { clearCitation: true, reload: true };
}
