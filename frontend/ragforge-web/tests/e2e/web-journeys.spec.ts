import { expect, test, type Page } from "@playwright/test";
import { createSyntheticApi, SYNTHETIC, type SyntheticApi, type SyntheticRole } from "../fixtures/synthetic-api";

async function login(page: Page, api: SyntheticApi): Promise<void> {
  await api.install(page);
  await page.goto("/");
  await expect(page.getByLabel("邮箱")).toBeVisible();
  await page.getByLabel("邮箱").fill("synthetic@example.invalid");
  await page.getByLabel("密码").fill("synthetic-pass-1234");
  await page.getByRole("button", { name: "登录工作台" }).click();
  await expect(page.locator(".status-chip.ready")).toContainText("API");
}

async function apiJson(page: Page, path: string, method = "GET", body?: string): Promise<{ status: number; body: any }> {
  return page.evaluate(async ({ path, method, body }) => {
    const response = await fetch(path, { method, body, credentials: "include", headers: body ? { "Content-Type": "application/json" } : undefined });
    return { status: response.status, body: await response.json() };
  }, { path, method, body });
}

function create(role: SyntheticRole = "SPACE_ADMIN", hasSpace = true): SyntheticApi {
  return createSyntheticApi({ role, hasSpace });
}

test.describe("P7Q-03 Web 8 journeys", () => {
  test("[J1 登录→首次设置] establishes session and creates the first isolated space", async ({ page }) => {
    const api = create("SPACE_ADMIN", false);
    await login(page, api);
    await expect(page.getByText("尚未创建空间")).toBeVisible();
    const created = await apiJson(page, "/api/v1/spaces", "POST", JSON.stringify({ name: "Synthetic Space", description: "isolated fixture" }));
    expect(created.status).toBe(201);
    expect(created.body.spaceId).toBe(SYNTHETIC.spaceId);
    await page.reload();
    await expect(page.locator("#space-select")).toHaveValue(SYNTHETIC.spaceId);
    await expect(page.locator(".context-id")).toContainText(SYNTHETIC.spaceId);
  });

  test("[J2 上传/轮询] uploads synthetic content and reaches a terminal polling state", async ({ page }) => {
    const api = create();
    await login(page, api);
    const submitted = await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.spaceId + "/sources/uploads", "POST", "synthetic-fixture");
    expect(submitted.status).toBe(201);
    const polled = await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.spaceId + "/ingestion-jobs/" + SYNTHETIC.jobId);
    expect(polled.body.job.spaceId).toBe(SYNTHETIC.spaceId);
    expect(polled.body.job.status).toBe("SUCCEEDED");
    expect(api.requests.some((request) => request.path === "/api/v1/spaces/" + SYNTHETIC.spaceId + "/sources/uploads")).toBe(true);
  });

  test("[J3 索引发布] publishes only the ready candidate for the current space", async ({ page }) => {
    const api = create();
    await login(page, api);
    await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.spaceId + "/sources/uploads", "POST");
    const ready = await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.spaceId + "/indexes");
    expect(ready.body.items[0].state).toBe("READY");
    const published = await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.spaceId + "/indexes/" + SYNTHETIC.indexId + "/publish", "POST");
    expect(published.body.activeIndexVersionId).toBe(SYNTHETIC.indexId);
    const active = await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.spaceId + "/indexes/active");
    expect(active.body.pointer.activeIndexVersionId).toBe(SYNTHETIC.indexId);
    expect(api.requests.at(-1)?.path).toBe("/api/v1/spaces/" + SYNTHETIC.spaceId + "/indexes/active");
  });

  test("[J4 问答] retains structured citation and provenance bound to space_id", async ({ page }) => {
    const api = create();
    await login(page, api);
    const conversation = await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.spaceId + "/conversations", "POST", "{}");
    const run = await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.spaceId + "/conversations/" + SYNTHETIC.conversationId + "/runs", "POST", JSON.stringify({ message: SYNTHETIC.question }));
    const answer = await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.spaceId + "/answers/" + SYNTHETIC.runId);
    expect(conversation.body.spaceId).toBe(SYNTHETIC.spaceId);
    expect(run.body.spaceId).toBe(SYNTHETIC.spaceId);
    expect(answer.body.spaceId).toBe(SYNTHETIC.spaceId);
    expect(answer.body.citations[0]).toMatchObject({ spaceId: SYNTHETIC.spaceId, runId: SYNTHETIC.runId, citationAllowed: true, contentRef: expect.any(String), textHash: SYNTHETIC.textHash });
    expect(answer.body.provenance).toMatchObject({ spaceId: SYNTHETIC.spaceId, runId: SYNTHETIC.runId, evidenceBundleId: expect.any(String), indexVersionId: SYNTHETIC.indexId, datasetHash: SYNTHETIC.datasetHash });
  });

  test("[J5 取消] confirms idempotent cancellation and restores terminal state", async ({ page }) => {
    const api = create();
    await login(page, api);
    const cancelled = await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.spaceId + "/answers/" + SYNTHETIC.runId + "/cancel", "POST", JSON.stringify({ reason: "synthetic cancellation" }));
    expect(cancelled.status).toBe(202);
    expect(cancelled.body).toMatchObject({ spaceId: SYNTHETIC.spaceId, runId: SYNTHETIC.runId, status: "CANCELLED" });
    const projection = await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.spaceId + "/answers/" + SYNTHETIC.runId);
    expect(projection.body.status).toBe("CANCELLED");
    expect(api.snapshot().cancelled).toBe(true);
  });

  test("[J6 历史/归档] archives a conversation and restores it through includeArchived", async ({ page }) => {
    const api = create();
    await login(page, api);
    const archived = await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.spaceId + "/conversations/" + SYNTHETIC.conversationId + "/archive", "POST");
    expect(archived.body.status).toBe("ARCHIVED");
    const hidden = await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.spaceId + "/conversations");
    expect(hidden.body.items).toHaveLength(0);
    const restored = await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.spaceId + "/conversations?includeArchived=true");
    expect(restored.body.items[0]).toMatchObject({ id: SYNTHETIC.conversationId, status: "ARCHIVED" });
  });

  test("[J7 权限] rejects viewer mutation with structured permission error", async ({ page }) => {
    const api = create("VIEWER");
    await login(page, api);
    await expect(page.getByText("当前角色只能查看来源；写操作由服务端权限最终裁决。")).toBeVisible();
    const denied = await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.spaceId + "/sources/git", "POST", JSON.stringify({ displayName: "synthetic", remote: "https://synthetic.invalid/repo" }));
    expect(denied.status).toBe(403);
    expect(denied.body).toMatchObject({ status: 403, code: "SPACE_ACCESS_DENIED", instance: "/api/v1/spaces/" + SYNTHETIC.spaceId + "/sources/git" });
  });

  test("[J8 跨空间拒绝] never returns content from another space", async ({ page }) => {
    const api = create();
    await login(page, api);
    const denied = await apiJson(page, "/api/v1/spaces/" + SYNTHETIC.otherSpaceId + "/answers/" + SYNTHETIC.runId);
    expect(denied.status).toBe(403);
    expect(denied.body).toMatchObject({ status: 403, code: "SPACE_ACCESS_DENIED", instance: "/api/v1/spaces/" + SYNTHETIC.otherSpaceId + "/answers/" + SYNTHETIC.runId });
    expect(api.requests.at(-1)?.path).toContain(SYNTHETIC.otherSpaceId);
  });
});

