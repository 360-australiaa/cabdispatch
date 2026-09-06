import axios, { type InternalAxiosRequestConfig } from "axios";

/** `AxiosRequestConfig` doesn't have a field for "have we already retried this
 * request once" — `_retried` here is this module's own marker, set on the
 * `error.config` object the response interceptor gets handed back, so a
 * request that fails again after a refresh doesn't loop forever retrying. */
type RetriableRequestConfig = InternalAxiosRequestConfig & { _retried?: boolean };

/**
 * Base URL for the Cab Dispatch backend. All domain routes live under
 * `/v1/...` (see shared/API_SUMMARY.md) — `/health` is the only unversioned
 * route. Override at build time with VITE_API_URL if needed.
 */
export const API_BASE_URL =
  (import.meta.env.VITE_API_URL as string | undefined) ?? "http://localhost:8001";

export const ACCESS_TOKEN_KEY = "cd_access_token";
export const REFRESH_TOKEN_KEY = "cd_refresh_token";

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function setTokens(accessToken: string, refreshToken: string) {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
}

export function clearTokens() {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
}

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
});

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getAccessToken();
  if (token) {
    config.headers.set("Authorization", `Bearer ${token}`);
  }
  return config;
});

/**
 * Silent refresh-on-401 (2026-09-06). The backend has held a real
 * `POST /v1/auth/refresh` endpoint and this module has stored a 14-day
 * `REFRESH_TOKEN_KEY` the whole time, but nothing ever called it — every
 * `ACCESS_TOKEN_EXPIRE_MINUTES` (30 by default) expiry force-logged the user
 * out instead of quietly refreshing. Real bug found live: a 144MB APK
 * publish upload outlived one access token and lost the whole session
 * mid-upload. [refreshPromise] is shared so N requests failing at once queue
 * behind one real refresh call instead of firing N of them (and racing to
 * each stomp `localStorage` with their own new token pair).
 */
let refreshPromise: Promise<string | null> | null = null;

interface RefreshTokenResponse {
  access_token: string;
  refresh_token: string;
}

function refreshAccessToken(): Promise<string | null> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return Promise.resolve(null);

  if (!refreshPromise) {
    refreshPromise = axios
      .post<RefreshTokenResponse>(`${API_BASE_URL}/v1/auth/refresh`, {
        refresh_token: refreshToken,
      })
      .then(({ data }) => {
        setTokens(data.access_token, data.refresh_token);
        return data.access_token;
      })
      .catch(() => {
        clearTokens();
        return null;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

function redirectToLogin() {
  clearTokens();
  // Full reload to /login so all in-memory auth/query state resets.
  if (window.location.pathname !== "/login") {
    window.location.assign("/login");
  }
}

apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (!axios.isAxiosError(error) || error.response?.status !== 401) {
      return Promise.reject(error);
    }

    const original = error.config as RetriableRequestConfig | undefined;
    const url = original?.url ?? "";
    // A 401 from the auth endpoints themselves is a real "bad credentials" or
    // "refresh token itself is invalid/expired" answer, not a stale access
    // token — refreshing in response to that would either loop or paper over
    // a genuine login failure. Same for a request this interceptor already
    // retried once: a second 401 means the *new* token was also rejected, so
    // this session is really over.
    const isAuthEndpoint = ["/v1/auth/login", "/v1/auth/refresh", "/v1/auth/mfa/login"].some((p) =>
      url.includes(p),
    );
    if (!original || original._retried || isAuthEndpoint) {
      redirectToLogin();
      return Promise.reject(error);
    }

    const newAccessToken = await refreshAccessToken();
    if (!newAccessToken) {
      redirectToLogin();
      return Promise.reject(error);
    }

    // Re-sent in full, including `original.data` (a FormData for an upload
    // like Publish Release) — a File inside FormData is a stable reference
    // the browser re-reads from disk, not a stream a first send consumes, so
    // resending it here is safe, just a second real upload rather than a
    // resume from wherever the first one got to.
    original._retried = true;
    original.headers.set("Authorization", `Bearer ${newAccessToken}`);
    return apiClient(original);
  },
);

export default apiClient;
