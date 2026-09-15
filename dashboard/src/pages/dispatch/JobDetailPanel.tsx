import { useEffect, useMemo, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, Send, X } from "lucide-react";
import { Badge, Button, Card, CardContent, CardHeader, CardTitle } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { errorMessage } from "@/lib/format";
// Cross-page import of the driver lookup already built for Driver
// Engagement (same "first 100 drivers in the tenant" lookup convention as
// `pages/messages/api.ts::listDriverOptions`) -- resolves the raw
// driver_id/accepted_by_driver_id UUIDs this panel used to show verbatim
// into real names, same pattern Audit Log already uses for actor_user_id.
import { useDriverOptionsQuery } from "@/pages/driver-engagement/hooks";
import { acceptJobOffer, cancelJob, declineJobOffer, getJob, listJobOffers } from "./api";
import {
  formatDateTime,
  formatMoney,
  jobStatusBadgeVariant,
  offerStatusBadgeVariant,
  secondsUntil,
} from "./format";
import { isTerminalJobStatus, type JobOffer } from "./types";
import { POLL, pollingQueryOptions, whileActive } from "@/lib/pollIntervals";

/** Mirrors `DELETE /v1/jobs/{id}`'s `_DISPATCH_ROLES` restriction
 * (`app/api/v1/jobs.py`) — without this, a driver-role user viewing this
 * panel would see an enabled "Cancel job" button that always 403s. The
 * same roles may answer an offer on a driver's behalf (admin plan §3). */
const DISPATCH_ROLES = new Set(["owner", "admin", "dispatcher"]);

/**
 * Live updates come from the parent page's `WS /v1/jobs/live` subscription
 * (`useJobsLive`), which invalidates this panel's two queries on every
 * frame. `live` says whether that socket is open: when it is, both queries
 * drop to a slow safety poll (the feed is driver-scoped on an older backend,
 * so an open socket can be quiet); when it is not, they poll at the shared
 * `POLL.REALTIME` band the list uses -- same job, same states, no reason for
 * the panel to run faster than the list it was opened from. Both stop
 * entirely while the tab is hidden (see `lib/pollIntervals.ts`) and once the
 * job is terminal.
 */

