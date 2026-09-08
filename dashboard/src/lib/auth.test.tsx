import { useEffect } from "react";
import { render, screen, waitFor, act } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { API, server, startMockServer } from "@/test/server";
import { AuthProvider, useAuth, type LoginResult } from "./auth";
import { ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY } from "./apiClient";

/**
 * Tests for the auth context, with the two-step MFA branch as the main
 * subject.
 *
 * `POST /v1/auth/login` is polymorphic: for an account with `mfa_enabled` it
 * answers `{mfa_required: true, mfa_token}` -- an HTTP 200 that is *not* a
 * session -- and only `POST /v1/auth/mfa/login` returns real tokens. The
 * failure mode worth guarding against is `login()` treating that 200 as
 * success and writing `undefined` into `localStorage`, which would leave a
 * user who never passed MFA holding a token pair.
 */

startMockServer();

/**
 * `AuthProvider` fetches the tenant record alongside the user (D6's "tenant in
 * context"). It is not the subject of any test here, but `server.listen` runs
 * with `onUnhandledRequest: "error"`, so leaving it unstubbed makes every test
 * in this file race an erroring request -- which is exactly how it showed up:
 * green in isolation, one order-dependent failure under the full suite.
 * Registered per-test because `resetHandlers()` runs after each one.
 */
beforeEach(() => {
  server.use(
    http.get(`${API}/v1/tenants/me`, () =>
      HttpResponse.json({ id: "t1", name: "Test Tenant", theme_json: null }),
    ),
  );
});

const USER = {
  id: "u1",
  tenant_id: "t1",
  role: "admin" as const,
  name: "Ops User",
  email: "ops@example.test",
  status: "active",
  mfa_enabled: false,
};

/** Test harness that exposes the context value to the assertions below. */
let auth: ReturnType<typeof useAuth>;

function Probe() {
  const value = useAuth();
  // Captured in an effect rather than assigned during render: writing to an
  // outer variable mid-render is a side effect, and react-hooks/globals
  // rightly errors on it. No dependency array, so every render refreshes the
  // handle and the assertions never read a stale context value.
  useEffect(() => {
    auth = value;
  });
  // Renders from the local `value`, never from the outer `auth` handle: that
  // one is only populated after the first commit.
  return (
    <div>
      <span data-testid="loading">{String(value.isLoading)}</span>
      <span data-testid="authed">{String(value.isAuthenticated)}</span>
      <span data-testid="name">{value.user?.name ?? "none"}</span>
    </div>
  );
}

async function renderAuth() {
  render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  );
  // The provider resolves the stored session on mount; wait it out so no test
  // asserts against the loading state by accident.
  await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));
}

beforeEach(() => {
  localStorage.clear();
  Object.defineProperty(window, "location", {
    configurable: true,
    value: { pathname: "/login", assign: vi.fn() },
  });
});

describe("AuthProvider session bootstrap", () => {
  it("starts unauthenticated when there is no stored token, without calling /me", async () => {
    let meCalls = 0;
    server.use(
      http.get(`${API}/v1/auth/me`, () => {
        meCalls += 1;
        return HttpResponse.json(USER);
      }),
    );

    await renderAuth();

    expect(meCalls).toBe(0);
    expect(screen.getByTestId("authed")).toHaveTextContent("false");
  });

  it("restores the user from /v1/auth/me when a token is stored", async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, "access-1");
    server.use(http.get(`${API}/v1/auth/me`, () => HttpResponse.json(USER)));

    await renderAuth();

    expect(screen.getByTestId("authed")).toHaveTextContent("true");
    expect(screen.getByTestId("name")).toHaveTextContent("Ops User");
  });

  it("discards a stored token that /v1/auth/me rejects", async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, "stale");
    localStorage.setItem(REFRESH_TOKEN_KEY, "stale-refresh");
    server.use(http.get(`${API}/v1/auth/me`, () => new HttpResponse(null, { status: 403 })));

    await renderAuth();

    expect(screen.getByTestId("authed")).toHaveTextContent("false");
    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
  });
});

describe("login without MFA", () => {
  it("stores both tokens and the user in one step", async () => {
    server.use(
      http.post(`${API}/v1/auth/login`, () =>
        HttpResponse.json({
          access_token: "access-1",
          refresh_token: "refresh-1",
          token_type: "bearer",
          user: USER,
        }),
      ),
    );
    await renderAuth();

    let result: LoginResult | undefined;
    await act(async () => {
      result = await auth.login("ops@example.test", "correct-horse");
    });

    expect(result).toEqual({ mfaRequired: false });
    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBe("access-1");
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe("refresh-1");
    expect(screen.getByTestId("authed")).toHaveTextContent("true");
  });

  it("propagates a rejected login and leaves the session untouched", async () => {
    server.use(
      http.post(`${API}/v1/auth/login`, () => new HttpResponse(null, { status: 401 })),
    );
    await renderAuth();

    await expect(auth.login("ops@example.test", "wrong")).rejects.toBeTruthy();

    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull();
    expect(screen.getByTestId("authed")).toHaveTextContent("false");
  });
});

