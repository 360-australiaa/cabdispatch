import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright config for the dashboard's end-to-end smoke test.
 *
 * Unlike the vitest suite, this one drives a *real* browser against a *real*
 * backend -- that is the whole point of it, and it is also why it does not
 * run by default. The spec needs a seeded API (a tenant, an MFA-enabled user,
 * and at least one vehicle) that no developer machine has by default, and a
 * suite that fails for everyone who has not set one up is a suite everyone
 * learns to ignore.
 *
 * So it is opt-in on `E2E_BASE_URL`. To run it:
 *
 *   1. Start a local backend against local SQLite:
 *        cd backend && uv run python scripts/init_db.py && uv run uvicorn app.main:app --port 8001
 *   2. Seed a user whose credentials you supply below -- never a real one,
 *      and never against the production host.
 *   3. Build and serve the dashboard against that API:
 *        cd dashboard && VITE_API_URL=http://localhost:8001 npm run build && npm run preview
 *   4. Run:
 *        E2E_BASE_URL=http://localhost:4173 \
 *        E2E_EMAIL=... E2E_PASSWORD=... E2E_TOTP_SECRET=... \
 *        npx playwright test
 *
 * Credentials come from the environment and are never committed: operating
 * rule §1.4 forbids an agent entering, minting, printing or committing any.
 *
 * `E2E_BASE_URL` must point at a local instance. The production host
 * (72.61.107.107) is refused outright below -- these tests log in and click
 * through pages, and the program's hard rule is that nothing here ever
 * touches it.
 */

const baseURL = process.env.E2E_BASE_URL;

if (baseURL && baseURL.includes("72.61.107.107")) {
  throw new Error(
    "E2E_BASE_URL points at the production server. The e2e suite must only ever run against a local backend.",
  );
}

export default defineConfig({
  testDir: "./e2e",
  // A smoke test that has to be run twice to be believed is not a smoke test.
  retries: 0,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  reporter: process.env.CI ? "list" : "html",
  use: {
    baseURL: baseURL ?? "http://localhost:4173",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
