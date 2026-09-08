import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useDuressLiveGps } from "./useDuressLiveGps";

/** Minimal controllable `WebSocket` stand-in — enough for this hook's
 * `onopen`/`onmessage`/`onclose`/`onerror` usage, with a hook into every
 * created instance so a test can drive it directly. */
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

describe("useDuressLiveGps — staleness", () => {
  let originalWebSocket: typeof window.WebSocket;

  beforeEach(() => {
    vi.useFakeTimers();
    originalWebSocket = window.WebSocket;
    FakeWebSocket.instances = [];
    (window as unknown as { WebSocket: unknown }).WebSocket = FakeWebSocket;
  });

  afterEach(() => {
    (window as unknown as { WebSocket: unknown }).WebSocket = originalWebSocket;
    vi.useRealTimers();
  });

  it("is not stale while fixes keep arriving inside the window", () => {
    const { result } = renderHook(() => useDuressLiveGps("event-1", true));
    const socket = FakeWebSocket.instances[0];

    act(() => socket.open());
    expect(result.current.status).toBe("open");

    act(() => socket.send(JSON.stringify({ lat: 1, lng: 2, ts: new Date().toISOString() })));
    expect(result.current.points).toHaveLength(1);
    expect(result.current.stale).toBe(false);

    // Advance less than the staleness threshold.
    act(() => vi.advanceTimersByTime(5_000));
    expect(result.current.stale).toBe(false);
  });

  it("goes stale when the socket stays open but no fix lands, per the frozen-broadcaster failure mode", () => {
    const { result } = renderHook(() => useDuressLiveGps("event-1", true));
    const socket = FakeWebSocket.instances[0];

    act(() => socket.open());
    act(() => socket.send(JSON.stringify({ lat: 1, lng: 2, ts: new Date().toISOString() })));
    expect(result.current.points).toHaveLength(1);

    // Cross the staleness threshold with no further messages -- exactly the
    // single-worker GPSBroadcaster symptom the followup doc describes: the
    // socket itself never closes, it just stops delivering.
    act(() => vi.advanceTimersByTime(25_000));
    expect(result.current.status).toBe("open");
    expect(result.current.stale).toBe(true);
    expect(result.current.lastFixAgeMs).toBeGreaterThan(20_000);
  });

  it("does not call a connected-but-empty feed stale — that is 'waiting', not 'stale'", () => {
    const { result } = renderHook(() => useDuressLiveGps("event-1", true));
    const socket = FakeWebSocket.instances[0];

    act(() => socket.open());
    act(() => vi.advanceTimersByTime(60_000));
    expect(result.current.points).toHaveLength(0);
    expect(result.current.stale).toBe(false);
  });
});
