import apiClient from "@/lib/apiClient";
import type {
  AdminPinSetRequest,
  AdminPinSetResponse,
  MfaDisableRequest,
  MfaSetupResponse,
  MfaStatusResponse,
  MfaVerifyRequest,
  PasswordChangeRequest,
  RecoveryCodesResponse,
  RecoveryCodeStatus,
  SessionListResponse,
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

/** Requires the current password; hashed server-side with the same scheme
 * the account's password already uses (no new hashing scheme). */
export async function changePassword(body: PasswordChangeRequest): Promise<void> {
  await apiClient.post("/v1/auth/password/change", body);
}

/**
 * Generates a fresh batch of ten single-use MFA recovery codes. The response
 * is the ONLY time the plaintext codes are ever available — the caller must
 * show them to the user immediately and never persist them itself (no
 * localStorage, no console.log).
 */
export async function generateRecoveryCodes(): Promise<RecoveryCodesResponse> {
  const res = await apiClient.post<RecoveryCodesResponse>("/v1/auth/mfa/recovery-codes/generate");
  return res.data;
}

export async function getRecoveryCodeStatus(): Promise<RecoveryCodeStatus> {
  const res = await apiClient.get<RecoveryCodeStatus>("/v1/auth/mfa/recovery-codes/status");
  return res.data;
}

/** Every currently-live session for the caller's own account. */
export async function listSessions(): Promise<SessionListResponse> {
  const res = await apiClient.get<SessionListResponse>("/v1/auth/sessions");
  return res.data;
}

/** Revokes one session by id — dies everywhere, including an already-open
 * websocket connection authenticated with it (see backend
 * `app.core.security.revocation_aware_pump`). */
export async function revokeSession(sessionId: string): Promise<void> {
  await apiClient.post(`/v1/auth/sessions/${sessionId}/revoke`);
}

/** "Sign out everywhere" — revokes every OTHER live session by default. Pass
 * `includeCurrent: true` to also sign the caller themselves out. */
export async function revokeAllSessions(includeCurrent = false): Promise<void> {
  await apiClient.post(`/v1/auth/sessions/revoke-all?keep_current=${!includeCurrent}`);
}
