import { describe, expect, it } from "vitest";
import {
  isUnsafeRecoveryText,
  recoveryFromAbstention,
  recoveryFromAnswerError,
  recoveryFromProblemCode,
  recoveryFromUnknown,
} from "../../src/answer-recovery";

describe("answer recovery mapping", () => {
  it("maps server binding failures to existing product routes", () => {
    expect(recoveryFromProblemCode("no_active_index")).toMatchObject({ path: "?view=flow", nextStep: "去资料页发布索引" });
    expect(recoveryFromProblemCode("no_authorized_chat_route")).toMatchObject({
      path: "?view=control&section=providers",
      nextStep: "去模型页完成云端路线配置",
    });
    expect(recoveryFromProblemCode("cloud_egress_required")).toMatchObject({
      path: "?view=control&section=providers",
      nextStep: "去云端模型页启用本空间问答",
    });
    expect(recoveryFromProblemCode("NO_ACTIVE_INDEX").path).toBe("?view=flow");
  });

  it("maps insufficient evidence and invalid citations to Chinese next steps", () => {
    expect(recoveryFromAbstention("NO_EVIDENCE").path).toBe("?view=flow");
    expect(recoveryFromAnswerError("EVIDENCE_INVALID").path).toBeNull();
    expect(recoveryFromAnswerError("PROVIDER_UNAVAILABLE").path).toBeNull();
  });

  it("uses a generic retry for unknown errors without leaking internals", () => {
    const unknown = recoveryFromUnknown();
    const mystery = recoveryFromProblemCode("SOME_VENDOR_DUMP");
    expect(unknown.path).toBeNull();
    expect(mystery.title).toBe(unknown.title);
    expect(isUnsafeRecoveryText(unknown.nextStep)).toBe(false);
    expect(isUnsafeRecoveryText(mystery.nextStep)).toBe(false);
    expect(mystery.nextStep).not.toContain("SOME_VENDOR_DUMP");
  });
});
