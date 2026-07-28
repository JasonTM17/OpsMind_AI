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
  for (const scenario of [
    { width: 1440, columns: 3 },
    { width: 1024, columns: 2 },
    { width: 820, columns: 1 },
    { width: 768, columns: 1 },
    { width: 375, columns: 1 },
    { width: 320, columns: 1 },
  ]) {
    await page.setViewportSize({ width: scenario.width, height: 900 });
    const navigation = page.goto(runPath(investigationRuns.slow));

    await expect(page.getByText("Loading the authorized investigation projection")).toBeVisible();
    await expect(page.getByRole("complementary")).toHaveCount(0);
    const geometry = await page.evaluate(() => {
      const main = document.querySelector<HTMLElement>('main[aria-label="Loading investigation"]');
      const layout = main?.querySelectorAll<HTMLElement>(':scope > [aria-hidden="true"]')[1];
      if (layout === undefined) throw new Error("Missing loading workspace layout");
      const panels = Array.from(layout.children, (element) => {
        const bounds = element.getBoundingClientRect();
        return { x: bounds.x, y: bounds.y, width: bounds.width };
      });
      if (panels.length !== 3) throw new Error("Loading workspace must expose three panels");
      return { panels, overflow: document.documentElement.scrollWidth > window.innerWidth };
    });
    expect(geometry.overflow).toBe(false);
    const [context, evidence, conclusion] = geometry.panels;

    if (scenario.columns === 3) {
      expect(context.y).toBeCloseTo(evidence.y, 0);
      expect(evidence.y).toBeCloseTo(conclusion.y, 0);
      expect(context.x).toBeLessThan(evidence.x);
      expect(evidence.x).toBeLessThan(conclusion.x);
    } else if (scenario.columns === 2) {
      expect(context.y).toBeCloseTo(evidence.y, 0);
      expect(context.x).toBeLessThan(evidence.x);
      expect(conclusion.y).toBeGreaterThan(evidence.y);
      expect(conclusion.x).toBeCloseTo(context.x, 0);
    } else {
      expect(context.y).toBeLessThan(evidence.y);
      expect(evidence.y).toBeLessThan(conclusion.y);
      expect(context.x).toBeCloseTo(evidence.x, 0);
      expect(evidence.x).toBeCloseTo(conclusion.x, 0);
      expect(context.width).toBeCloseTo(evidence.width, 0);
      expect(evidence.width).toBeCloseTo(conclusion.width, 0);
    }

    await navigation;
    await expect(page.getByRole("heading", { name: "Created", exact: true })).toBeVisible();
  }
});

async function expectAxeClean(page: Page): Promise<void> {
  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);
}
