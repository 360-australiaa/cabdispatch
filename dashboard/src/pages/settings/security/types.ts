/** Mirrors backend MFA schemas in `app/schemas/auth.py` (shared/openapi.json). */

export interface MfaSetupResponse {
  secret: string;
  otpauth_uri: string;
}

export interface MfaVerifyRequest {
  code: string;
}

export interface MfaDisableRequest {
  password: string;
}

export interface MfaStatusResponse {
  mfa_enabled: boolean;
}

/** Mirrors `AdminPinSetRequest` in `backend/app/schemas/tenant.py` (4-8 digit PIN). */
export interface AdminPinSetRequest {
  pin: string;
}

/** Mirrors `AdminPinSetResponse` — the PIN itself is never echoed back (write-only). */
export interface AdminPinSetResponse {
  tenant_id: string;
  admin_pin_configured: boolean;
}

// --- D10: password change / reset -------------------------------------------

export interface PasswordChangeRequest {
  current_password: string;
  new_password: string;
}

export interface PasswordResetRequest {
  email: string;
}

export interface PasswordResetConfirmRequest {
  reset_token: string;
  new_password: string;
}

// --- D10: MFA recovery codes --------------------------------------------------

/** The ten plaintext codes — present ONLY in the direct response of
 * `generateRecoveryCodes`; never fetchable again afterwards. */
export interface RecoveryCodesResponse {
  codes: string[];
}

export interface RecoveryCodeStatus {
  remaining: number;
}

// --- D10: sessions ("sign out everywhere") -----------------------------------

export interface SessionRead {
  id: string;
  user_agent: string | null;
  ip_address: string | null;
  created_at: string;
  last_seen_at: string;
  is_current: boolean;
}

export interface SessionListResponse {
  sessions: SessionRead[];
}
