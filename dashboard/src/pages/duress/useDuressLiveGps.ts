import { useEffect, useRef, useState } from "react";
import { API_BASE_URL, getAccessToken } from "@/lib/apiClient";
import type { DuressGpsPoint, DuressSnapshotNotification } from "./types";

export type LiveGpsStatus = "idle" | "connecting" | "open" | "closed" | "error";

const MAX_POINTS = 200;

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
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    setPoints([]);
    setLatestSnapshot(null);

    if (!eventId || !enabled) {
      setStatus("idle");
      return;
    }

    setStatus("connecting");
    const ws = new WebSocket(buildWsUrl(eventId));
    wsRef.current = ws;

    ws.onopen = () => setStatus("open");
    ws.onclose = () => setStatus("closed");
    ws.onerror = () => setStatus("error");
    ws.onmessage = (event: MessageEvent<string>) => {
      try {
        const parsed = JSON.parse(event.data) as DuressGpsPoint | DuressSnapshotNotification;
        if ("kind" in parsed && parsed.kind === "snapshot") {
          setLatestSnapshot(parsed);
          return;
        }
        setPoints((prev) => [...prev.slice(-(MAX_POINTS - 1)), parsed as DuressGpsPoint]);
      } catch {
        // Malformed frame — drop it, live feed keeps going.
      }
    };

    return () => {
      ws.close();
      wsRef.current = null;
    };
  }, [eventId, enabled]);

  return { status, points, latest: points[points.length - 1] ?? null, latestSnapshot };
}
