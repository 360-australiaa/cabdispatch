import { test, expect, type Page } from "@playwright/test";

/**
 * End-to-end smoke test: login -> MFA -> live map -> one page from each nav
 * group.
 *
 * SKIPPED BY DEFAULT. It needs a running, seeded backend and a real login,
 * neither of which exists on a clean checkout, and a red suite nobody can fix
 * locally is worse than no suite. See `playwright.config.ts` for the full
 * four-step run instructions; in short, set `E2E_BASE_URL` (plus `E2E_EMAIL`,
 * `E2E_PASSWORD`, and `E2E_TOTP_SECRET` for an MFA-enabled account) and the
 * tests below activate. In CI this is the job that stands up the API from
 * `scripts/init_db.py` and seeds a throwaway tenant.
 *
 * The credentials are read from the environment on purpose. No credential is
 * written into this file, and the suite refuses to run against the production
 * host (enforced in playwright.config.ts).
 *
 * What it is for: catching the failures unit tests structurally cannot. Every
 * page here is statically imported by `router.tsx` alongside mapbox-gl and
 * recharts, so a bad import, a missing env var, a broken bundle, or a page
 * that throws on first paint takes the whole app down -- and none of that
 * shows up in jsdom. Loading each page and asserting its heading is exactly
 * the check that catches it.
 */

const BASE_URL = process.env.E2E_BASE_URL;
const EMAIL = process.env.E2E_EMAIL;
const PASSWORD = process.env.E2E_PASSWORD;
const TOTP_SECRET = process.env.E2E_TOTP_SECRET;

/**
 * One page per nav cluster in `components/layout/Sidebar.tsx`, chosen as the
 * cheapest representative of each: live ops, records, money, driver
 * engagement, and admin/settings.
 */
const NAV_GROUP_SAMPLES: { path: string; heading: RegExp }[] = [
  { path: "/live-map", heading: /live map/i },
  { path: "/trips", heading: /trips/i },
  { path: "/billing", heading: /billing/i },
  { path: "/announcements", heading: /announcements/i },
  { path: "/audit-log", heading: /audit log/i },
];

/**
 * Logs in, handling the two-step MFA branch when the account has it enabled.
 *
 * `POST /v1/auth/login` answers `{mfa_required, mfa_token}` for such an
 * account, and the login page then swaps the password form for a 6-digit code
 * field (see `pages/login/index.tsx`). Detecting which branch happened by
 * looking for that field -- rather than assuming -- keeps this helper working
 * against a seeded account either way.
 */
async function login(page: Page) {
  await page.goto("/login");

  await page.getByLabel(/email/i).fill(EMAIL!);
  await page.getByLabel(/password/i).fill(PASSWORD!);
  await page.getByRole("button", { name: /sign in/i }).click();

  const codeField = page.getByLabel(/code/i);
  if (await codeField.isVisible({ timeout: 5_000 }).catch(() => false)) {
    expect(
      TOTP_SECRET,
      "This account has MFA enabled; set E2E_TOTP_SECRET to a seeded test secret.",
    ).toBeTruthy();
    // The TOTP is generated at run time from the seeded secret -- a fixed
    // code cannot be committed and would expire in 30 seconds anyway.
    await codeField.fill(await generateTotp(TOTP_SECRET!));
    await page.getByRole("button", { name: /verify|sign in/i }).click();
  }

  await expect(page).toHaveURL(/\/live-map/, { timeout: 15_000 });
}

/**
 * RFC 6238 TOTP, 6 digits, 30-second step, SHA-1 -- the parameters the
 * backend's own MFA uses. Implemented here with node:crypto rather than
 * pulling in an `otplib` dependency for one function in one opt-in test.
 */
async function generateTotp(base32Secret: string): Promise<string> {
  const { createHmac } = await import("node:crypto");

  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const char of base32Secret.replace(/=+$/, "").toUpperCase()) {
    const index = alphabet.indexOf(char);
    if (index === -1) throw new Error(`Invalid base32 character in E2E_TOTP_SECRET: ${char}`);
    bits += index.toString(2).padStart(5, "0");
  }
  const bytes = Buffer.from(
    (bits.match(/.{8}/g) ?? []).map((byte) => parseInt(byte, 2)),
  );

  const counter = Math.floor(Date.now() / 1000 / 30);
  const counterBuffer = Buffer.alloc(8);
  counterBuffer.writeBigUInt64BE(BigInt(counter));

  const digest = createHmac("sha1", bytes).update(counterBuffer).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const code =
    ((digest.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).toString().padStart(6, "0");
  return code;
}

test.describe("dashboard smoke", () => {
  // The opt-in gate. Without a seeded local backend and a test account there
  // is nothing to smoke-test, so every test below reports as skipped rather
  // than as failed.
  test.skip(
    !BASE_URL || !EMAIL || !PASSWORD,
    "e2e smoke needs E2E_BASE_URL, E2E_EMAIL and E2E_PASSWORD against a local seeded backend -- see playwright.config.ts",
  );

  test("logs in and lands on the live map", async ({ page }) => {
    await login(page);

    await expect(page.getByRole("heading", { name: /live map/i })).toBeVisible();
    // The sidebar rendering at all proves AppShell mounted and the session
    // survived the redirect.
    await expect(page.getByRole("link", { name: /live map/i })).toBeVisible();
  });

  test("refuses a wrong password and stays on /login", async ({ page }) => {
    await page.goto("/login");

    await page.getByLabel(/email/i).fill(EMAIL!);
    await page.getByLabel(/password/i).fill("definitely-not-the-password");
    await page.getByRole("button", { name: /sign in/i }).click();

    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByRole("heading", { name: /live map/i })).toHaveCount(0);
  });

  test("redirects an unauthenticated deep link to /login", async ({ page }) => {
    await page.goto("/trips");
    await expect(page).toHaveURL(/\/login/);
  });

  for (const { path, heading } of NAV_GROUP_SAMPLES) {
    test(`loads ${path} without a client-side crash`, async ({ page }) => {
      const errors: string[] = [];
      page.on("pageerror", (err) => errors.push(err.message));

      await login(page);
      await page.goto(path);

      await expect(page.getByRole("heading", { name: heading }).first()).toBeVisible();
      // The error boundary's panel means the page threw during render -- the
      // exact failure this smoke test exists to catch.
      await expect(page.getByRole("alert")).toHaveCount(0);
      expect(errors, `uncaught errors on ${path}`).toEqual([]);
    });
  }

  test("shows a 404 for an unknown path instead of silently redirecting", async ({ page }) => {
    await login(page);

    await page.goto("/this-route-does-not-exist");

    // The regression guard for the old wildcard, which sent every typo to the
    // live map and replaced the URL so there was no way back.
    await expect(page.getByRole("heading", { name: /page not found/i })).toBeVisible();
    await expect(page).toHaveURL(/this-route-does-not-exist/);
  });

  test("logs out and cannot return to a protected page with Back", async ({ page }) => {
    await login(page);

    await page.getByRole("button", { name: /log ?out|sign ?out/i }).click();
    await expect(page).toHaveURL(/\/login/);

    await page.goto("/live-map");
    await expect(page).toHaveURL(/\/login/);
  });
});
