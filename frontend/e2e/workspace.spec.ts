import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { initial } from "../src/api";
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

test("switching accounts clears private drafts and ignores old job responses", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByLabel("Username", { exact: true }).fill("admin");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Open workspace" }).click();
  const privateSource = "# private to the first account\nprint(42)\n";
  await page.getByLabel("Source code").fill(privateSource);
  const accepted = page.waitForResponse(
    (r) => r.url().endsWith("/api/jobs") && r.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Run tests", exact: true }).click();
  const { id } = await (await accepted).json();
  await expect(page.getByLabel("Execution logs")).toContainText("42", {
    timeout: 90000,
  });
  await page.getByRole("button", { name: /Run history/ }).click();

  // Keep an authenticated response in flight across logout and a new login.
  let release!: () => void;
  let captured!: () => void;
  let delivered!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  const oldResponse = new Promise<void>((resolve) => {
    captured = resolve;
  });
  const delivery = new Promise<void>((resolve) => {
    delivered = resolve;
  });
  await page.route(`**/api/jobs/${id}`, async (route) => {
    const response = await route.fetch();
    captured();
    await gate;
    await route.fulfill({ response });
    delivered();
  });
  await page.getByRole("button", { name: "Open →" }).first().click();
  await oldResponse;
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await page
    .getByRole("button", { name: "New here? Create a local account" })
    .click();
  await page.getByLabel("Username", { exact: true }).fill(`e2e_${Date.now()}`);
  await page
    .getByLabel("Password", { exact: true })
    .fill("local-test-password-2026");
  await page
    .getByRole("button", { name: "Create account", exact: true })
    .click();
  await expect(page.getByLabel("Source code")).toHaveValue(
    initial("PYTHON").source,
  );

  const completed = page.waitForEvent("requestfinished", {
    predicate: (r) => r.url().endsWith(`/api/jobs/${id}`),
  });
  release();
  await delivery;
  await completed;
  await page.evaluate(
    () =>
      new Promise<void>((resolve) => {
        requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
      }),
  );
  await expect(page.getByLabel("Source code")).toHaveValue(
    initial("PYTHON").source,
  );
  await page.getByRole("button", { name: /Run history/ }).click();
  await expect(page.getByRole("button", { name: "Open →" })).toHaveCount(0);
});

test("challenge mode runs visible and server-controlled hidden tests", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByLabel("Username", { exact: true }).fill("admin");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page.getByRole("button", { name: /Factorial/ }).click();
  await expect(page.getByText(/Remember that 0!/)).toBeVisible();
  await expect(page.getByLabel("Input 1")).toHaveValue("5\n");
  await expect(page.getByLabel("Input 1")).toHaveAttribute("readonly", "");
  await page
    .getByLabel("Source code")
    .fill(
      "n = int(input())\nresult = 1\nfor value in range(2, n + 1):\n    result *= value\nprint(result)\n",
    );
  await page.getByRole("button", { name: "Run tests", exact: true }).click();
  await page.getByRole("button", { name: "Test results", exact: true }).click();
  await expect(
    page.locator("tbody").getByText("accepted", { exact: true }).first(),
  ).toBeVisible({ timeout: 90000 });
  await expect(page.locator("tbody tr")).toHaveCount(5);
});
