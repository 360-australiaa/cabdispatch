import { http, HttpResponse, delay } from "msw";
import { API, server, startMockServer } from "@/test/server";

/**
 * Tests for the silent refresh-on-401 interceptor in `apiClient.ts`.
 *
 * This is the highest-value target in the dashboard: it is stateful
 * (`refreshPromise` is module-level), concurrent (N requests can fail at
 * once), and it exists because of a real production data-loss incident the
 * module's own comment records -- a 144MB APK upload outlived its access
 * token and lost the whole session mid-upload.
 *
 * `refreshPromise` living at module scope is exactly what makes the
 * shared-promise behaviour work, and also what makes it leak between tests,
 * so every test re-imports the module through `loadApiClient()` after a
 * `vi.resetModules()`. Each test therefore gets a genuinely fresh
 * interceptor, and a test that passes cannot be passing on state another
 * test left behind.
 */

startMockServer();

type ApiClientModule = typeof import("./apiClient");

async function loadApiClient(): Promise<ApiClientModule> {
  vi.resetModules();
  return import("./apiClient");
}

/** Captures `window.location.assign` calls -- jsdom does not implement real navigation. */
let assign: ReturnType<typeof vi.fn>;

beforeEach(() => {
  localStorage.clear();
  assign = vi.fn();
  Object.defineProperty(window, "location", {
    configurable: true,
    value: { pathname: "/live-map", assign },
  });
});

/**
 * The happy-path server: `/v1/auth/refresh` mints token pair #2, and
 * `/v1/trips` only succeeds for a caller presenting the *new* access token.
 * Returns the call counters so a test can assert how many refreshes really
 * happened rather than merely that the end state looks right.
 */
function stubRefreshableApi(options: { refreshDelayMs?: number } = {}) {
  const counts = { refresh: 0, trips: 0 };
  server.use(
    http.post(`${API}/v1/auth/refresh`, async () => {
      counts.refresh += 1;
      if (options.refreshDelayMs) await delay(options.refreshDelayMs);
      return HttpResponse.json({ access_token: "access-2", refresh_token: "refresh-2" });
    }),
    http.get(`${API}/v1/trips`, ({ request }) => {
      counts.trips += 1;
      if (request.headers.get("Authorization") !== "Bearer access-2") {
        return new HttpResponse(null, { status: 401 });
      }
      return HttpResponse.json({ items: ["trip-1"] });
    }),
  );
  return counts;
}

describe("apiClient request interceptor", () => {
  it("attaches the stored access token as a bearer header", async () => {
    const { apiClient, setTokens } = await loadApiClient();
    setTokens("access-1", "refresh-1");
    let seen: string | null = null;
    server.use(
      http.get(`${API}/v1/ping`, ({ request }) => {
        seen = request.headers.get("Authorization");
        return HttpResponse.json({ ok: true });
      }),
    );

    await apiClient.get("/v1/ping");

    expect(seen).toBe("Bearer access-1");
  });

  it("sends no Authorization header when there is no token", async () => {
    const { apiClient } = await loadApiClient();
    let seen: string | null = "unset";
    server.use(
      http.get(`${API}/v1/ping`, ({ request }) => {
        seen = request.headers.get("Authorization");
        return HttpResponse.json({ ok: true });
      }),
    );

    await apiClient.get("/v1/ping");

    expect(seen).toBeNull();
  });
});

