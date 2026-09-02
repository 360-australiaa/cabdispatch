import apiClient from "@/lib/apiClient";
import type {
  AdminPinSetRequest,
  AdminPinSetResponse,
  MfaDisableRequest,
  MfaSetupResponse,
  MfaStatusResponse,
  MfaVerifyRequest,
} from "./types";

/**
 * Generates a new TOTP secret and stores it as *pending* on the caller's
 * account (mfa_enabled stays false until confirmed via `mfaVerify`). Calling
 * this again before verifying overwrites the previous pending secret.
 */
export async function mfaSetup(): Promise<MfaSetupResponse> {
  const res = await apiClient.post<MfaSetupResponse>("/v1/auth/mfa/setup");
  return res.data;
}

/** Confirms a 6-digit code against the pending secret and flips mfa_enabled=true. */
export async function mfaVerify(body: MfaVerifyRequest): Promise<MfaStatusResponse> {
  const res = await apiClient.post<MfaStatusResponse>("/v1/auth/mfa/verify", body);
  return res.data;
}

/** Requires re-entering the current password (not a TOTP code) to turn MFA back off. */
export async function mfaDisable(body: MfaDisableRequest): Promise<MfaStatusResponse> {
  const res = await apiClient.post<MfaStatusResponse>("/v1/auth/mfa/disable", body);
  return res.data;
}

/**
 * Owner-only. Sets/updates the tenant's admin PIN — the PIN the driver's
 * Android tablet requires before it will run a factory reset (see
 * `AdminPinGateScreen` / `POST /v1/fleet/devices/{id}/verify-admin-pin`).
 * There is no separate "update" route or a way to read the PIN back
 * (write-only, like a password) — posting again overwrites the previous PIN.
 */
export async function setAdminPin(
  tenantId: string,
  body: AdminPinSetRequest,
): Promise<AdminPinSetResponse> {
  const res = await apiClient.post<AdminPinSetResponse>(
    `/v1/tenants/${tenantId}/admin-pin`,
    body,
  );
  return res.data;
}
