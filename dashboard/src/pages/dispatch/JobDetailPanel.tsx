import { useEffect, useMemo, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, Send, X } from "lucide-react";
import { Badge, Button, Card, CardContent, CardHeader, CardTitle } from "@/components/ui";
import { useAuth } from "@/lib/auth";
// Cross-page import of the driver lookup already built for Driver
// Engagement (same "first 100 drivers in the tenant" lookup convention as
// `pages/messages/api.ts::listDriverOptions`) -- resolves the raw
// driver_id/accepted_by_driver_id UUIDs this panel used to show verbatim
// into real names, same pattern Audit Log already uses for actor_user_id.
import { useDriverOptionsQuery } from "@/pages/driver-engagement/hooks";
import { cancelJob, getJob, listJobOffers } from "./api";
import {
  formatDateTime,
  formatMoney,
  jobStatusBadgeVariant,
  offerStatusBadgeVariant,
  secondsUntil,
} from "./format";
import { isTerminalJobStatus } from "./types";

/** Mirrors `DELETE /v1/jobs/{id}`'s `_DISPATCH_ROLES` restriction
 * (`app/api/v1/jobs.py`) — without this, a driver-role user viewing this
 * panel would see an enabled "Cancel job" button that always 403s. */
const CANCEL_ROLES = new Set(["owner", "admin", "dispatcher"]);

/**
 * No `WS /v1/jobs/live` equivalent exists for dispatchers — that socket is
 * deliberately driver-scoped (see `app/api/v1/jobs.py::live`'s docstring: it
 * only pushes offers addressed to the connecting user's own id). A dispatch
 * desk watching an in-flight broadcast doesn't need sub-second push, so this
 * just polls both queries every 2s while the job is non-terminal — same
 * "keep it simple" call as everywhere else in this dashboard that isn't
 * safety-critical (only Duress/Live Map use a real WS).
 */
const POLL_MS = 2000;

export function JobDetailPanel({ jobId, onClose }: { jobId: string; onClose: () => void }) {
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const canCancelRole = !!user && CANCEL_ROLES.has(user.role);

  const jobQuery = useQuery({
    queryKey: ["dispatch-job", jobId],
    queryFn: () => getJob(jobId),
    refetchInterval: (query) =>
      query.state.data && isTerminalJobStatus(query.state.data.status) ? false : POLL_MS,
  });

  const offersQuery = useQuery({
    queryKey: ["dispatch-job-offers", jobId],
    queryFn: () => listJobOffers(jobId),
    refetchInterval: (query) =>
      jobQuery.data && isTerminalJobStatus(jobQuery.data.status) ? false : POLL_MS,
    enabled: !!jobQuery.data,
  });

  const cancelMutation = useMutation({
    mutationFn: () => cancelJob(jobId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["dispatch-job", jobId] });
      queryClient.invalidateQueries({ queryKey: ["dispatch-jobs"] });
    },
  });

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
                  <span className="font-normal text-muted-foreground">(auto-refreshing)</span>
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
                    return (
                      <li
                        key={offer.id}
                        className="flex items-center justify-between gap-2 rounded-md border border-border bg-muted/40 px-3 py-2 text-xs"
                      >
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
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>

            {canCancelRole && (
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
