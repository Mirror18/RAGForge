export function spaceBindingWrite(current: { version: number } | null): { version: number; ifMatch: string } {
  if (!current) {
    return { version: 1, ifMatch: "\"0\"" };
  }
  return { version: current.version, ifMatch: `"${current.version}"` };
}

export function cloudEgressPayload(
  approvalId: string,
  approvedBy: string,
  nowMs = Date.now(),
  ttlMs = 30 * 24 * 60 * 60 * 1000,
): {
  approvalId: string;
  approvedBy: string;
  approvedAt: string;
  expiresAt: string;
  scope: "ALL";
} {
  return {
    approvalId,
    approvedBy,
    approvedAt: new Date(nowMs).toISOString(),
    expiresAt: new Date(nowMs + ttlMs).toISOString(),
    scope: "ALL",
  };
}
