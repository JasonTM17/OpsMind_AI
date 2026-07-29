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
  longContent: longContentRunId,
  oversized: oversizedRunId,
  unavailable: unavailableRunId,
  uncited: uncitedRunId,
} = investigationRuns;

test("renders a cited completed investigation without browser credentials", async ({ page }) => {
  await page.goto(runPath(completedRunId));

  await expect(page.locator("main#main-content")).toHaveCount(1);
  await expect(page.getByRole("heading", { level: 1 })).toHaveCount(1);
  await expect(page.getByRole("heading", { name: "Checkout latency regression" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Evidence spine" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Cited conclusion" })).toBeVisible();
  await expect(page.getByRole("meter", { name: "Hypothesis confidence" })).toHaveAttribute(
    "aria-valuetext",
    "83%",
  );
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

  await expectNoAccessibilityViolations(page);
});

test("surfaces a bounded dependency failure and no authoritative conclusion", async ({ page }) => {
  const path = runPath(failedRunId);
  await page.goto(path);

  await expect(
    page.getByRole("status")
      .getByText("Prometheus unavailable — retry was not attempted; durable state unchanged."),
  ).toBeVisible();
  await expect(page.getByRole("heading", { name: "No authoritative conclusion" })).toBeVisible();
  await expect(page.getByText("No remediation action was exposed.")).toBeVisible();
  const refresh = page.getByRole("button", { name: "Refresh status" });
  await expect(refresh).toBeVisible();
  await expect(page.getByText("Cited evidence")).toHaveCount(0);

  const refreshedRoute = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === path
      && response.request().method() === "GET"
      && response.request().headers().rsc === "1";
  });
  await refresh.click();
  await expect(page.getByRole("status", { name: "Refresh status result" })).toHaveText(
    /Refreshing status\.|Status refreshed\./u,
  );
  await refreshedRoute;
  await expect(refresh).toBeVisible();
  await expect(page.getByText("Status refreshed.", { exact: true })).toBeAttached();
  await expectNoAccessibilityViolations(page);
});

test("uses the responsive 3, 2, and 1-column operator geometry without overflow", async ({
  page,
}) => {
  for (const scenario of [
    { width: 1440, columns: 3 },
    { width: 1024, columns: 2 },
    { width: 820, columns: 1 },
    { width: 768, columns: 1 },
    { width: 375, columns: 1 },
    { width: 320, columns: 1 },
  ]) {
    await page.setViewportSize({ width: scenario.width, height: 1_000 });
    await page.goto(runPath(completedRunId));
    await expect(page.getByRole("heading", { name: "Evidence spine" })).toBeVisible();
    await expectNoHorizontalOverflow(page);

    const geometry = await page.evaluate(() => {
      function box(selector: string) {
        const element = document.querySelector<HTMLElement>(selector);
        if (element === null) throw new Error(`Missing responsive panel: ${selector}`);
        const bounds = element.getBoundingClientRect();
        return { x: bounds.x, y: bounds.y, width: bounds.width };
      }
      return {
        context: box('[aria-labelledby="investigation-context-title"]'),
        evidence: box('[aria-labelledby="evidence-spine-title"]'),
        conclusion: box('[aria-labelledby="conclusion-title"]'),
      };
    });

    if (scenario.columns === 3) {
      expect(geometry.context.y).toBeCloseTo(geometry.evidence.y, 0);
      expect(geometry.evidence.y).toBeCloseTo(geometry.conclusion.y, 0);
      expect(geometry.context.x).toBeLessThan(geometry.evidence.x);
      expect(geometry.evidence.x).toBeLessThan(geometry.conclusion.x);
    } else if (scenario.columns === 2) {
      expect(geometry.context.y).toBeCloseTo(geometry.evidence.y, 0);
      expect(geometry.context.x).toBeLessThan(geometry.evidence.x);
      expect(geometry.conclusion.y).toBeGreaterThan(geometry.evidence.y);
      expect(geometry.conclusion.x).toBeCloseTo(geometry.context.x, 0);
    } else {
      expect(geometry.context.y).toBeLessThan(geometry.evidence.y);
      expect(geometry.evidence.y).toBeLessThan(geometry.conclusion.y);
      expect(geometry.context.x).toBeCloseTo(geometry.evidence.x, 0);
      expect(geometry.evidence.x).toBeCloseTo(geometry.conclusion.x, 0);
      expect(geometry.context.width).toBeCloseTo(geometry.evidence.width, 0);
      expect(geometry.evidence.width).toBeCloseTo(geometry.conclusion.width, 0);
    }
  }
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
  const copyStatus = copyButton.locator("xpath=following-sibling::span");
  await expect(copyStatus).toHaveText(
    /Copied|Copy unavailable/u,
  );
  const firstFeedback = await copyStatus.textContent();
  await page.keyboard.press("Enter");
  await expect(copyStatus).toHaveText(/Copied again|Copy still unavailable/u);
  await expect(copyStatus).not.toHaveText(firstFeedback ?? "");
  const duration = await page.evaluate(() =>
    getComputedStyle(document.querySelector("button")!).transitionDuration);
  expect(Number.parseFloat(duration)).toBeLessThanOrEqual(0.00001);
});

