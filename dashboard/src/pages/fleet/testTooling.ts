import { isPlatformOwner } from "@/lib/platformAdmin";
import type { CurrentUser } from "@/lib/auth";

/**
 * The two independent gates that keep test-only tooling out of production UI.
 *
 * "Wipe all fleet data" (see WipeAllFleetDataButton in ./index.tsx) irreversibly
 * deletes every vehicle, device and driver on a tenant, and -- with its own
 * separate opt-in -- their PSL ledger, wallet, ratings and compliance documents
 * too. It exists for onboarding testing and has no place on a page an ordinary
 * operator can open, so it is hidden behind BOTH of the checks below rather than
 * either one:
 *
 *   1. A build-time env flag (`VITE_ENABLE_TEST_TOOLING`). Vite inlines VITE_*
 *      at build time, so a production bundle built without it cannot have the
 *      tooling switched on afterwards by any amount of client-side tampering --
 *      the branch is dead code the bundler can drop.
 *   2. A platform-owner account -- the distinguished platform tenant, not merely
 *      any tenant's owner (see lib/platformAdmin.ts). So even a build that ships
 *      the flag by mistake shows nothing to a real tenant's staff.
 *
 * Neither gate alone is treated as sufficient, and neither is a substitute for
 * server-side authorization: the backend wipe routes stay as they are and keep
 * enforcing their own permissions. This module only decides what the dashboard
 * is willing to *offer*.
 */

/** True only for the exact string "true" -- an unset, empty, "1" or "false"
 * value all read as "off". Deliberately strict: an ambiguous env value on a
 * production build must fail closed. */
export function isTestToolingEnabled(
  env: { VITE_ENABLE_TEST_TOOLING?: string } = import.meta.env,
): boolean {
  return env.VITE_ENABLE_TEST_TOOLING === "true";
}

/** Both gates. The only thing that may render the fleet wipe UI. */
export function canUseFleetTestTooling(
  user: CurrentUser | null | undefined,
  env?: { VITE_ENABLE_TEST_TOOLING?: string },
): boolean {
  return isTestToolingEnabled(env) && isPlatformOwner(user);
}
