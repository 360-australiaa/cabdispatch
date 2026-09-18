import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useDuressAlerts } from "./useDuressAlerts";
import type { DuressEvent } from "./types";

function makeEvent(id: string, status: string): DuressEvent {
  return {
    id,
    tenant_id: "t1",
    vehicle_id: "v1",
    driver_id: "d1",
    trigger: "button",
    status,
    opened_at: "2026-09-08T00:00:00Z",
    closed_at: null,
    gps_stream_ref: "ref",
    audio_ref: null,
    escalation_log_json: {},
    device_id: null,
    source: "tablet",
    device_audio_ref: null,
    device_call_result_json: null,
    created_at: "2026-09-08T00:00:00Z",
    updated_at: "2026-09-08T00:00:00Z",
  };
}

class FakeOscillator {
  frequency = { value: 0 };
  type = "sine";
  connect = vi.fn();
  start = vi.fn();
  stop = vi.fn();
}
class FakeGain {
  gain = {
    value: 0,
    exponentialRampToValueAtTime: vi.fn(),
  };
  connect = vi.fn();
}
class FakeAudioContext {
  currentTime = 0;
  destination = {};
  createOscillator(): FakeOscillator {
    return new FakeOscillator();
  }
  createGain(): FakeGain {
    return new FakeGain();
  }
  resume(): Promise<void> {
    return Promise.resolve();
  }
}

describe("useDuressAlerts", () => {
  let originalAudioContext: typeof window.AudioContext | undefined;
  let originalNotification: typeof window.Notification | undefined;

  beforeEach(() => {
    originalAudioContext = window.AudioContext;
    originalNotification = window.Notification;
    (window as unknown as { AudioContext: unknown }).AudioContext = FakeAudioContext;
  });

  afterEach(() => {
    (window as unknown as { AudioContext: unknown }).AudioContext = originalAudioContext;
    if (originalNotification === undefined) {
      delete (window as unknown as { Notification?: unknown }).Notification;
    } else {
      (window as unknown as { Notification: unknown }).Notification = originalNotification;
    }
    vi.restoreAllMocks();
  });

  it("does not alert for events already open on the first render", () => {
    const { result, rerender } = renderHook(({ events }) => useDuressAlerts(events), {
      initialProps: { events: [makeEvent("a", "open")] as DuressEvent[] | undefined },
    });

    act(() => result.current.arm());
    const oscillatorSpy = vi.spyOn(FakeAudioContext.prototype, "createOscillator");

    // Same event, same data -- nothing new.
    rerender({ events: [makeEvent("a", "open")] });
    expect(oscillatorSpy).not.toHaveBeenCalled();
  });

  it("beeps for a genuinely new open event once armed, but not before arming", () => {
    const { result, rerender } = renderHook(({ events }) => useDuressAlerts(events), {
      initialProps: { events: [makeEvent("a", "open")] as DuressEvent[] | undefined },
    });

    const oscillatorSpy = vi.spyOn(FakeAudioContext.prototype, "createOscillator");

    // Not armed yet -- a new event must not throw, and produces no tone
    // (there is no AudioContext instance to draw a tone from).
    rerender({ events: [makeEvent("a", "open"), makeEvent("b", "open")] });
    expect(oscillatorSpy).not.toHaveBeenCalled();

    act(() => result.current.arm());
    expect(result.current.armed).toBe(true);

    rerender({
      events: [makeEvent("a", "open"), makeEvent("b", "open"), makeEvent("c", "open")],
    });
    expect(oscillatorSpy).toHaveBeenCalledTimes(1);
  });

  it("never alerts for a status change on an already-seen event", () => {
    const { result, rerender } = renderHook(({ events }) => useDuressAlerts(events), {
      initialProps: { events: [makeEvent("a", "open")] as DuressEvent[] | undefined },
    });
    act(() => result.current.arm());
    const oscillatorSpy = vi.spyOn(FakeAudioContext.prototype, "createOscillator");

    rerender({ events: [makeEvent("a", "escalating")] });
    expect(oscillatorSpy).not.toHaveBeenCalled();
  });

  it("reports Notification as unsupported and never throws when the API is absent", () => {
    delete (window as unknown as { Notification?: unknown }).Notification;
    const { result } = renderHook(() => useDuressAlerts([makeEvent("a", "open")]));
    expect(result.current.notifPermission).toBe("unsupported");
    expect(() => act(() => result.current.arm())).not.toThrow();
  });

  // 2026-09-18 field audit: `armed` was plain useState(false) with nothing
  // persisting it, so the audible alarm on a safety desk was OFF again after
  // every single reload and the operator had to re-find a button. Four duress
  // events had sat open 334-436 hours.
  describe("arming survives a reload", () => {
    beforeEach(() => window.localStorage.clear());

    it("starts disarmed on a workstation that has never armed", () => {
      const { result } = renderHook(() => useDuressAlerts(undefined));
      expect(result.current.armed).toBe(false);
    });

    it("persists the choice so the next page load starts armed", () => {
      const first = renderHook(() => useDuressAlerts(undefined));
      act(() => first.result.current.arm());
      expect(first.result.current.armed).toBe(true);

      // A fresh mount is what a reload looks like to this hook.
      first.unmount();
      const second = renderHook(() => useDuressAlerts(undefined));
      expect(second.result.current.armed).toBe(true);
    });

    it("beeps on a new event after a reload without the operator re-arming", () => {
      window.localStorage.setItem("cabdispatch.duress.alertsArmed", "true");
      const { rerender } = renderHook(({ events }) => useDuressAlerts(events), {
        initialProps: { events: [] as DuressEvent[] | undefined },
      });
      // The browser only allows audio after a real gesture; the hook restores
      // the AudioContext on the operator's first interaction of the session.
      act(() => {
        window.dispatchEvent(new Event("pointerdown"));
      });
      const oscillatorSpy = vi.spyOn(FakeAudioContext.prototype, "createOscillator");

      rerender({ events: [makeEvent("new-one", "open")] });

      expect(oscillatorSpy).toHaveBeenCalled();
    });

    it("degrades to disarmed rather than throwing when site data is blocked", () => {
      const getItem = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
        throw new Error("blocked");
      });
      expect(() => renderHook(() => useDuressAlerts(undefined))).not.toThrow();
      getItem.mockRestore();
    });
  });
});
