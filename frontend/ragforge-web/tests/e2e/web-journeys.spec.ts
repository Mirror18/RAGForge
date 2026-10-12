import { expect, test, type Page } from "@playwright/test";
import { resolve } from "node:path";
import { createSyntheticApi, SYNTHETIC, type SyntheticApi, type SyntheticRole } from "../fixtures/synthetic-api";

async function login(page: Page, api: SyntheticApi): Promise<void> {
  await api.install(page);
  await page.goto("/");
  await expect(page.getByLabel("邮箱")).toBeVisible();
  await page.getByLabel("邮箱").fill("synthetic@example.invalid");
  await page.getByLabel("密码").fill("synthetic-pass-1234");
  await page.getByRole("button", { name: "进入工作台" }).click();
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
    await expect(page.getByText("只读成员").first()).toBeVisible();
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

  test("[J9 历史恢复] groups conversations in a table and opens the latest answer", async ({ page }) => {
    const api = create();
    await login(page, api);
    await page.goto(`/?view=answer&spaceId=${SYNTHETIC.spaceId}&conversationId=${SYNTHETIC.conversationId}&runId=${SYNTHETIC.runId}`);
    await expect(page.getByRole("table", { name: "问答历史表格" })).toBeVisible();
    await expect(page.locator(".answer-result")).toContainText("synthetic answer");
    await page.getByRole("button", { name: /Synthetic conversation/ }).click();
    await expect(page.locator(".history-detail")).toContainText("本会话的运行记录");
    await expect(page.locator(".history-detail")).toContainText("已完成");
    await expect(page.locator(".answer-result")).toContainText("synthetic answer");
  });
});

test("[P7C-15 列表分页] business flow and answer history show ten rows per page", async ({ page }) => {
  const api = createSyntheticApi({ largeCollections: true });
  await login(page, api);
  await apiJson(page, `/api/v1/spaces/${SYNTHETIC.spaceId}/sources/uploads`, "POST", "synthetic-pagination");

  await page.goto(`/?view=flow&spaceId=${SYNTHETIC.spaceId}`);
  const jobsTable = page.getByRole("table", { name: "导入任务列表" });
  await expect(jobsTable).toBeVisible();
  await expect(jobsTable.locator("tbody tr")).toHaveCount(10);
  await expect(page.locator('[data-pagination="ingestion-jobs"]')).toContainText("第 1 / 2 页 · 共 17 条");
  await page.locator('[data-pagination="ingestion-jobs"]').getByRole("button", { name: "下一页" }).click();
  await expect(jobsTable.locator("tbody tr")).toHaveCount(7);
  await expect(page.locator('[data-pagination="ingestion-jobs"]')).toContainText("第 2 / 2 页 · 共 17 条");

  await page.goto(`/?view=answer&spaceId=${SYNTHETIC.spaceId}`);
  const historyTable = page.getByRole("table", { name: "问答历史表格" });
  await expect(historyTable).toBeVisible();
  await expect(historyTable.locator('tbody > tr:not(.history-detail-row)')).toHaveCount(10);
  await expect(page.locator('[data-pagination="conversation-history"]')).toContainText("会话：第 1 / 3 页 · 共 23 条");
  await historyTable.locator('tbody > tr:not(.history-detail-row)').first().locator(".history-select").click();
  await expect(page.locator('[data-pagination="conversation-runs"]')).toContainText("运行记录：第 1 / 3 页 · 共 23 条");
  await expect(page.locator('[data-pagination="conversation-runs"] button[aria-label="下一页"]')).toBeEnabled();
  await page.locator('[data-pagination="conversation-runs"] button[aria-label="下一页"]').click();
  await expect(page.locator('[data-pagination="conversation-runs"]')).toContainText("运行记录：第 2 / 3 页 · 共 23 条");
  await page.locator('[data-pagination="conversation-history"] button[aria-label="下一页"]').click();
  await expect(historyTable.locator('tbody > tr:not(.history-detail-row)')).toHaveCount(10);
  await expect(historyTable).toContainText("Synthetic conversation 11");
  await expect(page.locator('[data-pagination="conversation-history"]')).toContainText("会话：第 2 / 3 页 · 共 23 条");
});

