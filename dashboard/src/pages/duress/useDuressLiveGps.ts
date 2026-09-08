import { useEffect, useRef, useState } from "react";
import { API_BASE_URL, getAccessToken } from "@/lib/apiClient";
import type { DuressGpsPoint, DuressSnapshotNotification } from "./types";

export type LiveGpsStatus = "idle" | "connecting" | "open" | "closed" | "error";

const MAX_POINTS = 200;

/** How long the socket can go without a fresh fix before this hook stops
 * calling the position "live", even though the socket itself is still
 * reporting `open`. See `docs/followups/2026-09-08-redis-pubsub-broadcasters.md`:
 * above one backend worker, `GPSBroadcaster` (`services/duress.py`) is a
 * per-process, in-memory pub/sub, so a dashboard connected to a different
 * worker than the one receiving a driver's fixes gets an `open` socket that
 * simply never delivers another point. A frozen dot presented as current
 * during a live panic event is the exact failure this desk must not commit —
 * this constant is the "how long before that frozen dot has to say so" knob,
 * not a tuning value to relax for cosmetic reasons. */
const STALE_AFTER_MS = 20_000;

/** How often the "now" used for staleness is refreshed while the socket is
 * open. Independent of message arrival — this is what lets a frozen feed's
 * age tick upward on screen even though nothing new is coming in. */
const STALE_CHECK_INTERVAL_MS = 2_000;

function buildWsUrl(eventId: string): string {
  const token = getAccessToken() ?? "";
  const wsBase = API_BASE_URL.replace(/^http/i, "ws");
  return `${wsBase}/v1/duress/${eventId}/live?token=${encodeURIComponent(token)}`;
}

/**
 * Subscribes to `WS /v1/duress/{eventId}/live` — the dashboard-side relay of
 * every GPS fix POSTed to `/v1/duress/{eventId}/gps` for this event (see
 * `app/services/duress.py::GPSBroadcaster`). Points are NOT persisted
 * server-side, so this hook is the only record of the trace while mounted.
 * Connects only while `enabled` is true and `eventId` is set; tears the
 * socket down (and clears accumulated points) whenever either changes.
 */
export function useDuressLiveGps(eventId: string | null, enabled: boolean) {
  const [status, setStatus] = useState<LiveGpsStatus>("idle");
  const [points, setPoints] = useState<DuressGpsPoint[]>([]);
  const [latestSnapshot, setLatestSnapshot] = useState<DuressSnapshotNotification | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [lastPointAt, setLastPointAt] = useState<number | null>(null);
  const wsRef = useRef<WebSocket | null>(null);

  // Ticks `now` forward while the socket is open, independent of whether a
  // new point actually arrives — this is what makes a frozen feed's
  // reported age keep climbing on screen instead of freezing along with it.
  useEffect(() => {
    if (status !== "open") return;
    const interval = window.setInterval(() => setNow(Date.now()), STALE_CHECK_INTERVAL_MS);
    return () => window.clearInterval(interval);
  }, [status]);

  useEffect(() => {
    setPoints([]);
    setLatestSnapshot(null);
    setLastPointAt(null);

    if (!eventId || !enabled) {
      setStatus("idle");
      return;
    }

    setStatus("connecting");
    const ws = new WebSocket(buildWsUrl(eventId));
    wsRef.current = ws;

    ws.onopen = () => {
      setStatus("open");
      setNow(Date.now());
    };
    ws.onclose = () => setStatus("closed");
    ws.onerror = () => setStatus("error");
    ws.onmessage = (event: MessageEvent<string>) => {
      try {
        const parsed = JSON.parse(event.data) as DuressGpsPoint | DuressSnapshotNotification;
        if ("kind" in parsed && parsed.kind === "snapshot") {
          setLatestSnapshot(parsed);
          return;
        }
        const point = parsed as DuressGpsPoint;
        const receivedAt = Date.now();
        setLastPointAt(receivedAt);
        setNow(receivedAt);
        setPoints((prev) => [...prev.slice(-(MAX_POINTS - 1)), point]);
      } catch {
        // Malformed frame — drop it, live feed keeps going.
      }
    };

    return () => {
      ws.close();
      wsRef.current = null;
    };
  }, [eventId, enabled]);

  const latest = points[points.length - 1] ?? null;
  // "Live" means: the socket says open AND a fix has actually landed inside
  // STALE_AFTER_MS of `now`. `now` ticks forward on its own (see the effect
  // above), so a genuinely frozen per-process broadcaster (the followup doc
  // this file cites) surfaces as stale within one check interval of crossing
  // the threshold — it does not require another message to arrive to notice
  // that none has.
  const lastFixAgeMs = lastPointAt != null ? now - lastPointAt : null;
  // No fix yet at all is "waiting for the first one" (GpsTracePanel's own
  // empty state), not "stale" — stale specifically means fixes stopped
  // arriving after the feed was genuinely live.
  const stale = status === "open" && lastFixAgeMs !== null && lastFixAgeMs > STALE_AFTER_MS;

  return { status, points, latest, latestSnapshot, stale, lastFixAgeMs };
}
