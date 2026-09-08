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
