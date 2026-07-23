import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

import { investigationRuns, runPath } from "./investigation-test-route";

const {
  abstained: abstainedRunId,
  budget: budgetRunId,
  completed: completedRunId,
  crossScope: crossScopeRunId,
  failed: failedRunId,
  invalid: invalidRunId,
  oversized: oversizedRunId,
  unavailable: unavailableRunId,
  uncited: uncitedRunId,
} = investigationRuns;

test("renders a cited completed investigation without browser credentials", async ({ page }) => {
  await page.goto(runPath(completedRunId));

  await expect(page.getByRole("heading", { name: "Checkout latency regression" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Evidence spine" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Cited conclusion" })).toBeVisible();
  await expect(
    page.getByRole("complementary", { name: "Cited conclusion" })
      .getByRole("heading", { name: "Evidence-backed hypothesis 1" }),
  ).toBeVisible();
  await expect(page.getByText("Authorized read-only", { exact: true })).toBeVisible();
  expect(await page.getByText("E-01", { exact: true }).count()).toBeGreaterThanOrEqual(3);
  await expect(page.getByRole("heading", { name: "Other bounded hypotheses" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Evidence-backed hypothesis 2" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Counter-evidence recorded" })).toBeVisible();
  await expect(page.getByText("operator-browser-safe-v1", { exact: true })).toBeVisible();
  await expect(page.getByText("display-redaction-v1", { exact: true })).toBeVisible();
  expect(await page.getByRole("button", { name: "Copy run ID" }).evaluate((button) =>
    button.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44);

  const body = await page.locator("body").innerText();
  for (const prohibited of [
    "Authorization: Bearer",
    "reasoning_content",
    "chain_of_thought",
    "raw prompt",
    "PromQL",
    "Remediate",
  ]) {
    expect(body).not.toContain(prohibited);
  }
  const browserStorage = await page.evaluate(() => ({
    local: Object.entries(localStorage),
    session: Object.entries(sessionStorage),
    cookies: document.cookie,
  }));
  expect(browserStorage.local).toEqual([]);
  expect(browserStorage.cookies).toBe("");
  expect(JSON.stringify(browserStorage.session)).not.toMatch(
    /authorization|bearer|credential|refresh[_-]?token|access[_-]?token/iu,
  );

  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);
});

test("surfaces a bounded dependency failure and no authoritative conclusion", async ({ page }) => {
  await page.goto(runPath(failedRunId));

  await expect(
    page.getByRole("status")
      .getByText("Prometheus unavailable — retry was not attempted; durable state unchanged."),
  ).toBeVisible();
  await expect(page.getByRole("heading", { name: "No authoritative conclusion" })).toBeVisible();
  await expect(page.getByText("No remediation action was exposed.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Refresh status" })).toBeVisible();
  await expect(page.getByText("Cited evidence")).toHaveCount(0);
});

test("keeps the evidence workflow usable at 375 pixels", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(runPath(completedRunId));

  await expect(page.getByRole("heading", { name: "Checkout latency regression" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Evidence spine" })).toBeVisible();
  const dimensions = await page.evaluate(() => ({
    viewport: window.innerWidth,
    document: document.documentElement.scrollWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);

  await page.setViewportSize({ width: 820, height: 1_000 });
  await page.reload();
  expect(await page.evaluate(() =>
    document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test("supports skip navigation and reduced motion", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto(runPath(completedRunId));

  await page.locator("body").press("Tab");
  const skipLink = page.getByRole("link", { name: "Skip to investigation content" });
  await expect(skipLink).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/#main-content$/u);
  const copyButton = page.getByRole("button", { name: "Copy run ID" });
  await copyButton.focus();
  await expect(copyButton).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(copyButton.locator("xpath=following-sibling::span")).toHaveText(
    /Copied|Copy unavailable/u,
  );
  const duration = await page.evaluate(() =>
    getComputedStyle(document.querySelector("button")!).transitionDuration);
  expect(Number.parseFloat(duration)).toBeLessThanOrEqual(0.00001);
});

test("renders the empty operator entry without exposing a fixture selector", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Select an authorized investigation" })).toBeVisible();
  await expect(page.getByText("No credential fallback")).toBeVisible();
  await expect(page.getByText("Session not asserted", { exact: true })).toBeVisible();
  expect(await page.locator("body").innerText()).not.toContain(completedRunId);
});

test("rejects an analysis projection containing raw reasoning fields", async ({ page }) => {
  await page.goto(runPath(invalidRunId));

  await expect(page.getByRole("heading", { name: "Projection verification failed" })).toBeVisible();
  const body = await page.locator("body").innerText();
  expect(body).not.toContain("This prohibited field");
  expect(body).not.toContain("reasoning_content");
  await expect(page.getByRole("heading", { name: "Cited conclusion" })).toHaveCount(0);
});

for (const scenario of [
  { article: "a", name: "cross-scope projection", runId: crossScopeRunId },
  { article: "an", name: "oversized projection", runId: oversizedRunId },
  { article: "an", name: "uncited complete projection", runId: uncitedRunId },
]) {
  test(`rejects ${scenario.article} ${scenario.name} before rendering incident data`, async ({ page }) => {
    await page.goto(runPath(scenario.runId));

    await expect(page.getByRole("heading", { name: "Projection verification failed" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Checkout latency regression" })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Cited conclusion" })).toHaveCount(0);
    await expect(page.getByText(/Support correlation/u)).toBeVisible();
  });
}

for (const scenario of [
  {
    name: "abstained",
    runId: abstainedRunId,
    reason: "Evidence remained insufficient for a cited conclusion.",
  },
  {
    name: "budget exceeded",
    runId: budgetRunId,
    reason: "The accepted token budget was exhausted.",
  },
]) {
  test(`renders the ${scenario.name} terminal state explicitly`, async ({ page }) => {
    await page.goto(runPath(scenario.runId));

    await expect(page.getByRole("status").getByText(scenario.reason)).toBeVisible();
    await expect(page.getByRole("heading", { name: "No authoritative conclusion" })).toBeVisible();
    await expect(page.getByText("No remediation action was exposed.")).toBeVisible();
  });
}

test("keeps durable state explicit when the Platform dependency is unavailable", async ({ page }) => {
  await page.goto(runPath(unavailableRunId));

  await expect(page.getByRole("heading", { name: "Platform data unavailable" })).toBeVisible();
  await expect(page.getByText(
    "The last durable state remains unchanged. No retry or downstream action was attempted.",
  )).toBeVisible();
  await expect(page.getByText("No credential fallback")).toBeVisible();
});