test("[P7C-16 索引列表分页] index lifecycle shows ten versions per page", async ({ page }) => {
  const api = createSyntheticApi({ largeCollections: true });
  await login(page, api);
  await apiJson(page, `/api/v1/spaces/${SYNTHETIC.spaceId}/sources/uploads`, "POST", "synthetic-index-pagination");
  await page.goto(`/?view=flow&spaceId=${SYNTHETIC.spaceId}`);

  const indexTable = page.getByRole("table", { name: "索引生命周期列表" });
  await expect(indexTable).toBeVisible();
  await expect(indexTable.locator("tbody tr")).toHaveCount(10);
  await expect(page.locator('[data-pagination="index-lifecycle"]')).toContainText("第 1 / 2 页 · 共 16 条");
  await expect(indexTable.getByRole("button", { name: "发布为当前版本" })).toHaveCount(10);
  await page.locator('[data-pagination="index-lifecycle"] button[aria-label="下一页"]').click();
  await expect(indexTable.locator("tbody tr")).toHaveCount(6);
  await expect(page.locator('[data-pagination="index-lifecycle"]')).toContainText("第 2 / 2 页 · 共 16 条");
});

test("[P7Q-06 URL + 分页] restores route state and traverses all large synthetic collections", async ({ page }) => {
  const api = createSyntheticApi({ largeCollections: true });
  await login(page, api);
  await page.locator("#chunk-studio-tab").click();
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
  expect(await collect(`/api/v1/spaces/${SYNTHETIC.spaceId}/jobs`)).toBe(17);
  expect(await collect(`/api/v1/spaces/${SYNTHETIC.spaceId}/indexes`)).toBe(16);
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

test("[RF-6 登录到带引用回答] walks login to a cited answer on desktop and narrow screens", async ({ page }) => {
  const api = create();
  const asset = (name: string): string => resolve(process.cwd(), "../../tests/evidence/business-demo", name);
  await login(page, api);
  await page.locator("#answer-tab").click();
  await expect(page.locator("#answer-heading")).toBeVisible();
  await expect(page.getByRole("button", { name: "开始回答" })).toBeEnabled();
  await page.getByLabel("问题").fill(SYNTHETIC.question);
  await page.getByRole("button", { name: "开始回答" }).click();
  await expect(page.locator(".answer-result")).toContainText("synthetic answer");
  await expect(page.locator(".citation-section")).toBeVisible();
  await expect(page.locator(".citation-card")).toContainText(SYNTHETIC.evidenceId);
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.screenshot({ path: asset("06-桌面-带引用问答.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator("#answer-tab")).toBeVisible();
  await expect(page.locator(".answer-result")).toContainText("synthetic answer");
  await page.screenshot({ path: asset("07-窄屏-带引用问答.png"), fullPage: true });
});

test("[P7C-14 演示截图] captures the complete synthetic business loop", async ({ page }) => {
  const api = createSyntheticApi({ largeCollections: true });
  const asset = (name: string): string => resolve(process.cwd(), "../../tests/evidence/business-demo", name);
  await login(page, api);
  await page.goto(`/?view=flow&spaceId=${SYNTHETIC.spaceId}`);
  await expect(page.locator("#business-flow-heading")).toBeVisible();
  await page.screenshot({ path: asset("01-空间与向导.png"), fullPage: true });

  await apiJson(page, `/api/v1/spaces/${SYNTHETIC.spaceId}/sources/uploads`, "POST", "synthetic-demo");
  await page.goto(`/?view=flow&spaceId=${SYNTHETIC.spaceId}`);
  await expect(page.getByRole("table", { name: "导入任务列表" })).toBeVisible();
  await page.screenshot({ path: asset("02-资料导入.png"), fullPage: true });

  await apiJson(page, `/api/v1/spaces/${SYNTHETIC.spaceId}/indexes/${SYNTHETIC.indexId}/publish`, "POST");
  await page.goto(`/?view=flow&spaceId=${SYNTHETIC.spaceId}`);
  await expect(page.locator(".index-lifecycle")).toBeVisible();
  await page.screenshot({ path: asset("03-索引发布.png"), fullPage: true });

  await page.goto(`/?view=answer&spaceId=${SYNTHETIC.spaceId}&conversationId=${SYNTHETIC.conversationId}&runId=${SYNTHETIC.runId}`);
  await expect(page.locator(".answer-result")).toContainText("synthetic answer");
  await expect(page.locator(".citation-section")).toBeVisible();
  await page.screenshot({ path: asset("04-带引用问答.png"), fullPage: true });
  await page.locator('.history-table tbody > tr:not(.history-detail-row)').first().locator(".history-select").click();
  await expect(page.locator(".history-detail")).toContainText("本会话的运行记录");
  await page.screenshot({ path: asset("05-历史会话.png"), fullPage: true });
});