describe("apiClient refresh-on-401", () => {
  it("refreshes once and replays the original request with the new token", async () => {
    const { apiClient, setTokens, getAccessToken, getRefreshToken } = await loadApiClient();
    setTokens("access-1", "refresh-1");
    const counts = stubRefreshableApi();

    const res = await apiClient.get("/v1/trips");

    expect(res.data).toEqual({ items: ["trip-1"] });
    expect(counts.refresh).toBe(1);
    // Twice: the original 401 and the replay that succeeded.
    expect(counts.trips).toBe(2);
    // Both halves of the new pair are persisted, not just the access token.
    expect(getAccessToken()).toBe("access-2");
    expect(getRefreshToken()).toBe("refresh-2");
    expect(assign).not.toHaveBeenCalled();
  });

  it("shares one in-flight refresh across concurrent 401s instead of stampeding", async () => {
    const { apiClient, setTokens } = await loadApiClient();
    setTokens("access-1", "refresh-1");
    // The delay guarantees all three 401s land while the first refresh is
    // still in flight -- without the shared promise this is 3 refreshes and
    // three racing writes to localStorage.
    const counts = stubRefreshableApi({ refreshDelayMs: 50 });

    const results = await Promise.all([
      apiClient.get("/v1/trips"),
      apiClient.get("/v1/trips"),
      apiClient.get("/v1/trips"),
    ]);

    expect(counts.refresh).toBe(1);
    expect(results.map((r) => r.data)).toEqual([
      { items: ["trip-1"] },
      { items: ["trip-1"] },
      { items: ["trip-1"] },
    ]);
    // 3 original 401s + 3 replays.
    expect(counts.trips).toBe(6);
  });

  it("allows a fresh refresh after an earlier one has settled", async () => {
    const { apiClient, setTokens } = await loadApiClient();
    setTokens("access-1", "refresh-1");
    const counts = stubRefreshableApi();

    await apiClient.get("/v1/trips");
    // A later, separate expiry must not be swallowed by the cleared promise.
    localStorage.setItem("cd_access_token", "stale-again");
    await apiClient.get("/v1/trips");

    expect(counts.refresh).toBe(2);
  });

  it("gives up and redirects to login when the refresh itself 401s", async () => {
    const { apiClient, setTokens, getAccessToken, getRefreshToken } = await loadApiClient();
    setTokens("access-1", "refresh-1");
    let refreshCalls = 0;
    server.use(
      http.post(`${API}/v1/auth/refresh`, () => {
        refreshCalls += 1;
        return new HttpResponse(null, { status: 401 });
      }),
      http.get(`${API}/v1/trips`, () => new HttpResponse(null, { status: 401 })),
    );

    await expect(apiClient.get("/v1/trips")).rejects.toMatchObject({
      response: { status: 401 },
    });

    expect(refreshCalls).toBe(1);
    // A dead refresh token must not be left behind for the next page load.
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
    expect(assign).toHaveBeenCalledWith("/login");
  });

  it("gives up immediately when there is no refresh token to spend", async () => {
    const { apiClient } = await loadApiClient();
    localStorage.setItem("cd_access_token", "access-1");
    let refreshCalls = 0;
    server.use(
      http.post(`${API}/v1/auth/refresh`, () => {
        refreshCalls += 1;
        return HttpResponse.json({ access_token: "x", refresh_token: "y" });
      }),
      http.get(`${API}/v1/trips`, () => new HttpResponse(null, { status: 401 })),
    );

    await expect(apiClient.get("/v1/trips")).rejects.toBeTruthy();

    expect(refreshCalls).toBe(0);
    expect(assign).toHaveBeenCalledWith("/login");
  });

  it("retries a request only once -- a second 401 ends the session", async () => {
    const { apiClient, setTokens } = await loadApiClient();
    setTokens("access-1", "refresh-1");
    let refreshCalls = 0;
    let tripCalls = 0;
    server.use(
      // The refresh keeps succeeding, so only the `_retried` marker can stop
      // an infinite refresh/replay loop here.
      http.post(`${API}/v1/auth/refresh`, () => {
        refreshCalls += 1;
        return HttpResponse.json({ access_token: "access-2", refresh_token: "refresh-2" });
      }),
      http.get(`${API}/v1/trips`, () => {
        tripCalls += 1;
        return new HttpResponse(null, { status: 401 });
      }),
    );

    await expect(apiClient.get("/v1/trips")).rejects.toBeTruthy();

    expect(refreshCalls).toBe(1);
    expect(tripCalls).toBe(2);
    expect(assign).toHaveBeenCalledWith("/login");
  });
});

