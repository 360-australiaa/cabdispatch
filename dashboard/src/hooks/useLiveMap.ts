import { useEffect, useRef, useState } from "react";
import { API_BASE_URL, getAccessToken } from "@/lib/apiClient";

/**
 * Mirrors backend `PositionRead` (app/schemas/live_ops.py) — exactly the JSON
 * message shape sent over `WS /v1/fleet/live`.
 */
export interface LivePosition {
  vehicle_id: string;
  lat: number;
  lng: number;
  status: string;
  updated_at: string;
}

export type LiveSocketState = "connecting" | "open" | "closed" | "error";

/**
 * Subscribes to `WS /v1/fleet/live` and keeps a live-updating cache of
 * `vehicle_id -> latest published position` for the caller's tenant.
 *
 * Browsers can't set an `Authorization` header on a WS handshake, so the
 * access token travels as `?token=` (see
 * `backend/app/api/v1/live_ops.py::_authenticate_ws`). Positions here are
 * ephemeral/in-memory server-side (nothing is persisted, see
 * `app.services.live_ops` module docstring) — callers should merge this
 * cache *over* their REST-fetched vehicle list (which already carries each
 * vehicle's last-known position as of the REST call) rather than treat it as
 * the sole source of truth, since a vehicle that hasn't broadcast this
 * session simply won't have an entry here yet.
 *
 * Auto-reconnects with capped exponential backoff for as long as the hook
 * stays mounted; tears the socket down on unmount.
 */
export function useFleetLiveSocket() {
  const [positions, setPositions] = useState<Record<string, LivePosition>>({});
  const [connectionState, setConnectionState] = useState<LiveSocketState>("connecting");
  const socketRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    let cancelled = false;
    let retryCount = 0;
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;

    function connect() {
      if (cancelled) return;
      const token = getAccessToken();
      if (!token) {
        setConnectionState("error");
        return;
      }

      const wsBase = API_BASE_URL.replace(/^http/, "ws");
      const socket = new WebSocket(`${wsBase}/v1/fleet/live?token=${encodeURIComponent(token)}`);
      socketRef.current = socket;
      setConnectionState("connecting");

      socket.onopen = () => {
        retryCount = 0;
        setConnectionState("open");
      };

      socket.onmessage = (event: MessageEvent<string>) => {
        try {
          const payload = JSON.parse(event.data) as LivePosition;
          setPositions((prev) => ({ ...prev, [payload.vehicle_id]: payload }));
        } catch {
          // Ignore malformed frames rather than crash the socket handler.
        }
      };

      socket.onerror = () => {
        setConnectionState("error");
      };

      socket.onclose = () => {
        if (cancelled) return;
        setConnectionState("closed");
        const delay = Math.min(15000, 1000 * 2 ** retryCount);
        retryCount += 1;
        reconnectTimer = setTimeout(connect, delay);
      };
    }

    connect();

    return () => {
      cancelled = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      socketRef.current?.close();
    };
  }, []);

  return { positions, connectionState };
}
