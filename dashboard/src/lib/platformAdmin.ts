import type { CurrentUser } from "./auth";

/**
 * Mirrors `app.core.security.PLATFORM_TENANT_ID` on the backend. The
 * platform admin console (`/platform`) is gated both server-side
 * (`require_platform_owner` in `app/api/v1/platform.py`) and here
 * client-side — role == "owner" alone is not enough, it must also be the
 * distinguished platform tenant, not just any ordinary tenant's owner.
 */
export const PLATFORM_TENANT_ID = "00000000-0000-0000-0000-000000000000";

export function isPlatformOwner(user: CurrentUser | null | undefined): boolean {
  return !!user && user.role === "owner" && user.tenant_id === PLATFORM_TENANT_ID;
}
