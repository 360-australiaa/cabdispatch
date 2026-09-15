import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { API_BASE_URL, getAccessToken } from "@/lib/apiClient";
import type { Job, JobOffer } from "./types";

export type JobsLiveState = "disabled" | "connecting" | "open" | "closed" | "error";

/** One frame of `WS /v1/jobs/live` -- `app.schemas.jobs.JobOfferPushEvent`:
 * a `job_offer` event carrying the offer and its job as pushed by
 * `create_job_and_broadcast`. Anything else on the socket is treated as
 * "something changed" and refetched, not dropped. */
export interface JobOfferPushEvent {
  type: "job_offer" | string;
  offer?: JobOffer;
  job?: Job;
}

/**
 * Subscribes the Dispatch page to `WS /v1/jobs/live` and turns every frame
 * into a React Query invalidation of the jobs list, the pushed job's detail
 * and its offers -- the dashboard-side half of the loop the admin plan §3
 * asked for ("the page polls; switch to the socket").
 *
 * The socket is a notification channel here, not the source of truth: a
 * frame says *which* job moved and the REST queries refetch the real rows,
 * so the list, the detail panel and the offers stay consistent with each
 * other and with what a plain reload would show. That keeps the failure
 * modes simple -- a missed or malformed frame costs at most one poll
 * interval, never a wrong row.
 *
 * Honest caveat, kept in `index.tsx`'s polling policy: the backend's feed is
 * driver-scoped today (it pushes offers addressed to the *connecting* user's
 * id, `app/api/v1/jobs.py::live`), so a dispatcher's socket may be open and
 * quiet. The parallel backend change widens it for owner/admin/dispatcher;
 * until then the page keeps a slow safety poll while the socket is open,
 * and falls back to the previous fast poll whenever it is not.
 *
 * Auth travels as `?token=` (browsers cannot set a header on the WS
 * handshake), same as `useFleetLiveSocket`. Reconnects with capped
 * exponential backoff while mounted; tears down on unmount or `enabled`
 * going false.
 */
export function useJobsLive(enabled = true) {
  const queryClient = useQueryClient();
  const [state, setState] = useState<JobsLiveState>(enabled ? "connecting" : "disabled");
  const [lastEventAt, setLastEventAt] = useState<number | null>(null);
  const socketRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    if (!enabled) {
      setState("disabled");
      return;
    }

    let cancelled = false;
    let retryCount = 0;
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;

    function connect() {
      if (cancelled) return;
      const token = getAccessToken();
      if (!token) {
        setState("error");
        return;
      }

      const wsBase = API_BASE_URL.replace(/^http/, "ws");
      const socket = new WebSocket(`${wsBase}/v1/jobs/live?token=${encodeURIComponent(token)}`);
      socketRef.current = socket;
      setState("connecting");

      socket.onopen = () => {
        retryCount = 0;
        setState("open");
      };

      socket.onmessage = (event: MessageEvent<string>) => {
        let payload: JobOfferPushEvent | null = null;
        try {
          payload = JSON.parse(event.data) as JobOfferPushEvent;
        } catch {
          // A malformed frame still means the feed is alive; refetch the
          // list rather than crash the handler or ignore a real change.
        }
        setLastEventAt(Date.now());
        queryClient.invalidateQueries({ queryKey: ["dispatch-jobs"] });
        const jobId = payload?.job?.id ?? payload?.offer?.job_id;
        if (jobId) {
          queryClient.invalidateQueries({ queryKey: ["dispatch-job", jobId] });
          queryClient.invalidateQueries({ queryKey: ["dispatch-job-offers", jobId] });
        }
      };

      socket.onerror = () => {
        setState("error");
      };

      socket.onclose = () => {
        if (cancelled) return;
        setState("closed");
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
      socketRef.current = null;
    };
  }, [enabled, queryClient]);

  return { state, lastEventAt };
}
