/**
 * Every polling interval in the dashboard, named in one place.
 *
 * The audit (`docs/audits/2026-09-08-dashboard-audit.md` §3) found 12 polling
 * sites carrying bare magic numbers from 3 s to 60 s, with no shared
 * constants and no way to see the whole picture — you could not answer "how
 * hard does one open dashboard tab hit the API?" without grepping. Naming
 * them here makes the cost legible and makes a change deliberate.
 *
 * ## The background-tab problem this module also fixes
 *
 * React Query's `refetchInterval` keeps firing while the tab is hidden. A
 * Dispatch tab left open behind another window was hitting `GET /v1/jobs`
 * **every 3 seconds, indefinitely** — nobody looking, a request every three
 * seconds, for as long as the browser stayed open. Multiply by an ops room
 * with a tab per screen and it is a meaningful, entirely wasted share of the
 * backend's load (which, per the backend audit, has no rate limiting to
 * absorb it).
 *
 * `refetchIntervalInBackground: false` is React Query's own answer and is the
 * library default, but it only takes effect when the query does not opt out;
 * spelling it explicitly in `pollingQueryOptions` below means every polling
 * site states its intent rather than inheriting it silently, and a future
 * `true` anywhere becomes a visible, reviewable line. On returning to the tab
 * React Query refetches on window focus, so the operator still sees fresh
 * data immediately — the poll stops while nobody is looking, it does not go
 * stale once they look again.
 *
 * ## What is deliberately NOT here
 *
 * The three WebSocket surfaces (`useLiveMap`, `useMessagesLive`,
 * `useDuressLiveGps`) do not poll and have their own backoff. The audit notes
 * that Duress and Jobs poll despite WS-capable backends — moving them onto
 * the socket is a separate change with its own risk, not a rename.
 */

/**
 * Named polling intervals, in milliseconds.
 *
 * The bands are a judgement about how quickly a stale reading actually costs
 * an operator something, not a measured requirement — flagged honestly here
 * per the repo's convention for undecided defaults.
 */
export const POLL = {
  /** A live incident or an in-flight dispatch offer: seconds matter, and the
   * operator is watching this exact row while it changes. Only ever used
   * *conditionally* — see `whileActive` below. */
  REALTIME: 3_000,

  /** An outstanding request the operator just made and is waiting on: a
   * device Locate/Restart round-trip, where the tablet answers within about a
   * minute and the badge must not sit on "Waiting…" until a manual refresh. */
  PENDING_ACTION: 5_000,

  /** The live map's vehicle positions — the REST fallback under the
   * WebSocket, not the primary path. */
  LIVE_POSITIONS: 5_000,

  /** An open duress incident's detail panel: its timeline and call state. */
  INCIDENT_DETAIL: 8_000,

  /** Duress lists and their snapshot galleries. */
  INCIDENT_LIST: 10_000,

  /** Slow-moving supporting data on a live screen: zones, geofences, the
   * map's vehicle roster. */
  SUPPORTING: 20_000,

  /** Fleet lists and message threads — a stale row here is an inconvenience,
   * not an incident. */
  ROSTER: 30_000,

  /** Background counts and badges that render on every page, including the
   * sidebar's compliance-expiry count. The cheapest band, because this one
   * runs on *every* screen at once. */
  AMBIENT: 60_000,
} as const;

export type PollInterval = (typeof POLL)[keyof typeof POLL];

/**
 * Spread into any `useQuery` that polls.
 *
 * Pairs the interval with `refetchIntervalInBackground: false`, so a hidden
 * tab stops polling. See this module's header for why that is the whole point
 * of routing every polling site through here.
 *
 * ```ts
 * useQuery({ queryKey, queryFn, ...pollingQueryOptions(POLL.ROSTER) });
 * ```
 *
 * `interval` accepts React Query's function form too, for the several sites
 * that poll only while something is actually in flight.
 */
export function pollingQueryOptions<T = unknown>(
  interval: number | false | ((query: T) => number | false),
) {
  return {
    refetchInterval: interval,
    /** The fix. A backgrounded tab must not keep hitting the API. */
    refetchIntervalInBackground: false,
  } as const;
}

/**
 * The conditional-polling shape used by Dispatch, device Locate and the
 * duress panels: poll at `interval` only while `isActive` says there is
 * something in flight, and stop entirely otherwise.
 *
 * This is the more important half of the background fix, and it predates it:
 * an idle fleet with no active job already makes **no** requests at all,
 * rather than polling forever on the chance something appears. Keeping that
 * behaviour expressed as one named helper is what stops the next polling site
 * from quietly reintroducing a flat interval.
 */
export function whileActive<TQuery>(
  interval: number,
  isActive: (query: TQuery) => boolean,
): (query: TQuery) => number | false {
  return (query) => (isActive(query) ? interval : false);
}
