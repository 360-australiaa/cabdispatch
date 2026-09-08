import { setupServer } from "msw/node";

/**
 * One MSW server for the whole test run.
 *
 * Everything the dashboard talks to goes over HTTP through axios, so HTTP is
 * the right seam to fake: tests below drive the *real* `apiClient` module --
 * real interceptors, real shared refresh promise, real `localStorage` writes
 * -- and only the transport is replaced. Stubbing `axios.post` instead would
 * mean the very interceptor logic under test never runs.
 *
 * `onUnhandledRequest: "error"` is deliberate and is the safety net for the
 * program's hard rule that no test may ever touch the real server
 * (`72.61.107.107`): any request a test did not explicitly stub fails the test
 * loudly instead of escaping to the network.
 */
export const server = setupServer();

/** Base URL `apiClient` resolves to under test (no VITE_API_URL is set). */
export const API = "http://localhost:8001";

export function startMockServer() {
  beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
  afterEach(() => server.resetHandlers());
  afterAll(() => server.close());
}
