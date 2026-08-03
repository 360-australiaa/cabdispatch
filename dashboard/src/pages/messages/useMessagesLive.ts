import { useEffect, useRef, useState } from "react";
import { API_BASE_URL, getAccessToken } from "@/lib/apiClient";
import type { Message } from "./types";

export type MessagesLiveStatus = "idle" | "connecting" | "open" | "closed" | "error";

function buildWsUrl(driverId: string): string {
  const token = getAccessToken() ?? "";
  const wsBase = API_BASE_URL.replace(/^http/i, "ws");
  return `${wsBase}/v1/messages/live?driver_id=${encodeURIComponent(driverId)}&token=${encodeURIComponent(token)}`;
}

/**
 * Subscribes to `WS /v1/messages/live?driver_id=` — the real-time relay of
 * every message POSTed to `/v1/messages` for this driver's thread (see
 * `app.services.messages.MessageBroadcaster`). Same token-in-query-param WS
 * auth and connect-only-while-open lifecycle as
 * `src/pages/duress/useDuressLiveGps.ts` (browsers can't set custom headers
 * on a WS handshake, so the access token rides along as `?token=`).
 *
 * Only the most recently received message is exposed — the caller (the
 * thread panel) is responsible for merging it into its own message list
 * against the initial `GET /v1/messages?driver_id=` history and de-duping by
 * id (the sender's own POST response arrives via its mutation response
 * first; the WS echo of that same message follows shortly after).
 */
export function useMessagesLive(driverId: string | null, enabled: boolean) {
  const [status, setStatus] = useState<MessagesLiveStatus>("idle");
  const [lastMessage, setLastMessage] = useState<Message | null>(null);
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    setLastMessage(null);

    if (!driverId || !enabled) {
      setStatus("idle");
      return;
    }

    setStatus("connecting");
    const ws = new WebSocket(buildWsUrl(driverId));
    wsRef.current = ws;

    ws.onopen = () => setStatus("open");
    ws.onclose = () => setStatus("closed");
    ws.onerror = () => setStatus("error");
    ws.onmessage = (event: MessageEvent<string>) => {
      try {
        const message = JSON.parse(event.data) as Message;
        setLastMessage(message);
      } catch {
        // Malformed frame — drop it, live feed keeps going.
      }
    };

    return () => {
      ws.close();
      wsRef.current = null;
    };
  }, [driverId, enabled]);

  return { status, lastMessage };
}