test("keeps the newest copy result when clipboard requests finish out of order", async ({ page }) => {
  await page.addInitScript(() => {
    const requests: Array<{
      resolve: () => void;
      reject: (reason?: unknown) => void;
    }> = [];
    Object.defineProperty(window, "__opsmindCopyRequests", { value: requests });
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: {
        writeText: () => new Promise<void>((resolve, reject) => requests.push({ resolve, reject })),
      },
    });
  });
  await page.goto(runPath(completedRunId));

  const copyButton = page.getByRole("button", { name: "Copy run ID" });
  await copyButton.click();
  await copyButton.click();
  await expect.poll(() => page.evaluate(() =>
    (window as unknown as { __opsmindCopyRequests: unknown[] })
      .__opsmindCopyRequests.length)).toBe(2);

  await settleCopyRequest(page, 1, "resolve");
  const copyStatus = copyButton.locator("xpath=following-sibling::span");
  await expect(copyStatus).toHaveText("Copied");
  await settleCopyRequest(page, 0, "reject");
  await expect(copyStatus).toHaveText("Copied");
});

test("does not announce an old correlation copy result after a status refresh", async ({ page }) => {
  await page.addInitScript(() => {
    const requests: Array<{
      resolve: () => void;
      reject: (reason?: unknown) => void;
    }> = [];
    Object.defineProperty(window, "__opsmindCopyRequests", { value: requests });
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: {
        writeText: () => new Promise<void>((resolve, reject) => requests.push({ resolve, reject })),
      },
    });
  });

  const path = runPath(failedRunId);
  await page.goto(path);

  const copyButton = page.getByRole("button", { name: "Copy correlation ID" });
  const correlationId = copyButton.locator("xpath=preceding-sibling::code");
  const previousCorrelationId = await correlationId.textContent();
  await copyButton.click();
  await expect.poll(() => page.evaluate(() =>
    (window as unknown as { __opsmindCopyRequests: unknown[] })
      .__opsmindCopyRequests.length)).toBe(1);

  const refreshedRoute = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === path
      && response.request().method() === "GET"
      && response.request().headers().rsc === "1";
  });
  await page.getByRole("button", { name: "Refresh status" }).click();
  await refreshedRoute;
  await expect.poll(() => correlationId.textContent()).not.toBe(previousCorrelationId);

  const copyStatus = copyButton.locator("xpath=following-sibling::span");
  await settleCopyRequest(page, 0, "resolve");
  await expect(copyStatus).toHaveText("");
});

test("renders the empty operator entry without exposing a fixture selector", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Select an authorized investigation" })).toBeVisible();
  await expect(page.getByText("No credential fallback")).toBeVisible();
  await expect(page.getByText("Session not asserted", { exact: true })).toBeVisible();
  expect(await page.locator("body").innerText()).not.toContain(completedRunId);

  // Every investigation state is scanned, but the entry route is the first
  // thing an operator reaches and was the only rendered page without a check.
  await expectNoAccessibilityViolations(page);
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
    await expectNoAccessibilityViolations(page);
  });
}

test("keeps durable state explicit when the Platform dependency is unavailable", async ({ page }) => {
  await page.goto(runPath(unavailableRunId));

  await expect(page.getByRole("heading", { name: "Platform data unavailable" })).toBeVisible();
  await expect(page.getByText(
    "The last durable state remains unchanged. No retry or downstream action was attempted.",
  )).toBeVisible();
  await expect(page.getByText("No credential fallback")).toBeVisible();
  const refresh = page.getByRole("button", { name: "Refresh status" });
  await expect(refresh).toBeVisible();
  expect(await refresh.evaluate((button) => button.getBoundingClientRect().height))
    .toBeGreaterThanOrEqual(44);
  await expectNoAccessibilityViolations(page);
});

test("wraps maximum-shape operator content without hiding evidence", async ({ page }) => {
  for (const width of [1024, 320]) {
    await page.setViewportSize({ width, height: 1_000 });
    await page.goto(runPath(longContentRunId));
    await expect(
      page
        .getByRole("complementary", { name: "Hypothesis", exact: true })
        .getByText("UnbrokenSignal".repeat(15), { exact: true }),
    ).toBeVisible();
    await expect(page.getByText("C".repeat(900), { exact: true }).first()).toBeVisible();
    await expect(page.getByText("200 durable reference(s)", { exact: true })).toBeVisible();
    await expect(
      page.getByRole("list", { name: "Durable evidence records" }).locator(":scope > li"),
    ).toHaveCount(200);
    await expectNoHorizontalOverflow(page);
  }
});

async function expectNoHorizontalOverflow(page: import("@playwright/test").Page) {
  const dimensions = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
}

async function expectNoAccessibilityViolations(page: import("@playwright/test").Page) {
  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);
}

async function settleCopyRequest(
  page: import("@playwright/test").Page,
  index: number,
  outcome: "resolve" | "reject",
) {
  await page.evaluate(({ requestIndex, requestOutcome }) => {
    const requests = (window as unknown as {
      __opsmindCopyRequests: Array<{
        resolve: () => void;
        reject: (reason?: unknown) => void;
      }>;
    }).__opsmindCopyRequests;
    const request = requests.at(requestIndex);
    if (request === undefined) throw new Error(`Missing copy request ${requestIndex}`);
    if (requestOutcome === "resolve") request.resolve();
    else request.reject(new DOMException("Clipboard denied", "NotAllowedError"));
  }, { requestIndex: index, requestOutcome: outcome });
}
