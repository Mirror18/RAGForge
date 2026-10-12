const labels: Record<string, string> = {
  ACTIVE: "启用中",
  ARCHIVED: "已归档",
  BUILDING: "构建中",
  CANCELLED: "已取消",
  DRAFT: "草稿",
  FAILED: "失败",
  DISABLED: "已停用",
  DLQ: "死信",
  DEAD_LETTER: "死信",
  IN_PROGRESS: "处理中",
  NEEDS_REVIEW: "待复核",
  QUEUED: "排队中",
  PUBLISHED: "已发布",
  READY: "待发布",
  RETIRED: "已退役",
  RETRY_SCHEDULED: "等待重试",
  REQUESTED: "已提交",
  RUNNING: "运行中",
  SUCCEEDED: "已完成",
  VALIDATING: "验证中",
  USER: "普通用户",
  VIEWER: "只读成员",
  EDITOR: "编辑者",
  SPACE_ADMIN: "空间管理员",
  PLATFORM_ADMIN: "平台管理员",
  CHAT: "回答",
  EMBEDDING: "向量化",
  RERANK: "重排",
  LOCAL: "本地",
  CLOUD: "云端",
  LOCAL_ONLY: "仅本地",
  NONE: "不自动切换",
  SAME_EGRESS_ONLY: "仅同出境等级",
  OLLAMA: "本地模型服务",
  MIMO: "MiMo 云端模型",
  OPENAI_COMPATIBLE: "兼容 OpenAI 的服务",
  AI_RUNTIME: "AI 运行时",
  GIT: "Git 仓库",
  HTTP: "网页",
};

export function labelOf(value: string | null | undefined, fallback?: string): string {
  if (!value) return fallback ?? "未设置";
  return labels[value] ?? fallback ?? value;
}

export function roleLabel(value: string | null | undefined): string {
  return labelOf(value, "未加载");
}

export function stateLabel(value: string | null | undefined): string {
  return labelOf(value, "未知状态");
}

export function providerLabel(value: string | null | undefined): string {
  return labelOf(value, "未知服务");
}

export function purposeLabel(value: string | null | undefined): string {
  return labelOf(value, "未知用途");
}

export function egressLabel(value: string | null | undefined): string {
  return labelOf(value, "未设置");
}
