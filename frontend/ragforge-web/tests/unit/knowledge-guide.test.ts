import { describe, expect, it } from "vitest";
import { knowledgeNextStep, publishedBindingChoiceNeeded } from "../../src/knowledge-guide";

describe("knowledge guide", () => {
  it("does not require a binding picker for a single published set", () => {
    expect(publishedBindingChoiceNeeded(1, 1)).toBe(false);
    expect(publishedBindingChoiceNeeded(0, 1)).toBe(false);
  });

  it("requires a choice only when more than one published route or prompt exists", () => {
    expect(publishedBindingChoiceNeeded(2, 1)).toBe(true);
    expect(publishedBindingChoiceNeeded(1, 3)).toBe(true);
  });

  it("sends missing model, missing documents, and unpublished index to different next steps", () => {
    expect(knowledgeNextStep([{ code: "no_authorized_chat_route", path: "?view=control&section=providers" }])).toEqual({
      label: "配置已授权的云端回答路线",
      path: "?view=control&section=providers",
      kind: "models",
    });
    expect(knowledgeNextStep([{ code: "no_documents", path: "?view=flow" }]).kind).toBe("import");
    expect(knowledgeNextStep([{ code: "no_active_index", path: "?view=flow" }]).kind).toBe("publish");
  });
});
