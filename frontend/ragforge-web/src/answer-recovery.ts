import type { AnswerAbstentionReason, AnswerErrorCode } from "./answer";

export interface RecoveryAction {
  title: string;
  nextStep: string;
  path: string | null;
}

const RETRY: RecoveryAction = {
  title: "暂时无法完成这次回答",
  nextStep: "请稍后重试。不会切换到其他空间或未授权云端。",
  path: null,
};

const BY_PROBLEM: Record<string, RecoveryAction> = {
  no_active_index: { title: "当前空间还没有已发布索引", nextStep: "去资料页发布索引", path: "?view=flow" },
  no_documents: { title: "当前空间还没有资料", nextStep: "去资料页导入文档", path: "?view=flow" },
  no_authorized_chat_route: { title: "当前空间没有已授权的回答路线", nextStep: "去模型页完成云端路线配置", path: "?view=control&section=providers" },
  no_published_prompt: { title: "当前空间没有已发布的回答规则", nextStep: "去模型页发布回答规则", path: "?view=control&section=prompts" },
  no_active_profile: { title: "当前空间没有活跃检索档", nextStep: "先发布索引，再在试检索中确认", path: "?view=playground" },
  cloud_egress_required: { title: "当前空间尚未启用云端问答", nextStep: "去云端模型页启用本空间问答", path: "?view=control&section=providers" },
};

const BY_ANSWER_ERROR: Record<AnswerErrorCode, RecoveryAction> = {
  PROVIDER_UNAVAILABLE: { title: "回答服务暂不可用", nextStep: "请稍后重试，客户端不会静默切换云端", path: null },
  TIMEOUT: { title: "模型调用超时", nextStep: "请稍后重试", path: null },
  SPACE_EGRESS_DENIED: { title: "空间出境策略拒绝本次调用", nextStep: "去云端模型页检查出境授权", path: "?view=control&section=providers" },
  EVIDENCE_INVALID: { title: "引用未通过校验", nextStep: "请重新提问，不要使用未校验的引用", path: null },
  TOOL_FAILURE: { title: "只读工具未能提供可靠结果", nextStep: "请稍后重试", path: null },
  CANCELLED: { title: "回答已取消", nextStep: "如需继续请重新提问", path: null },
  INTERNAL_ERROR: RETRY,
};

const BY_ABSTENTION: Record<AnswerAbstentionReason, RecoveryAction> = {
  NO_EVIDENCE: { title: "知识库没有相关证据", nextStep: "去资料页补充或发布索引后再问", path: "?view=flow" },
  LOW_CONFIDENCE: { title: "证据相关性不足", nextStep: "去资料页补充资料或换一种问法", path: "?view=flow" },
  EVIDENCE_CONFLICT: { title: "当前证据相互冲突", nextStep: "去资料页核对来源后再问", path: "?view=flow" },
  POLICY_BLOCKED: { title: "当前问题被空间策略拒绝", nextStep: "请调整问题后重试", path: null },
  SPACE_ACCESS_DENIED: { title: "当前空间访问策略不允许回答", nextStep: "请确认空间成员权限后重试", path: null },
  TOOL_UNAUTHORIZED: { title: "所需只读工具未获授权", nextStep: "请稍后重试或联系空间管理员", path: null },
  TOOL_FAILURE: { title: "只读工具未能提供可靠结果", nextStep: "请稍后重试", path: null },
  PROVIDER_UNAVAILABLE: BY_ANSWER_ERROR.PROVIDER_UNAVAILABLE,
  CANCELLED: BY_ANSWER_ERROR.CANCELLED,
};

export function recoveryFromProblemCode(code: string | null | undefined): RecoveryAction {
  if (!code) return RETRY;
  return BY_PROBLEM[code.trim().toLowerCase()] ?? RETRY;
}

export function recoveryFromAnswerError(code: AnswerErrorCode): RecoveryAction {
  return BY_ANSWER_ERROR[code] ?? RETRY;
}

export function recoveryFromAbstention(reason: AnswerAbstentionReason): RecoveryAction {
  return BY_ABSTENTION[reason] ?? RETRY;
}

export function recoveryFromUnknown(): RecoveryAction {
  return RETRY;
}

export function isUnsafeRecoveryText(text: string): boolean {
  return /api[_-]?key|sk-|Bearer |prompt:|stack trace|exception/i.test(text);
}
