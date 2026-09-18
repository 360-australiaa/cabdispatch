/**
 * The Dispatch poll-policy regression test.
 *
 * 2026-09-19: the page slowed its jobs poll from 3 s to 30 s the moment the
 * `WS /v1/jobs/live` socket reached `open` -- but that socket is driver-keyed
 * (`app/api/v1/jobs.py::live`), so a dispatcher's socket opens and never
 * delivers a frame, and the page went TEN TIMES staler in exchange for
 * nothing. These tests pin the rule that replaced it: the slow band is earned
 * by a DELIVERED FRAME, never by a connection.
 *
 * They drive the real `useJobsLive` over a fake `WebSocket` and count actual
 * `listJobs` calls on React Query's timers, rather than asserting on an
 * internal flag -- so they keep holding when the backend fan-out is widened
 * to dispatchers (frames then arrive, and the slow band is allowed again).
 */
import { render, screen, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ACCESS_TOKEN_KEY } from "@/lib/apiClient";

vi.mock("./api", () => ({
  listJobs: vi.fn(),
  createJob: vi.fn(),
}));
vi.mock("@/pages/driver-engagement/hooks", () => ({
  useDriverOptionsQuery: () => ({ data: [] }),
}));
vi.mock("./JobDetailPanel", () => ({ JobDetailPanel: () => null }));
vi.mock("./CreateJobModal", () => ({ CreateJobModal: () => null }));

import { listJobs } from "./api";
import DispatchPage from "./index";

/** Same controllable `WebSocket` stand-in as `useJobsLive.test.ts`. */
class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  close = vi.fn();

  constructor(public url: string) {
    FakeWebSocket.instances.push(this);
  }

  open() {
    this.onopen?.();
  }

  send(data: string) {
    this.onmessage?.({ data });
  }
}

/** One in-flight job, so the `whileActive` gate actually polls. */
const ACTIVE_JOB = {
  id: "j1",
  status: "offered",
  origin_address: "Wynyard",
  dest_address: "Bondi",
  fare_estimate_low: "20.00",
  fare_estimate_high: "30.00",
  accepted_by_driver_id: null,
  requested_at: "2026-09-19T01:00:00Z",
};

describe("Dispatch poll policy", () => {
  let originalWebSocket: typeof window.WebSocket;
  let client: QueryClient;

  function wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    );
  }

  beforeEach(() => {
    vi.useFakeTimers();
    originalWebSocket = window.WebSocket;
    FakeWebSocket.instances = [];
    (window as unknown as { WebSocket: unknown }).WebSocket = FakeWebSocket;
    localStorage.setItem(ACCESS_TOKEN_KEY, "tok-123");
    vi.mocked(listJobs).mockResolvedValue({ items: [ACTIVE_JOB], total: 1 } as never);
    client = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: Infinity } },
    });
  });

  afterEach(() => {
    (window as unknown as { WebSocket: unknown }).WebSocket = originalWebSocket;
    localStorage.clear();
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  /** Let the pending `listJobs` promise settle under fake timers. */
  async function settle() {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
      await vi.advanceTimersByTimeAsync(0);
    });
  }

  async function advance(ms: number) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ms);
    });
  }

  it("keeps the fast 3 s poll while the socket is open but has delivered no frame", async () => {
    render(<DispatchPage />, { wrapper });
    await settle();
    // The row must be on screen, or the `whileActive` gate would be off for a
    // reason that has nothing to do with the socket and this test would pass
    // against any implementation.
    expect(screen.getByText("Wynyard")).toBeInTheDocument();

    const socket = FakeWebSocket.instances[0];
    act(() => socket.open());

    const before = vi.mocked(listJobs).mock.calls.length;
    await advance(10_000);
    const polls = vi.mocked(listJobs).mock.calls.length - before;

    // 10 s at the 3 s band is ~3 refetches; at the 30 s band it is 0.
    expect(polls).toBeGreaterThanOrEqual(2);
  });

  it("slows to the 30 s band once a frame has actually been delivered", async () => {
    render(<DispatchPage />, { wrapper });
    await settle();
    expect(screen.getByText("Wynyard")).toBeInTheDocument();

    const socket = FakeWebSocket.instances[0];
    act(() => socket.open());
    act(() =>
      socket.send(JSON.stringify({ type: "job_offer", job: { id: "j1", status: "offered" } })),
    );
    await settle();

    const before = vi.mocked(listJobs).mock.calls.length;
    await advance(10_000);
    const polls = vi.mocked(listJobs).mock.calls.length - before;

    expect(polls).toBe(0);
  });

  it("returns to the fast band when a delivering socket goes silent", async () => {
    render(<DispatchPage />, { wrapper });
    await settle();
    expect(screen.getByText("Wynyard")).toBeInTheDocument();

    const socket = FakeWebSocket.instances[0];
    act(() => socket.open());
    act(() => socket.send(JSON.stringify({ type: "job_offer", job: { id: "j1" } })));
    await settle();

    // Past FRAME_TRUST_WINDOW_MS (90 s) with no further frame: a half-open
    // socket must not pin the page to the slow band forever.
    await advance(95_000);
    const before = vi.mocked(listJobs).mock.calls.length;
    await advance(10_000);
    const polls = vi.mocked(listJobs).mock.calls.length - before;

    expect(polls).toBeGreaterThanOrEqual(2);
  });
});
