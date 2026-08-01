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
}

interface TokenResponse {
  access_token: string;
  refresh_token: string;
  token_type: string;
  user: CurrentUser;
}

interface AuthContextValue {
  user: CurrentUser | null;
  isAuthenticated: boolean;
  /** True while the initial session (token -> /v1/auth/me) is being resolved. */
  isLoading: boolean;
  login: (email: string, password: string) => Promise<void>;
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

  const login = useCallback(async (email: string, password: string) => {
    const res = await apiClient.post<TokenResponse>("/v1/auth/login", {
      email,
      password,
    });
    setTokens(res.data.access_token, res.data.refresh_token);
    setUser(res.data.user);
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
      logout,
    }),
    [user, isLoading, login, logout],
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
