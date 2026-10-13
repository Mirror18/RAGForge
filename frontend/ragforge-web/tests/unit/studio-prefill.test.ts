import { describe, expect, it } from "vitest";
import {
  prefillAfterSpaceChange,
  resolvePlaygroundPrefill,
  resolveStudioPrefill,
} from "../../src/studio-prefill";

describe("studio prefill", () => {
  it("playground is ready when active index and profile exist", () => {
    const guide = resolvePlaygroundPrefill({
      spaceId: "space-a",
      defaults: {
        indexVersionId: "idx-1",
        profileId: "prof-1",
        profileVersion: 2,
      },
      gaps: [],
    });
    expect(guide.kind).toBe("ready");
    expect(guide.indexVersionId).toBe("idx-1");
    expect(guide.profileId).toBe("prof-1");
    expect(guide.nextStep).toBeNull();
  });

  it("playground points to publish when active versions are missing", () => {
    const guide = resolvePlaygroundPrefill({
      spaceId: "space-a",
      defaults: null,
      gaps: [{ code: "no_active_index", path: "?view=flow" }],
    });
    expect(guide.kind).toBe("needs_setup");
    expect(guide.nextStep).toEqual({ label: "发布当前索引", path: "?view=flow" });
  });

  it("studio empty state follows readiness when there is no citation", () => {
    const missing = resolveStudioPrefill({
      spaceId: "space-a",
      hasActiveIndex: false,
      gaps: [{ code: "no_documents", path: "?view=flow" }],
      citationForSpace: false,
    });
    expect(missing.kind).toBe("needs_setup");
    expect(missing.nextStep?.path).toBe("?view=flow");

    const ready = resolveStudioPrefill({
      spaceId: "space-a",
      hasActiveIndex: true,
      gaps: [],
      citationForSpace: false,
    });
    expect(ready.kind).toBe("ready");
    expect(ready.nextStep).toBeNull();
  });

  it("space switch clears foreign citation and requests reload", () => {
    expect(prefillAfterSpaceChange("space-a", "space-b")).toEqual({
      clearCitation: true,
      reload: true,
    });
    expect(prefillAfterSpaceChange("space-a", "space-a")).toEqual({
      clearCitation: false,
      reload: false,
    });
    expect(prefillAfterSpaceChange("space-a", "")).toEqual({
      clearCitation: true,
      reload: false,
    });
  });
});
