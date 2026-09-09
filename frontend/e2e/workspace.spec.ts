import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
const password = readFileSync(new URL("../../.env", import.meta.url), "utf8")
  .split("\n")
  .find((l) => l.startsWith("ADMIN_PASSWORD="))!
  .split("=")[1]
  .trim();
test("log in, execute Python, replay persisted logs and open learning material", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByLabel("Username", { exact: true }).fill("admin");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Open workspace" }).click();
  await expect(
    page.getByRole("heading", { name: "Execution workspace" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Run tests", exact: true }).click();
  await expect(page.getByLabel("Execution logs")).toContainText("42", {
    timeout: 90000,
  });
  await page.getByRole("button", { name: "Test results", exact: true }).click();
  await expect(
    page.locator("tbody").getByText("accepted", { exact: true }),
  ).toBeVisible({ timeout: 90000 });
  await page.getByRole("button", { name: /Run history/ }).click();
  await page.getByRole("button", { name: "Open →" }).first().click();
  await page.getByRole("button", { name: "Live output", exact: true }).click();
  await expect(page.getByLabel("Execution logs")).toContainText("42");
  await page.getByRole("button", { name: /Learning path/ }).click();
  await page.getByRole("button", { name: /The system map/ }).click();
  await expect(
    page.getByRole("heading", { name: /system map/i, level: 1 }),
  ).toBeVisible();
  await page.screenshot({
    path: "test-results/workspace-desktop.png",
    fullPage: true,
  });
});
test("mobile login and escaped program output remain usable", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByLabel("Username", { exact: true }).fill("admin");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page
    .getByLabel("Source code")
    .fill('print("<script>window.codegridInjected = true</script>")\n');
  await page
    .getByLabel("Expected output 1", { exact: true })
    .fill("<script>window.codegridInjected = true</script>\n");
  await page.getByRole("button", { name: "Run tests", exact: true }).click();
  await expect(page.getByLabel("Execution logs")).toContainText("<script>", {
    timeout: 90000,
  });
  expect(
    await page.evaluate(() => Object.hasOwn(window, "codegridInjected")),
  ).toBe(false);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});
