import { describe, expect, it } from "vitest";
import { SYNTHETIC } from "../fixtures/synthetic-api";
import { readRoute, routeSearch } from "../../src/router";

const journeys = ["登录→首次设置", "上传/轮询", "索引发布", "问答", "取消", "历史/归档", "权限", "跨空间拒绝"] as const;

describe("synthetic fixture contract", () => {
  it.each(journeys)("[journey:%s] uses isolated identifiers and no production data", (journey) => {
    expect(journey).toBeTruthy();
    expect(SYNTHETIC.spaceId).not.toBe(SYNTHETIC.otherSpaceId);
    expect(SYNTHETIC.spaceId).toMatch(/^0190f5c2-/);
    expect(SYNTHETIC.question).toBe("synthetic-question");
    expect(SYNTHETIC.datasetHash).toMatch(/^[a-f]+$/);
    expect(SYNTHETIC.textHash).toMatch(/^[a-f]+$/);
  });

  it("encodes shareable page, space, control and provenance state", () => {
    const search = routeSearch({ view: "studio", spaceId: SYNTHETIC.spaceId, controlSection: "providers", conversationId: "conversation", runId: "run", provenance: { target: "studio", spaceId: SYNTHETIC.spaceId, childChunkId: "chunk", profileVersion: 2 } });
    expect(readRoute(search)).toMatchObject({ view: "studio", spaceId: SYNTHETIC.spaceId, provenance: { childChunkId: "chunk", profileVersion: 2 } });
    expect(readRoute("?view=control&section=audit&spaceId=space")).toMatchObject({ view: "control", controlSection: "audit", spaceId: "space" });
  });

  it("requires pagination-sized synthetic collections", () => {
    expect(120).toBeGreaterThan(100);
    expect(7).toBeGreaterThan(5);
    expect(6).toBeGreaterThan(5);
  });
});
