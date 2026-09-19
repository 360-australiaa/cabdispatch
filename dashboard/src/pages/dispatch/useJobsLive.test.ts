import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createElement, type ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import { ACCESS_TOKEN_KEY } from "@/lib/apiClient";
import { FRAME_TRUST_WINDOW_MS, useJobsLive } from "./useJobsLive";

/** Same controllable `WebSocket` stand-in as `useDuressLiveGps.test.ts`. */
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

describe("useJobsLive", () => {
  let originalWebSocket: typeof window.WebSocket;
  let client: QueryClient;
  let invalidate: Mock;

  function wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client }, children);
  }

  beforeEach(() => {
    vi.useFakeTimers();
    originalWebSocket = window.WebSocket;
    FakeWebSocket.instances = [];
    (window as unknown as { WebSocket: unknown }).WebSocket = FakeWebSocket;
    localStorage.setItem(ACCESS_TOKEN_KEY, "tok-123");
    client = new QueryClient();
    invalidate = vi.fn().mockResolvedValue(undefined);
    client.invalidateQueries = invalidate as unknown as QueryClient["invalidateQueries"];
  });

  afterEach(() => {
    (window as unknown as { WebSocket: unknown }).WebSocket = originalWebSocket;
    localStorage.clear();
    vi.useRealTimers();
  });

  it("connects to /v1/jobs/live with the access token and reports open", () => {
    const { result } = renderHook(() => useJobsLive(), { wrapper });
    const socket = FakeWebSocket.instances[0];
    expect(socket.url).toBe("ws://localhost:8001/v1/jobs/live?token=tok-123");
    expect(result.current.state).toBe("connecting");

    act(() => socket.open());
    expect(result.current.state).toBe("open");
  });

  it("invalidates the jobs list plus the pushed job's detail and offers on a job_offer frame", () => {
    const { result } = renderHook(() => useJobsLive(), { wrapper });
    const socket = FakeWebSocket.instances[0];
    act(() => socket.open());

    act(() =>
      socket.send(
        JSON.stringify({
          type: "job_offer",
          offer: { id: "o1", job_id: "j1", driver_id: "d1", status: "pending" },
          job: { id: "j1", status: "offered" },
        }),
      ),
    );

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["dispatch-jobs"] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["dispatch-job", "j1"] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["dispatch-job-offers", "j1"] });
    expect(result.current.lastEventAt).not.toBeNull();
  });

  it("still refetches the list on a frame it cannot parse", () => {
    renderHook(() => useJobsLive(), { wrapper });
    const socket = FakeWebSocket.instances[0];
    act(() => socket.open());
    act(() => socket.send("not json"));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["dispatch-jobs"] });
    expect(invalidate).toHaveBeenCalledTimes(1);
  });

  it("ignores the body of a frame type this build does not know, but still refetches the list", () => {
    // The guard: a backend newer than this tab can push a type we have never
    // heard of. We must not mine its body for ids (its shape is unknown) --
    // refetching the list is always correct and cannot break.
    renderHook(() => useJobsLive(), { wrapper });
    const socket = FakeWebSocket.instances[0];
    act(() => socket.open());
    act(() =>
      socket.send(JSON.stringify({ type: "job_cancelled", job: { id: "j9" } })),
    );

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["dispatch-jobs"] });
    expect(invalidate).toHaveBeenCalledTimes(1);
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ["dispatch-job", "j9"] });
  });

  it("reports deliveringFrames only after a frame actually arrives, and expires it", () => {
    // An open socket proves a connection, not delivery: the jobs feed is
    // driver-keyed, so a dispatcher opens it and hears nothing. Dispatch gates
    // its 30 s band on this flag; see FRAME_TRUST_WINDOW_MS.
    const { result } = renderHook(() => useJobsLive(), { wrapper });
    const socket = FakeWebSocket.instances[0];
    act(() => socket.open());
    expect(result.current.state).toBe("open");
    expect(result.current.deliveringFrames).toBe(false);

    act(() => socket.send(JSON.stringify({ type: "job_offer", job: { id: "j1" } })));
    expect(result.current.deliveringFrames).toBe(true);

    act(() => vi.advanceTimersByTime(FRAME_TRUST_WINDOW_MS + 1));
    expect(result.current.deliveringFrames).toBe(false);
  });

  it("does not let an unparseable frame earn the slow poll band", () => {
    // The two halves of this handler have to agree. We distrust junk enough
    // to refuse to read its body; it cannot also be proof the feed is healthy.
    const { result } = renderHook(() => useJobsLive(), { wrapper });
    const socket = FakeWebSocket.instances[0];
    act(() => socket.open());

    act(() => socket.send("not json"));

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["dispatch-jobs"] });
    expect(result.current.deliveringFrames).toBe(false);
  });

  it("does not let a frame type this build does not know earn the slow poll band", () => {
    const { result } = renderHook(() => useJobsLive(), { wrapper });
    const socket = FakeWebSocket.instances[0];
    act(() => socket.open());

    act(() => socket.send(JSON.stringify({ type: "job_cancelled", job: { id: "j9" } })));

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["dispatch-jobs"] });
    expect(result.current.deliveringFrames).toBe(false);
  });

  it("revokes frame trust the moment the socket closes", () => {
    // A socket that dies one second after its first frame must NOT pin the
    // page to the 30 s band for the rest of the 90 s trust window -- gating on
    // `state === "open"` fell back to the fast poll instantly, so anything
    // slower here would be a regression against the code this replaced.
    const { result } = renderHook(() => useJobsLive(), { wrapper });
    const socket = FakeWebSocket.instances[0];
    act(() => socket.open());
    act(() => socket.send(JSON.stringify({ type: "job_offer", job: { id: "j1" } })));
    expect(result.current.deliveringFrames).toBe(true);

    act(() => socket.onclose?.());

    expect(result.current.state).toBe("closed");
    expect(result.current.deliveringFrames).toBe(false);
  });

  it("revokes frame trust the moment the socket errors", () => {
    const { result } = renderHook(() => useJobsLive(), { wrapper });
    const socket = FakeWebSocket.instances[0];
    act(() => socket.open());
    act(() => socket.send(JSON.stringify({ type: "job_offer", job: { id: "j1" } })));
    expect(result.current.deliveringFrames).toBe(true);

    act(() => socket.onerror?.());

    expect(result.current.state).toBe("error");
    expect(result.current.deliveringFrames).toBe(false);
  });

  it("reports error/closed so the page can fall back to polling, and reconnects with backoff", () => {
    const { result } = renderHook(() => useJobsLive(), { wrapper });
    const socket = FakeWebSocket.instances[0];
    act(() => socket.onerror?.());
    expect(result.current.state).toBe("error");

    act(() => socket.onclose?.());
    expect(result.current.state).toBe("closed");
    expect(FakeWebSocket.instances).toHaveLength(1);

    act(() => vi.advanceTimersByTime(1000));
    expect(FakeWebSocket.instances).toHaveLength(2);
  });

  it("does not open a socket when disabled, and closes it on unmount", () => {
    const { result, unmount, rerender } = renderHook(({ enabled }) => useJobsLive(enabled), {
      wrapper,
      initialProps: { enabled: false },
    });
    expect(result.current.state).toBe("disabled");
    expect(FakeWebSocket.instances).toHaveLength(0);

    rerender({ enabled: true });
    expect(FakeWebSocket.instances).toHaveLength(1);
    unmount();
    expect(FakeWebSocket.instances[0].close).toHaveBeenCalled();
  });

  it("reports error without connecting when there is no access token", () => {
    localStorage.clear();
    const { result } = renderHook(() => useJobsLive(), { wrapper });
    expect(result.current.state).toBe("error");
    expect(FakeWebSocket.instances).toHaveLength(0);
  });
});
