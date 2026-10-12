import { describe, expect, it } from "vitest";
import { cloudEgressPayload, spaceBindingWrite } from "../../src/space-enable";

describe("space enable helpers", () => {
  it("starts the first binding at version 1 with If-Match 0", () => {
    expect(spaceBindingWrite(null)).toEqual({ version: 1, ifMatch: "\"0\"" });
  });

  it("updates an existing binding with the current version", () => {
    expect(spaceBindingWrite({ version: 3 })).toEqual({ version: 3, ifMatch: "\"3\"" });
  });

  it("covers chat, embedding and rerank with a dated ALL-scope authorization", () => {
    const payload = cloudEgressPayload("approval", "admin", Date.parse("2026-10-13T00:00:00.000Z"), 86_400_000);
    expect(payload).toMatchObject({
      approvalId: "approval",
      approvedBy: "admin",
      scope: "ALL",
      approvedAt: "2026-10-13T00:00:00.000Z",
      expiresAt: "2026-10-14T00:00:00.000Z",
    });
  });
});