export function JobDetailPanel({
  jobId,
  onClose,
  live = false,
}: {
  jobId: string;
  onClose: () => void;
  live?: boolean;
}) {
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const canDispatch = !!user && DISPATCH_ROLES.has(user.role);
  const pollInterval = live ? POLL.ROSTER : POLL.REALTIME;

  const jobQuery = useQuery({
    queryKey: ["dispatch-job", jobId],
    queryFn: () => getJob(jobId),
    ...pollingQueryOptions(
      whileActive(
        pollInterval,
        (query: { state: { data?: { status: string } } }) =>
          !query.state.data || !isTerminalJobStatus(query.state.data.status),
      ),
    ),
  });

  const offersQuery = useQuery({
    queryKey: ["dispatch-job-offers", jobId],
    queryFn: () => listJobOffers(jobId),
    ...pollingQueryOptions(
      !jobQuery.data || !isTerminalJobStatus(jobQuery.data.status) ? pollInterval : false,
    ),
    enabled: !!jobQuery.data,
  });

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ["dispatch-job", jobId] });
    queryClient.invalidateQueries({ queryKey: ["dispatch-job-offers", jobId] });
    queryClient.invalidateQueries({ queryKey: ["dispatch-jobs"] });
  }

  const cancelMutation = useMutation({
    mutationFn: () => cancelJob(jobId),
    onSuccess: invalidate,
  });

  // Accept/decline on the driver's behalf. Keyed by offer so only the row
  // being acted on shows "Accepting…"; the error is kept per offer too.
  const [offerError, setOfferError] = useState<{ offerId: string; message: string } | null>(null);
  const answerMutation = useMutation({
    mutationFn: ({ offer, answer }: { offer: JobOffer; answer: "accept" | "decline" }) =>
      answer === "accept"
        ? acceptJobOffer(jobId, offer.id, { driver_id: offer.driver_id })
        : declineJobOffer(jobId, offer.id, { driver_id: offer.driver_id }),
    onMutate: () => setOfferError(null),
    onSuccess: invalidate,
    onError: (err, { offer }) => setOfferError({ offerId: offer.id, message: errorMessage(err) }),
  });
  const answeringOfferId = answerMutation.isPending ? answerMutation.variables?.offer.id : null;

  const driversQuery = useDriverOptionsQuery();
  const driverNameById = useMemo(() => {
    const map = new Map<string, string>();
    for (const d of driversQuery.data ?? []) map.set(d.id, d.name);
    return map;
  }, [driversQuery.data]);

  // Ticks once a second only while at least one offer is still pending, so
  // the per-offer countdown below actually counts down instead of being a
  // static snapshot of `expires_at` (this drives the previously-unused
  // `secondsUntil` helper -- see format.ts).
  const [now, setNow] = useState(() => Date.now());
  const hasPendingOffer = (offersQuery.data ?? []).some((o) => o.status === "pending");
  useEffect(() => {
    if (!hasPendingOffer) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [hasPendingOffer]);

  const job = jobQuery.data;
  const canCancel = !!job && !isTerminalJobStatus(job.status);

  function driverLabel(driverId: string): string {
    return driverNameById.get(driverId) ?? `${driverId.slice(0, 8)}…`;
  }

  return (
    <Card className="sticky top-4">
      <CardHeader className="flex-row items-start justify-between gap-2 space-y-0">
        <div>
          <CardTitle className="flex items-center gap-2 text-base">
            <Send className="h-4 w-4 text-brand-accent" />
            Job
          </CardTitle>
          <p className="mt-0.5 font-mono text-xs text-muted-foreground">{jobId}</p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close detail panel"
          className="rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <X className="h-4 w-4" />
        </button>
      </CardHeader>

      <CardContent className="flex flex-col gap-5">
        {jobQuery.isLoading && (
          <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" /> Loading job…
          </div>
        )}

        {jobQuery.isError && <p className="text-sm text-destructive">Failed to load this job.</p>}

        {job && (
          <>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
              <Field label="Status">
                <Badge variant={jobStatusBadgeVariant(job.status)}>{job.status}</Badge>
              </Field>
              <Field label="Requested">{formatDateTime(job.requested_at)}</Field>
              <Field label="Fare estimate">
                {formatMoney(job.fare_estimate_low)} – {formatMoney(job.fare_estimate_high)}
              </Field>
              <Field label="Accepted by">
                {job.accepted_by_driver_id ? driverLabel(job.accepted_by_driver_id) : "—"}
              </Field>
              <Field label="Pickup">{job.origin_address}</Field>
              <Field label="Drop-off">{job.dest_address}</Field>
            </dl>

            <div>
              <h3 className="mb-2 text-sm font-medium text-foreground">
                Offers {!isTerminalJobStatus(job.status) && (
                  <span className="font-normal text-muted-foreground">
                    ({live ? "live" : "auto-refreshing"})
                  </span>
                )}
              </h3>
              {offersQuery.isLoading ? (
                <p className="text-xs text-muted-foreground">Loading offers…</p>
              ) : offersQuery.isError ? (
                <p className="text-xs text-destructive">
                  Failed to load this job's offers — check the backend connection and try again.
                </p>
              ) : (offersQuery.data?.length ?? 0) === 0 ? (
                <p className="text-xs text-muted-foreground">
                  No drivers were available when this job was created.
                </p>
              ) : (
                <ul className="flex flex-col gap-2">
                  {offersQuery.data!.map((offer) => {
                    const remaining = offer.status === "pending" ? secondsUntil(offer.expires_at, now) : null;
                    const answering = answeringOfferId === offer.id;
                    return (
                      <li
                        key={offer.id}
                        className="flex flex-col gap-1.5 rounded-md border border-border bg-muted/40 px-3 py-2 text-xs"
                      >
                        <div className="flex items-center justify-between gap-2">
                          <span className="font-medium">{driverLabel(offer.driver_id)}</span>
                          <span className="text-muted-foreground">
                            offered {formatDateTime(offer.offered_at)}
                          </span>
                          {remaining !== null && (
                            <span
                              className={remaining <= 5 ? "font-medium text-destructive" : "text-muted-foreground"}
                              title="Time left before this offer auto-expires"
                            >
                              {remaining}s left
                            </span>
                          )}
                          <Badge variant={offerStatusBadgeVariant(offer.status)}>{offer.status}</Badge>
                        </div>
                        {canDispatch && offer.status === "pending" && (
                          <div className="flex justify-end gap-1.5">
                            <Button
                              variant="primary"
                              size="sm"
                              disabled={answerMutation.isPending}
                              onClick={() => answerMutation.mutate({ offer, answer: "accept" })}
                              title={`Accept this offer for ${driverLabel(offer.driver_id)} — e.g. the driver confirmed by radio`}
                            >
                              {answering && answerMutation.variables?.answer === "accept"
                                ? "Accepting…"
                                : "Accept on behalf"}
                            </Button>
                            <Button
                              variant="outline"
                              size="sm"
                              disabled={answerMutation.isPending}
                              onClick={() => answerMutation.mutate({ offer, answer: "decline" })}
                            >
                              {answering && answerMutation.variables?.answer === "decline"
                                ? "Declining…"
                                : "Decline"}
                            </Button>
                          </div>
                        )}
                        {offerError?.offerId === offer.id && (
                          <p className="text-destructive">
                            {offerError.message}
                            {/not addressed to you/i.test(offerError.message) && (
                              <span className="mt-0.5 block text-muted-foreground">
                                This backend lets only the driver answer their own offer — answering
                                on their behalf needs the backend update that accepts a target driver.
                              </span>
                            )}
                          </p>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>

            {canDispatch && (
              <div className="flex justify-end gap-2 border-t border-border pt-4">
                <Button
                  variant="destructive"
                  size="sm"
                  disabled={!canCancel || cancelMutation.isPending}
                  onClick={() => cancelMutation.mutate()}
                >
                  {cancelMutation.isPending ? "Cancelling…" : "Cancel job"}
                </Button>
              </div>
            )}

            {cancelMutation.isError && (
              <p className="text-xs text-destructive">
                Failed to cancel — the job may already be accepted or terminal.
              </p>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 font-medium text-foreground">{children}</dd>
    </div>
  );
}
