import { describe, expect, it } from "vitest";
import { PLATFORM_TENANT_ID } from "@/lib/platformAdmin";
import type { CurrentUser } from "@/lib/auth";
import { canUseFleetTestTooling, isTestToolingEnabled } from "./testTooling";

/**
 * The whole point of these two functions is that they fail closed, so the
 * cases worth asserting are the ones where something is *almost* right: the
 * flag set to a truthy-looking value that is not the literal "true", an owner
 * of an ordinary tenant rather than the platform tenant, and each gate passing
 * on its own without the other.
 */

const ON = { VITE_ENABLE_TEST_TOOLING: "true" };
const OFF = { VITE_ENABLE_TEST_TOOLING: undefined };

function user(overrides: Partial<CurrentUser>): CurrentUser {
  return {
    id: "u1",
    tenant_id: "11111111-1111-1111-1111-111111111111",
    role: "dispatcher",
    name: "Test User",
    email: "test@example.com",
    status: "active",
    mfa_enabled: false,
    ...overrides,
  };
}

const platformOwner = user({ role: "owner", tenant_id: PLATFORM_TENANT_ID });
const tenantOwner = user({ role: "owner" });

describe("isTestToolingEnabled", () => {
  it("is on only for the literal string 'true'", () => {
    expect(isTestToolingEnabled(ON)).toBe(true);
  });

  it.each(["", "1", "TRUE", "yes", "false", undefined])(
    "is off for %o",
    (value) => {
      expect(isTestToolingEnabled({ VITE_ENABLE_TEST_TOOLING: value })).toBe(false);
    },
  );

  it("is off when the variable is absent entirely", () => {
    expect(isTestToolingEnabled({})).toBe(false);
  });
});

describe("canUseFleetTestTooling", () => {
  it("allows a platform owner on a build with the flag on", () => {
    expect(canUseFleetTestTooling(platformOwner, ON)).toBe(true);
  });

  it("refuses a platform owner when the flag is off", () => {
    expect(canUseFleetTestTooling(platformOwner, OFF)).toBe(false);
  });

  it("refuses an ordinary tenant's owner even with the flag on", () => {
    expect(canUseFleetTestTooling(tenantOwner, ON)).toBe(false);
  });

  it.each(["admin", "dispatcher", "driver"])(
    "refuses role %s on the platform tenant even with the flag on",
    (role) => {
      expect(canUseFleetTestTooling(user({ role, tenant_id: PLATFORM_TENANT_ID }), ON)).toBe(false);
    },
  );

  it("refuses a signed-out visitor", () => {
    expect(canUseFleetTestTooling(null, ON)).toBe(false);
    expect(canUseFleetTestTooling(undefined, ON)).toBe(false);
  });
});