test("[P7Q-06 URL + 分页] restores route state and traverses all large synthetic collections", async ({ page }) => {
  const api = createSyntheticApi({ largeCollections: true });
  await login(page, api);
  await page.getByRole("button", { name: "Chunk Studio" }).click();
  await expect(page).toHaveURL(/view=studio/);
  await page.reload();
  await expect(page.locator("#chunk-studio-heading")).toBeVisible();

  const collect = async (path: string): Promise<number> => page.evaluate(async ({ path }) => {
    let cursor: string | null = null;
    let count = 0;
    do {
      const url = new URL(path, window.location.origin);
      url.searchParams.set("limit", "20");
      if (cursor) url.searchParams.set("cursor", cursor);
      const response = await fetch(url, { credentials: "include" });
      const body = await response.json() as { items: unknown[]; nextCursor: string | null };
      count += body.items.length;
      cursor = body.nextCursor;
    } while (cursor);
    return count;
  }, { path });
  await page.evaluate(async (spaceId) => {
    await fetch(`/api/v1/spaces/${spaceId}/sources/uploads`, { method: "POST", credentials: "include", body: "synthetic" });
  }, SYNTHETIC.spaceId);
  expect(await collect(`/api/v1/spaces/${SYNTHETIC.spaceId}/sources`)).toBe(120);
  expect(await collect(`/api/v1/spaces/${SYNTHETIC.spaceId}/jobs`)).toBe(7);
  expect(await collect(`/api/v1/spaces/${SYNTHETIC.spaceId}/indexes`)).toBe(6);
  expect(api.requests.filter((request) => request.path.endsWith("/sources")).length).toBeGreaterThan(1);
});

test("[P7Q-06 刷新恢复] restores five shareable workbench pages", async ({ page }) => {
  const api = create();
  await login(page, api);
  const pages = [
    ["flow", "#business-flow-heading"],
    ["answer", "#answer-heading"],
    ["studio", "#chunk-studio-heading"],
    ["playground", "#retrieval-playground-heading"],
    ["control", "#control-center-heading"],
    ["profile", "#personal-space-heading"],
  ] as const;
  for (const [view, heading] of pages) {
    await page.goto(`/?view=${view}&spaceId=${SYNTHETIC.spaceId}`);
    await page.reload();
    await expect(page.locator(heading)).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`view=${view}`));
  }
});
