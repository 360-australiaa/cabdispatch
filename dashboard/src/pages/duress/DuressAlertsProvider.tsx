import { createContext, useContext, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { POLL, pollingQueryOptions } from "@/lib/pollIntervals";
import { useAuth } from "@/lib/auth";
import { listDuressEvents } from "./api";
import { useDuressAlerts } from "./useDuressAlerts";

/**
 * Mounts the duress alarm ONCE for the whole authenticated app.
 *
 * ## Why this exists (2026-09-18 field audit)
 *
 * [useDuressAlerts] was mounted only by `pages/duress/index.tsx`. An operator
 * on any other screen — realistically the Live Map, which is what a dispatcher
 * actually watches — got no sound and no desktop notification when a driver
 * hit the panic button. The alarm only worked if you were already looking at
 * the alarm page, which is the one place you do not need it. The same audit
 * found four duress events open for 334-436 hours.
 *
 * Mounting here, inside [AppShell], means the alarm follows the operator
 * across every route for as long as they are logged in.
 *
 * ## Why a context rather than just calling the hook in both places
 *
 * Two live instances would each hold their own `seenIds` and both fire on the
 * same new event — a double beep and two duplicate notifications. One instance
 * owns the state; the Duress Desk's own "Enable alerts" button reads and
 * drives it through [useDuressAlertsContext].
 *
 * ## Polling band
 *
 * `POLL.INCIDENT_LIST` (10 s), not the 3 s `REALTIME` band the Duress Desk
 * page itself uses. This query runs on EVERY screen for the whole session, so
 * it is the ambient cost of having an alarm at all; the desk keeps its faster
 * poll for the table the operator is actively reading. Ten seconds is the
 * detection latency for a panic button, which is the honest trade until duress
 * events reach a real push channel — today the only duress socket is
 * per-event (`WS /v1/duress/{id}/live`) and requires already knowing the event
 * id, so nothing can push "an emergency was just raised".
 */

type DuressAlertsValue = ReturnType<typeof useDuressAlerts>;

const DuressAlertsContext = createContext<DuressAlertsValue | null>(null);

export function DuressAlertsProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();

  const openEventsQuery = useQuery({
    queryKey: ["duress-alerts-open"],
    queryFn: () => listDuressEvents({ open_only: true, limit: 50 }),
    // Only once authenticated: before login there is no tenant to alarm for,
    // and an unauthenticated poll would just 401 in a loop on the login
    // screen.
    enabled: !!user,
    ...pollingQueryOptions(POLL.INCIDENT_LIST),
  });

  const alerts = useDuressAlerts(openEventsQuery.data?.items);

  return <DuressAlertsContext.Provider value={alerts}>{children}</DuressAlertsContext.Provider>;
}

/** The single app-wide alarm state. Returns null when called outside the
 * provider (e.g. an isolated component test), so a caller can degrade rather
 * than crash. */
export function useDuressAlertsContext(): DuressAlertsValue | null {
  return useContext(DuressAlertsContext);
}
