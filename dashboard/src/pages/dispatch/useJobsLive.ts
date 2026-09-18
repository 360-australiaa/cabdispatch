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
 * The frame types this build knows how to interpret.
 *
 * As of 2026-09-19 the backend publishes exactly one: `app/services/jobs.py`
 * does `job_offer_broadcaster.publish({"type": "job_offer", ...})` and
 * nothing else, and `app/schemas/jobs.py` types it `Literal["job_offer"]`.
 *
 * A frame whose type is NOT in this set is deliberately not mined for
 * `job`/`offer` ids: we refetch the jobs list (cheap, and always the right
 * answer for "something changed") and ignore the rest of the body. That is
 * the guard -- a backend that starts pushing, say, `job_cancelled` with a
 * different shape cannot make this handler read fields that are not there,
 * invalidate a query key built from the wrong id, or throw inside
 * `onmessage` and kill the socket's message pump. Dashboard tabs are not
 * redeployed in lockstep with the API, so "a frame type this build has never
 * heard of" is an expected event, not a bug.
 */
const KNOWN_FRAME_TYPES = new Set<string>(["job_offer"]);

/**
 * How long one delivered frame is taken as proof that this socket actually
 * feeds this user, before the page falls back to the fast poll band.
 *
 * WHY revert at all -- the decision this change had to make explicitly: the
 * alternative ("one frame ever seen, stay slow forever") is wrong for the two
 * failure modes that really happen here. A socket can go half-open (the
 * connection survives, `onclose` never fires, frames simply stop), and the
 * backend can be redeployed mid-shift with a narrower fan-out than the one
 * that sent our last frame. In both cases `state === "open"` and a non-null
 * `lastEventAt` would pin Dispatch to the 30 s band while nothing at all is
 * arriving -- the same 10x staleness this change exists to remove, only
 * harder to notice. Reverting costs at most a few extra `GET /v1/jobs` on a
 * healthy-but-quiet feed; not reverting costs an operator up to half a minute
 * of not seeing a live job move. Freshness wins.
 *
 * 90 s is deliberately three slow ticks: a feed that is merely quiet because
 * nothing is happening is not punished for it (and the Dispatch poll is
 * `whileActive`-gated, so it does not run at all with no live job), while a
 * feed that has genuinely died is caught quickly.
 */
export const FRAME_TRUST_WINDOW_MS = 90_000;

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
 * ## `deliveringFrames` -- why the page must not trust an open socket
 *
 * 2026-09-19: Dispatch dropped its poll from 3 s to 30 s the moment this
 * socket reached `open`. The backend feed is driver-scoped
 * (`app/api/v1/jobs.py::live` subscribes the *connecting* user's id), so a
 * dispatcher's socket opens, authenticates, and then never delivers a single
 * frame -- and the page became TEN TIMES staler as a direct result of a
 * useless socket connecting. An open socket proves a TCP connection; only a
 * received frame proves this user is on the fan-out.
 *
 * So the hook reports `deliveringFrames`, true only while a frame has arrived
 * within `FRAME_TRUST_WINDOW_MS`, and the page gates its slow band on that
 * instead of on `state === "open"`.
 *
 * This is a PERMANENT SAFETY NET, not a workaround for today's narrow feed.
 * The parallel backend change widens the fan-out to owner/admin/dispatcher;
 * when it lands, dispatchers start receiving frames and this simply allows
 * the slow band again -- no dashboard edit required. It must stay afterwards,
 * because "the socket opened" will never be evidence that frames are flowing:
 * a role change, a half-open connection, a proxy that holds the socket up
 * while dropping traffic, or a future narrowing of the fan-out all reproduce
 * the identical defect. The rule encoded here is the durable one: slow down
 * only on evidence of delivery.
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
  const [deliveringFrames, setDeliveringFrames] = useState(false);
  const socketRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    if (!enabled) {
      setState("disabled");
      setDeliveringFrames(false);
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
        // A frame arrived on *this* user's socket. That -- not the socket
        // being open -- is what earns the slow poll band; see
        // `FRAME_TRUST_WINDOW_MS`.
        setLastEventAt(Date.now());
        setDeliveringFrames(true);
        queryClient.invalidateQueries({ queryKey: ["dispatch-jobs"] });

        // Unknown or unparseable frame: the list refetch above already covers
        // "something changed", so stop here rather than read a body whose
        // shape this build does not know.
        if (!payload || !KNOWN_FRAME_TYPES.has(payload.type)) return;

        const jobId = payload.job?.id ?? payload.offer?.job_id;
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

  // Expire the trust a frame bought us. Nothing else re-renders this hook
  // when frames merely stop arriving, so this timer is what returns the page
  // to the fast band on a silent-but-open socket.
  useEffect(() => {
    if (lastEventAt === null) return;
    const elapsed = Date.now() - lastEventAt;
    const timer = setTimeout(
      () => setDeliveringFrames(false),
      Math.max(0, FRAME_TRUST_WINDOW_MS - elapsed),
    );
    return () => clearTimeout(timer);
  }, [lastEventAt]);

  return { state, lastEventAt, deliveringFrames };
}
