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
  isAuthenticated: boolean;
  /** True while the initial session (token -> /v1/auth/me) is being resolved. */
  isLoading: boolean;
  login: (email: string, password: string) => Promise<LoginResult>;
  /** Second step of login for mfa_enabled accounts: exchanges the mfa_token + TOTP code for real tokens. */
  completeMfaLogin: (mfaToken: string, code: string) => Promise<void>;
  /** Re-fetches /v1/auth/me — call after enabling/disabling MFA so `user.mfa_enabled` stays in sync. */
  refreshUser: () => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const token = getAccessToken();
    if (!token) {
      setIsLoading(false);
      return;
    }
    apiClient
      .get<CurrentUser>("/v1/auth/me")
      .then((res) => setUser(res.data))
      .catch(() => {
        clearTokens();
        setUser(null);
      })
      .finally(() => setIsLoading(false));
  }, []);

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
    return { mfaRequired: false };
  }, []);

  const completeMfaLogin = useCallback(async (mfaToken: string, code: string) => {
    const res = await apiClient.post<TokenResponse>("/v1/auth/mfa/login", {
      mfa_token: mfaToken,
      code,
    });
    setTokens(res.data.access_token, res.data.refresh_token);
    setUser(res.data.user);
  }, []);

  const refreshUser = useCallback(async () => {
    const res = await apiClient.get<CurrentUser>("/v1/auth/me");
    setUser(res.data);
  }, []);

  const logout = useCallback(() => {
    // Best-effort; token is discarded client-side regardless of the result.
    apiClient.post("/v1/auth/logout").catch(() => {});
    clearTokens();
    setUser(null);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      isAuthenticated: user !== null,
      isLoading,
      login,
      completeMfaLogin,
      refreshUser,
      logout,
    }),
    [user, isLoading, login, completeMfaLogin, refreshUser, logout],
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