describe("apiClient 401s that must not trigger a refresh", () => {
  it.each([
    ["/v1/auth/login", "a wrong password"],
    ["/v1/auth/mfa/login", "a wrong TOTP code"],
  ])("does not refresh when %s 401s (%s)", async (path) => {
    const { apiClient, setTokens } = await loadApiClient();
    setTokens("access-1", "refresh-1");
    let refreshCalls = 0;
    let pathCalls = 0;
    server.use(
      http.post(`${API}/v1/auth/refresh`, () => {
        refreshCalls += 1;
        return HttpResponse.json({ access_token: "access-2", refresh_token: "refresh-2" });
      }),
      http.post(`${API}${path}`, () => {
        pathCalls += 1;
        return new HttpResponse(null, { status: 401 });
      }),
    );

    await expect(apiClient.post(path, {})).rejects.toBeTruthy();

    expect(refreshCalls).toBe(0);
    expect(pathCalls).toBe(1);
    expect(assign).toHaveBeenCalledWith("/login");
  });

  it("does not recurse when a refresh sent through apiClient itself 401s", async () => {
    const { apiClient, setTokens } = await loadApiClient();
    setTokens("access-1", "refresh-1");
    let refreshCalls = 0;
    server.use(
      http.post(`${API}/v1/auth/refresh`, () => {
        refreshCalls += 1;
        return new HttpResponse(null, { status: 401 });
      }),
    );

    await expect(
      apiClient.post("/v1/auth/refresh", { refresh_token: "refresh-1" }),
    ).rejects.toBeTruthy();

    // The recursion case: a 401 from the refresh route is a dead session, so
    // the interceptor must reject it outright. If it instead tried to refresh
    // it would call this same route again and 401 again, forever. Exactly one
    // call -- the caller's own -- proves it did not.
    expect(refreshCalls).toBe(1);
    expect(assign).toHaveBeenCalledWith("/login");
  });

  it("passes non-401 errors straight through without refreshing", async () => {
    const { apiClient, setTokens, getAccessToken } = await loadApiClient();
    setTokens("access-1", "refresh-1");
    let refreshCalls = 0;
    server.use(
      http.post(`${API}/v1/auth/refresh`, () => {
        refreshCalls += 1;
        return HttpResponse.json({ access_token: "access-2", refresh_token: "refresh-2" });
      }),
      http.get(`${API}/v1/trips`, () => new HttpResponse(null, { status: 500 })),
    );

    await expect(apiClient.get("/v1/trips")).rejects.toMatchObject({
      response: { status: 500 },
    });

    expect(refreshCalls).toBe(0);
    // A 500 is not a session problem: the user stays logged in.
    expect(getAccessToken()).toBe("access-1");
    expect(assign).not.toHaveBeenCalled();
  });

  it("does not redirect when the 401 arrives while already on /login", async () => {
    const { apiClient } = await loadApiClient();
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { pathname: "/login", assign },
    });
    server.use(http.post(`${API}/v1/auth/login`, () => new HttpResponse(null, { status: 401 })));

    await expect(apiClient.post("/v1/auth/login", {})).rejects.toBeTruthy();

    expect(assign).not.toHaveBeenCalled();
  });
});

describe("apiClient token storage", () => {
  it("round-trips and clears both tokens", async () => {
    const { setTokens, getAccessToken, getRefreshToken, clearTokens } = await loadApiClient();

    setTokens("a", "r");
    expect(getAccessToken()).toBe("a");
    expect(getRefreshToken()).toBe("r");

    clearTokens();
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });

  it("defaults the base URL to localhost, never a production host", async () => {
    const { API_BASE_URL } = await loadApiClient();
    expect(API_BASE_URL).toBe("http://localhost:8001");
  });
});
