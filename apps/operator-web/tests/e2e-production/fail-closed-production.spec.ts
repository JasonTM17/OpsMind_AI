import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

const investigationPath =
  "/organizations/10000000-0000-4000-8000-000000000702" +
  "/projects/10000000-0000-4000-8000-000000000703" +
  "/incidents/10000000-0000-4000-8000-000000000704" +
  "/investigations/10000000-0000-4000-8000-000000000701";

test("production bundle resolves runtime environment without a credential fallback", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByText("Staging", { exact: true })).toBeVisible();
  await expect(page.getByText("Session not asserted", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Select an authorized investigation" })).toBeVisible();
});

test("production investigation route fails closed without a BFF session", async ({ page }) => {
  await page.goto(investigationPath);

  await expect(page.getByRole("heading", { name: "Secure operator session unavailable" })).toBeVisible();
  await expect(page.getByText("No credential fallback")).toBeVisible();
  await expect(page.getByText("Session not asserted", { exact: true })).toBeVisible();
  const body = await page.locator("body").innerText();
  expect(body).not.toMatch(/authorization|bearer|access[_-]?token|refresh[_-]?token/iu);

  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);
});
