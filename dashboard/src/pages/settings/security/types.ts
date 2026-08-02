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
