import { useCallback, useEffect, useRef, useState } from "react";
import type { DuressEvent } from "./types";

/**
 * Audible + desktop-notification alert on a new *open* duress event landing
 * in the polled list (dashboard audit `duress/index.tsx:66-67` — "No
 * sound/desktop notification on a new event despite being a safety desk").
 *
 * Two things this deliberately does NOT do:
 *
 * 1. Request `Notification` permission on mount. A permission prompt firing
 *    the instant this page loads (before the operator has done anything) is
 *    exactly the pattern browsers train people to reflexively dismiss —
 *    which then permanently denies the one alert that matters most. Instead
 *    `arm()` is exposed for a real button click, and the desk still works
 *    (audibly) if the operator never clicks it, or clicks it and says no.
 * 2. Alert for events that were already open the first time this page loaded
 *    this session. Only a genuinely NEW open event — one whose id this
 *    session has not seen before — fires an alert; otherwise reopening this
 *    tab on a fleet with three long-running open incidents would blast three
 *    alerts for nothing new.
 *
 * The beep is synthesised with the Web Audio API (`AudioContext` + a short
 * oscillator tone) rather than shipping an audio asset — no network fetch,
 * no bundle weight, and it only ever plays after `arm()` has run inside a
 * real click handler, which is what lets the browser's autoplay policy allow
 * it to play again later without another gesture.
 */
export function useDuressAlerts(events: DuressEvent[] | undefined) {
  const seenIds = useRef<Set<string> | null>(null);
  const audioCtxRef = useRef<AudioContext | null>(null);
  const [armed, setArmed] = useState(false);
  const [notifPermission, setNotifPermission] = useState<NotificationPermission | "unsupported">(
    typeof window !== "undefined" && "Notification" in window ? Notification.permission : "unsupported",
  );

  const beep = useCallback(() => {
    const ctx = audioCtxRef.current;
    if (!ctx) return;
    const oscillator = ctx.createOscillator();
    const gain = ctx.createGain();
    oscillator.type = "sine";
    oscillator.frequency.value = 880;
    gain.gain.value = 0.0001;
    oscillator.connect(gain);
    gain.connect(ctx.destination);
    const now = ctx.currentTime;
    // Two short pulses rather than one flat tone — reads as an alert, not a
    // UI blip, without needing an audio asset.
    gain.gain.exponentialRampToValueAtTime(0.2, now + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.18);
    gain.gain.exponentialRampToValueAtTime(0.2, now + 0.28);
    gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.44);
    oscillator.start(now);
    oscillator.stop(now + 0.46);
  }, []);

  /** Call from a real click handler — creates/resumes the `AudioContext` (so
   * the beep can play later without another gesture) and, only if the
   * operator hasn't already answered the browser's permission prompt,
   * requests `Notification` permission. Safe to call again; a denial is
   * never re-prompted, per the `Notification` API's own contract. */
  const arm = useCallback(() => {
    setArmed(true);
    if (!audioCtxRef.current) {
      const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
      if (Ctor) audioCtxRef.current = new Ctor();
    }
    audioCtxRef.current?.resume().catch(() => {
      // Some browsers reject resume() outside a "real" gesture context in
      // edge cases (e.g. a synthetic test click) — the alert degrades to
      // silent, never to a thrown error.
    });
    if (typeof window !== "undefined" && "Notification" in window && Notification.permission === "default") {
      Notification.requestPermission()
        .then(setNotifPermission)
        .catch(() => setNotifPermission("denied"));
    }
  }, []);

  useEffect(() => {
    if (!events) return;

    const currentIds = new Set(events.map((e) => e.id));

    if (seenIds.current === null) {
      // First successful fetch this session — record what's already open,
      // alert for none of it.
      seenIds.current = currentIds;
      return;
    }

    const newlyOpen = events.filter((e) => e.status === "open" && !seenIds.current!.has(e.id));
    seenIds.current = new Set([...seenIds.current, ...currentIds]);

    if (newlyOpen.length === 0) return;

    if (armed) beep();

    if (typeof window !== "undefined" && "Notification" in window && Notification.permission === "granted") {
      for (const event of newlyOpen) {
        // Local var so a stale `document` reference isn't captured across
        // the notification's own async lifetime.
        const notification = new Notification("New duress event", {
          body: `Event ${event.id.slice(0, 8)} just opened.`,
          tag: `duress-${event.id}`,
        });
        notification.onclick = () => {
          window.focus();
          notification.close();
        };
      }
    }
  }, [events, armed, beep]);

  return { armed, notifPermission, arm };
}
