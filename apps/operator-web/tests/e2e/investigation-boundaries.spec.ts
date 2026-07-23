import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

import { investigationRuns, runPath } from "./investigation-test-route";

for (const scenario of [
  {
    name: "created",
    runId: investigationRuns.created,
    heading: "Created",
    detail: "The run is accepted and has not started analysis.",
  },
  {
    name: "analyzing",
    runId: investigationRuns.analyzing,
    heading: "Analyzing",
    detail: "A bounded analysis round is in progress.",
  },
]) {
  test(`renders the ${scenario.name} investigation state`, async ({ page }) => {
    await page.goto(runPath(scenario.runId));

    await expect(page.getByRole("heading", { name: scenario.heading, exact: true })).toBeVisible();
    await expect(page.getByText(scenario.detail)).toBeVisible();
    await expect(page.getByRole("heading", { name: "No authoritative conclusion" })).toBeVisible();
  });
}

test("renders pending catalog intent without claiming tool completion", async ({ page }) => {
  await page.goto(runPath(investigationRuns.waiting));

  await expect(page.getByRole("heading", { name: "Waiting for evidence" })).toBeVisible();
  await expect(page.getByText("1 accepted read intent counted against budget.")).toBeVisible();
  await expect(page.getByText("metrics.query")).toBeVisible();
  await expect(page.getByText(
    "Reviewed catalog label; executable arguments remain server-side.",
  )).toBeVisible();
  await expect(page.getByText(/Model-authored rationale/iu)).toHaveCount(0);
  await expect(page.getByText(/completed read-only tool call/iu)).toHaveCount(0);
  await expectAxeClean(page);
});

test("renders no-progress as a stopped state without a conclusion", async ({ page }) => {
  await page.goto(runPath(investigationRuns.noProgress));

  await expect(page.getByRole("status").getByText(
    "The model requested more evidence without a cataloged read intent.",
  )).toBeVisible();
  await expect(page.getByRole("heading", { name: "No authoritative conclusion" })).toBeVisible();
  await expectAxeClean(page);
});

for (const scenario of [
  {
    name: "expired session",
    runId: investigationRuns.unauthorized,
    heading: "Access denied",
  },
  {
    name: "forbidden scope",
    runId: investigationRuns.forbidden,
    heading: "Access denied",
  },
  {
    name: "missing investigation",
    runId: investigationRuns.missing,
    heading: "Investigation not found",
  },
]) {
  test(`fails closed for ${scenario.name}`, async ({ page }) => {
    await page.goto(runPath(scenario.runId));

    await expect(page.getByRole("heading", { name: scenario.heading })).toBeVisible();
    await expect(page.getByText("Session not asserted", { exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Cited conclusion" })).toHaveCount(0);
  });
}

test("rejects an invalid route identity before contacting the Platform API", async ({ page }) => {
  await page.goto(runPath("not-a-uuid"));

  await expect(page.getByRole("heading", { name: "Investigation not found" })).toBeVisible();
  await expect(page.getByText("Session not asserted", { exact: true })).toBeVisible();
  await expectAxeClean(page);
});

for (const scenario of [
  { name: "chunked overflow", runId: investigationRuns.chunked },
  { name: "invalid media type", runId: investigationRuns.invalidMedia },
  { name: "invalid JSON", runId: investigationRuns.invalidJson },
  { name: "invalid UTF-8", runId: investigationRuns.invalidUtf8 },
  { name: "missing browser-safe classification", runId: investigationRuns.unclassified },
  { name: "unknown catalog operation", runId: investigationRuns.unknownOperation },
]) {
  test(`rejects ${scenario.name} without partial rendering`, async ({ page }) => {
    await page.goto(runPath(scenario.runId));

    await expect(page.getByRole("heading", { name: "Projection verification failed" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Checkout latency regression" })).toHaveCount(0);
    await expect(page.getByText(/Support correlation/u)).toBeVisible();
  });
}

test("bounds an upstream timeout without retrying in the browser", async ({ page }) => {
  await page.goto(runPath(investigationRuns.timeout));

  await expect(page.getByRole("heading", { name: "Platform data unavailable" })).toBeVisible();
  await expect(page.getByText(
    "The last durable state remains unchanged. No retry or downstream action was attempted.",
  )).toBeVisible();
});

test("renders the route loading boundary while the bounded read is pending", async ({ page }) => {
  await page.setViewportSize({ width: 820, height: 900 });
  const navigation = page.goto(runPath(investigationRuns.slow));

  await expect(page.getByText("Loading the authorized investigation projection")).toBeVisible();
  await expect.poll(() => page.evaluate(
    () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
  )).toBe(true);
  await navigation;
  await expect(page.getByRole("heading", { name: "Created", exact: true })).toBeVisible();
});

async function expectAxeClean(page: Page): Promise<void> {
  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);
}
