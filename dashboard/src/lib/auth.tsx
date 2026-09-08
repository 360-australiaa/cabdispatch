import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import apiClient, {
  clearTokens,
  getAccessToken,
  setTokens,
} from "./apiClient";

/** Mirrors backend `UserRead` (shared/openapi.json). */
export interface CurrentUser {
  id: string;
  tenant_id: string | null;
  role: "owner" | "admin" | "dispatcher" | "driver" | string;
  name: string;
  email: string;
  status: string;
  mfa_enabled: boolean;
}

/**
 * Mirrors backend `TenantRead` (`app/schemas/tenant.py`) — the same shape
 * `hooks/useWhite-labelSettings.ts`'s `TenantRead` already re-declares for
 * its own `GET /v1/tenants/me` call. Duplicated rather than imported from
 * there to avoid a `lib` -> `hooks` -> (future) `lib` import cycle; `lib` is
 * meant to be the dependency-free base layer other modules build on.
 *
 * WAVE 3 (D6): this is the "tenant record in context" the program plan asks
 * for. It carries branding (`theme_json`) and identity today. It does NOT
 * yet carry `timezone`/`currency` — those fields don't exist on the backend
 * row (see `lib/i18n/jurisdiction.ts`'s doc comment for the same gap,
 * confirmed against `wave3/x1-jurisdiction`, which owns adding them). Once
 * they land, add them here and `lib/format.ts` has a real value to read
 * instead of its pinned `en-AU`/`AUD` constants.
 */
export interface TenantRecord {
  id: string;
  name: string;
  theme_json: {
    logo_url: string | null;
    primary_color: string | null;
    accent_color: string | null;
  } | null;
  plan: string;
  status: string;
}

interface TokenResponse {
  access_token: string;
  refresh_token: string;
  token_type: string;
  user: CurrentUser;
}

/** Returned by POST /v1/auth/login instead of TokenResponse when mfa_enabled=True. */
interface MfaRequiredResponse {
  mfa_required: true;
  mfa_token: string;
}

/** Result of `login()` — callers branch on `mfaRequired` to decide whether a second step is needed. */
export type LoginResult = { mfaRequired: false } | { mfaRequired: true; mfaToken: string };

interface AuthContextValue {
  user: CurrentUser | null;
  /** Null until resolved, and stays null for a user with no tenant (e.g. a
   * platform-owner account — see `lib/platformAdmin.ts`) or if the tenant
   * fetch itself fails; a failure here must not block login, so it is
   * swallowed the same way the `/v1/auth/me` failure path already is below. */
  tenant: TenantRecord | null;
  isAuthenticated: boolean;
  /** True while the initial session (token -> /v1/auth/me) is being resolved. */
  isLoading: boolean;
  login: (email: string, password: string) => Promise<LoginResult>;
  /** Second step of login for mfa_enabled accounts: exchanges the mfa_token
   * plus EITHER a TOTP `code` OR a `recoveryCode` (D10: the "lost my
   * authenticator" fallback) for real tokens. Exactly one of the two must be
   * supplied — same contract `POST /v1/auth/mfa/login` enforces server-side. */
  completeMfaLogin: (
    mfaToken: string,
    credential: { code: string } | { recoveryCode: string },
  ) => Promise<void>;
  /** Re-fetches /v1/auth/me — call after enabling/disabling MFA so `user.mfa_enabled` stays in sync. */
  refreshUser: () => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [tenant, setTenant] = useState<TenantRecord | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  /** Best-effort: a tenant-less account (platform owner) or a transient
   * failure both just mean "no tenant record to brand with" — never worth
   * failing the session over, since nothing in this dashboard is unusable
   * without it today. See the `TenantRecord` doc comment for what it does
   * carry and what it's still missing (timezone/currency). */
  const fetchTenant = useCallback(async () => {
    try {
      const res = await apiClient.get<TenantRecord>("/v1/tenants/me");
      setTenant(res.data);
    } catch {
      setTenant(null);
    }
  }, []);

  useEffect(() => {
    const token = getAccessToken();
    if (!token) {
      setIsLoading(false);
      return;
    }
    apiClient
      .get<CurrentUser>("/v1/auth/me")
      .then((res) => {
        setUser(res.data);
        return fetchTenant();
      })
      .catch(() => {
        clearTokens();
        setUser(null);
      })
      .finally(() => setIsLoading(false));
  }, [fetchTenant]);

  const login = useCallback(async (email: string, password: string): Promise<LoginResult> => {
    const res = await apiClient.post<TokenResponse | MfaRequiredResponse>("/v1/auth/login", {
      email,
      password,
    });
    if ("mfa_required" in res.data && res.data.mfa_required) {
      return { mfaRequired: true, mfaToken: res.data.mfa_token };
    }
    const data = res.data as TokenResponse;
    setTokens(data.access_token, data.refresh_token);
    setUser(data.user);
    await fetchTenant();
    return { mfaRequired: false };
  }, [fetchTenant]);

  const completeMfaLogin = useCallback(
    async (mfaToken: string, credential: { code: string } | { recoveryCode: string }) => {
      const res = await apiClient.post<TokenResponse>("/v1/auth/mfa/login", {
        mfa_token: mfaToken,
        code: "code" in credential ? credential.code : undefined,
        recovery_code: "recoveryCode" in credential ? credential.recoveryCode : undefined,
      });
      setTokens(res.data.access_token, res.data.refresh_token);
      setUser(res.data.user);
      await fetchTenant();
    },
    [fetchTenant],
  );

  const refreshUser = useCallback(async () => {
    const res = await apiClient.get<CurrentUser>("/v1/auth/me");
    setUser(res.data);
  }, []);

  const logout = useCallback(() => {
    // Best-effort; token is discarded client-side regardless of the result.
    apiClient.post("/v1/auth/logout").catch(() => {});
    clearTokens();
    setUser(null);
    setTenant(null);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      tenant,
      isAuthenticated: user !== null,
      isLoading,
      login,
      completeMfaLogin,
      refreshUser,
      logout,
    }),
    [user, tenant, isLoading, login, completeMfaLogin, refreshUser, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return ctx;
}