describe("two-step MFA login", () => {
  it("returns the mfa_token and grants NO session on the first step", async () => {
    server.use(
      http.post(`${API}/v1/auth/login`, () =>
        HttpResponse.json({ mfa_required: true, mfa_token: "mfa-tok" }),
      ),
    );
    await renderAuth();

    let result: LoginResult | undefined;
    await act(async () => {
      result = await auth.login("mfa@example.test", "correct-horse");
    });

    expect(result).toEqual({ mfaRequired: true, mfaToken: "mfa-tok" });
    // The critical assertion: an `mfa_required` 200 is not a login. Nothing
    // may be written to storage and the app must still consider the user out.
    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
    expect(screen.getByTestId("authed")).toHaveTextContent("false");
  });

  it("exchanges the mfa_token plus a TOTP code for a real session", async () => {
    let body: { mfa_token?: string; code?: string } = {};
    server.use(
      http.post(`${API}/v1/auth/mfa/login`, async ({ request }) => {
        body = (await request.json()) as typeof body;
        return HttpResponse.json({
          access_token: "access-2",
          refresh_token: "refresh-2",
          token_type: "bearer",
          user: { ...USER, mfa_enabled: true },
        });
      }),
    );
    await renderAuth();

    await act(async () => {
      await auth.completeMfaLogin("mfa-tok", "123456");
    });

    // The second step must forward the token from step one, not the password.
    expect(body).toEqual({ mfa_token: "mfa-tok", code: "123456" });
    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBe("access-2");
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe("refresh-2");
    expect(screen.getByTestId("authed")).toHaveTextContent("true");
  });

  it("leaves the user logged out when the TOTP code is rejected", async () => {
    server.use(
      http.post(`${API}/v1/auth/mfa/login`, () => new HttpResponse(null, { status: 401 })),
    );
    await renderAuth();

    await expect(auth.completeMfaLogin("mfa-tok", "000000")).rejects.toBeTruthy();

    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull();
    expect(screen.getByTestId("authed")).toHaveTextContent("false");
  });
});

describe("refreshUser", () => {
  it("re-reads /v1/auth/me so an MFA change is reflected in the context", async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, "access-1");
    let call = 0;
    server.use(
      http.get(`${API}/v1/auth/me`, () => {
        call += 1;
        return HttpResponse.json({ ...USER, mfa_enabled: call > 1 });
      }),
    );
    await renderAuth();
    expect(auth.user?.mfa_enabled).toBe(false);

    await act(async () => {
      await auth.refreshUser();
    });

    expect(auth.user?.mfa_enabled).toBe(true);
  });
});

describe("logout", () => {
  it("clears both tokens and the user, and tells the backend best-effort", async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, "access-1");
    localStorage.setItem(REFRESH_TOKEN_KEY, "refresh-1");
    let logoutCalls = 0;
    server.use(
      http.get(`${API}/v1/auth/me`, () => HttpResponse.json(USER)),
      http.post(`${API}/v1/auth/logout`, () => {
        logoutCalls += 1;
        return HttpResponse.json({ ok: true });
      }),
    );
    await renderAuth();
    expect(screen.getByTestId("authed")).toHaveTextContent("true");

    await act(async () => {
      auth.logout();
    });

    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
    expect(screen.getByTestId("authed")).toHaveTextContent("false");
    await waitFor(() => expect(logoutCalls).toBe(1));
  });

  it("logs the user out locally even when the logout call fails", async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, "access-1");
    localStorage.setItem(REFRESH_TOKEN_KEY, "refresh-1");
    server.use(
      http.get(`${API}/v1/auth/me`, () => HttpResponse.json(USER)),
      http.post(`${API}/v1/auth/logout`, () => new HttpResponse(null, { status: 500 })),
    );
    await renderAuth();

    await act(async () => {
      auth.logout();
    });

    // A server that is down must never be able to keep someone signed in on a
    // shared ops machine.
    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull();
    expect(screen.getByTestId("authed")).toHaveTextContent("false");
  });
});

describe("useAuth outside a provider", () => {
  it("throws rather than silently handing back an empty session", () => {
    // React logs the thrown error; silence it so the suite output stays clean.
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    expect(() => render(<Probe />)).toThrow(/must be used within an AuthProvider/);
    spy.mockRestore();
  });
});
