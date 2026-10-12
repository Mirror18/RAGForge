import { describe, expect, it } from "vitest";
import { buildCreateAnswerBody } from "../../src/answer";

describe("create answer payload", () => {
  it("omits hashes and technical ids for a ready-space question", () => {
    const body = buildCreateAnswerBody({
      runId: "0190f5c2-7c1e-7ac1-8def-1234567890ab",
      message: "What is verified?",
      timeoutSeconds: 30,
      allowCloudEgress: true,
    });
    expect(body).toEqual({
      runId: "0190f5c2-7c1e-7ac1-8def-1234567890ab",
      query: "What is verified?",
      timeoutSeconds: 30,
      allowCloudEgress: true,
    });
    expect(body).not.toHaveProperty("datasetHash");
    expect(body).not.toHaveProperty("configHash");
    expect(body).not.toHaveProperty("promptVersionId");
  });

  it("includes expert overrides without hashes unless provided", () => {
    const body = buildCreateAnswerBody({
      runId: "0190f5c2-7c1e-7ac1-8def-1234567890ab",
      message: "What is verified?",
      timeoutSeconds: 30,
      promptVersionId: "0190f5c2-7c1e-7ad2-8def-1234567890ab",
      routeVersionId: "0190f5c2-7c1e-7ad3-8def-1234567890ab",
      profileVersionId: "0190f5c2-7c1e-7ad4-8def-1234567890ab",
      model: "cloud-chat",
    });
    expect(body.promptVersionId).toBe("0190f5c2-7c1e-7ad2-8def-1234567890ab");
    expect(body.modelRouteVersionId).toBe("0190f5c2-7c1e-7ad3-8def-1234567890ab");
    expect(body).not.toHaveProperty("datasetHash");
  });
});
