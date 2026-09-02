import { useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Loader2, Pencil, Phone, Trash2, X } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Input,
  Modal,
} from "@/components/ui";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth";
import {
  callDuressEvent,
  cancelDuressEvent,
  closeDuressEvent,
  deleteDuressEvent,
  escalateDuressEvent,
  getDuressEvent,
} from "./api";
import { CameraSnapshotPanel } from "./CameraSnapshotPanel";
import { DeviceCallSummary } from "./DeviceCallSummary";
import { DuressAudioPlayer } from "./DuressAudioPlayer";
import { EscalationTimeline } from "./EscalationTimeline";
import { GpsTracePanel } from "./GpsTracePanel";
import { EditEventModal } from "./EditEventModal";
import {
  formatCallResultSummary,
  formatDateTime,
  secondsUntil,
  sourceBadgeVariant,
  sourceLabel,
  statusBadgeVariant,
} from "./format";
import { isTerminalStatus, ESCALATION_STAGES } from "./types";
import { useDuressLiveGps } from "./useDuressLiveGps";

/** Roles allowed to watch the live GPS relay — mirrors the `owner`/`admin`/
 * `dispatcher` restriction noted on `WS /v1/duress/{id}/live` in
 * `app/api/v1/duress.py`. */
const LIVE_GPS_ROLES = new Set(["owner", "admin", "dispatcher"]);

export function EventDetailPanel({
  eventId,
  onClose,
  onDeleted,
}: {
  eventId: string;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const [note, setNote] = useState("");
  const [editOpen, setEditOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);

  const eventQuery = useQuery({
    queryKey: ["duress-event", eventId],
    queryFn: () => getDuressEvent(eventId),
    // A safety desk needs to see a Twilio call-status update or another
    // dispatcher's action land without anyone touching this panel.
    refetchInterval: 8_000,
  });

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ["duress-event", eventId] });
    queryClient.invalidateQueries({ queryKey: ["duress-events"] });
  }

  const cancelMutation = useMutation({
    mutationFn: () => cancelDuressEvent(eventId, { note: note || null }),
    onSuccess: () => {
      setNote("");
      invalidate();
    },
  });
  const escalateMutation = useMutation({
    mutationFn: () => escalateDuressEvent(eventId, { note: note || null }),
    onSuccess: () => {
      setNote("");
      invalidate();
    },
  });
  const closeMutation = useMutation({
    mutationFn: () => closeDuressEvent(eventId, { note: note || null }),
    onSuccess: () => {
      setNote("");
      invalidate();
    },
  });
  const callMutation = useMutation({
    mutationFn: () => callDuressEvent(eventId, { note: note || null }),
    onSuccess: () => {
      setNote("");
      invalidate();
    },
  });
  const deleteMutation = useMutation({
    mutationFn: () => deleteDuressEvent(eventId),
    onSuccess: () => {
      setDeleteOpen(false);
      queryClient.invalidateQueries({ queryKey: ["duress-events"] });
      onDeleted();
    },
  });

  const event = eventQuery.data;
  const canWatchLiveGps = !!user && LIVE_GPS_ROLES.has(user.role);
  // Same owner/admin/dispatcher restriction the backend enforces on
  // cancel/escalate/close/call/edit/delete (app/api/v1/duress.py) — without
  // this, a lower-privileged logged-in user (e.g. driver) would see fully
  // enabled action buttons that always 403, surfaced only as a generic
  // "action failed" message.
  const canManageEvent = canWatchLiveGps;
  const isSelected = !!event;
  const { status: gpsStatus, points: gpsPoints, latestSnapshot } = useDuressLiveGps(
    isSelected ? eventId : null,
    isSelected && canWatchLiveGps,
  );

  const anyActionPending =
    cancelMutation.isPending ||
    escalateMutation.isPending ||
    closeMutation.isPending ||
    callMutation.isPending;

  const canCancel =
    !!event &&
    event.status === "open" &&
    secondsUntil(event.escalation_log_json?.cancel_deadline_at, Date.now()) > 0;
  const canEscalate =
    !!event &&
    (event.status === "open" || event.status === "escalating") &&
    (event.escalation_log_json?.next_stage_index ?? 0) < ESCALATION_STAGES.length;
  const canClose = !!event && !isTerminalStatus(event.status);
  const hasDevice = !!event?.device_id;

  return (
    <Card className="sticky top-4">
      <CardHeader className="flex-row items-start justify-between gap-2 space-y-0">
        <div>
          <CardTitle className="flex items-center gap-2 text-base">
            <AlertTriangle className="h-4 w-4 text-brand-accent" />
            Duress event
            {event && (
              <Badge variant={sourceBadgeVariant(event.source)}>{sourceLabel(event.source)}</Badge>
            )}
          </CardTitle>
          <p className="mt-0.5 font-mono text-xs text-muted-foreground">{eventId}</p>
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
        {eventQuery.isLoading && (
          <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" /> Loading event…
          </div>
        )}

        {eventQuery.isError && (
          <p className="text-sm text-destructive">Failed to load this duress event.</p>
        )}

        {event && (
          <>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
              <Field label="Status">
                <Badge variant={statusBadgeVariant(event.status)}>{event.status}</Badge>
              </Field>
              <Field label="Trigger">
                <span className="capitalize">{event.trigger}</span>
              </Field>
              <Field label="Vehicle">{event.vehicle_id}</Field>
              <Field label="Driver">{event.driver_id}</Field>
              <Field label="Opened">{formatDateTime(event.opened_at)}</Field>
              <Field label="Closed">{formatDateTime(event.closed_at)}</Field>
              <Field label="GPS stream ref">
                <span className="break-all font-mono text-xs">{event.gps_stream_ref}</span>
              </Field>
              <Field label="Audio ref">
                <span className="break-all font-mono text-xs">{event.audio_ref ?? "—"}</span>
              </Field>
              <Field label="Device ID">
                <span className="break-all font-mono text-xs">{event.device_id ?? "—"}</span>
              </Field>
              <Field label="Device audio ref">
                <span className="break-all font-mono text-xs">
                  {event.device_audio_ref ?? "—"}
                </span>
              </Field>
            </dl>

            <div>
              <h3 className="mb-2 text-sm font-medium text-foreground">Escalation timeline</h3>
              <EscalationTimeline event={event} />
            </div>

            {event.device_call_result_json && (
              <div>
                <h3 className="mb-2 text-sm font-medium text-foreground">Device call</h3>
                <DeviceCallSummary result={event.device_call_result_json} />
              </div>
            )}

            {canWatchLiveGps ? (
              <GpsTracePanel status={gpsStatus} points={gpsPoints} />
            ) : (
              <p className="text-xs text-muted-foreground">
                Live GPS relay is restricted to owner/admin/dispatcher roles.
              </p>
            )}

            <DuressAudioPlayer eventId={event.id} audioRef={event.audio_ref} />

            <CameraSnapshotPanel
              eventId={event.id}
              enabled
              refreshSignal={latestSnapshot?.snapshot_id ?? null}
              pollIntervalMs={canWatchLiveGps ? undefined : 4000}
            />

            <div className="flex flex-col gap-2 border-t border-border pt-4">
              <label htmlFor="duress-note" className="text-xs font-medium text-muted-foreground">
                Note (optional, applied to the next action)
              </label>
              <Input
                id="duress-note"
                value={note}
                onChange={(e) => setNote(e.target.value)}
                maxLength={500}
                placeholder="e.g. confirmed false alarm by phone"
                disabled={anyActionPending || !canManageEvent}
              />

              {!canManageEvent && (
                <p className="text-xs text-muted-foreground">
                  Managing a duress event is restricted to owner/admin/dispatcher roles.
                </p>
              )}

              <div className="mt-1 flex flex-wrap gap-2">
                <Button
                  variant="destructive"
                  size="sm"
                  disabled={!canManageEvent || !canCancel || anyActionPending}
                  onClick={() => cancelMutation.mutate()}
                >
                  {cancelMutation.isPending ? "Cancelling…" : "Cancel"}
                </Button>
                <Button
                  variant="accent"
                  size="sm"
                  disabled={!canManageEvent || !canEscalate || anyActionPending}
                  onClick={() => escalateMutation.mutate()}
                >
                  {escalateMutation.isPending ? "Escalating…" : "Escalate"}
                </Button>
                <Button
                  variant="primary"
                  size="sm"
                  disabled={!canManageEvent || !canClose || anyActionPending}
                  onClick={() => closeMutation.mutate()}
                >
                  {closeMutation.isPending ? "Closing…" : "Close / resolve"}
                </Button>
                <span title={!hasDevice ? "No duress device linked to this incident" : undefined}>
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={!canManageEvent || !hasDevice || anyActionPending}
                    onClick={() => callMutation.mutate()}
                  >
                    <Phone className="h-3.5 w-3.5" />
                    {callMutation.isPending ? "Calling…" : "Call the cab"}
                  </Button>
                </span>
              </div>

              {(cancelMutation.isError ||
                escalateMutation.isError ||
                closeMutation.isError ||
                callMutation.isError) && (
                <p className="text-xs text-destructive">
                  Action failed — the event may no longer be in a state that allows it. Refresh
                  and try again.
                </p>
              )}

              {callMutation.isSuccess && callMutation.data && (
                <p
                  className={cn(
                    "flex flex-wrap items-center gap-2 text-xs",
                    callMutation.data.mock ? "text-muted-foreground" : "text-success",
                  )}
                >
                  {!callMutation.data.mock && <CheckCircle2 className="h-3.5 w-3.5 shrink-0" />}
                  {formatCallResultSummary(callMutation.data)}
                  {callMutation.data.mock && <Badge variant="outline">Simulated</Badge>}
                </p>
              )}
            </div>

            {canManageEvent && (
              <div className="flex justify-end gap-2 border-t border-border pt-4">
                <Button variant="outline" size="sm" onClick={() => setEditOpen(true)}>
                  <Pencil className="h-3.5 w-3.5" /> Edit
                </Button>
                <Button variant="outline" size="sm" onClick={() => setDeleteOpen(true)}>
                  <Trash2 className="h-3.5 w-3.5" /> Delete
                </Button>
              </div>
            )}
          </>
        )}
      </CardContent>

      {event && (
        <EditEventModal
          event={event}
          open={editOpen}
          onClose={() => setEditOpen(false)}
          onSaved={() => {
            setEditOpen(false);
            invalidate();
          }}
        />
      )}

      <Modal
        open={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        title="Delete duress event?"
        description="This permanently removes the event record. This cannot be undone."
        footer={
          <>
            <Button variant="outline" onClick={() => setDeleteOpen(false)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={() => deleteMutation.mutate()}
            >
              {deleteMutation.isPending ? "Deleting…" : "Delete"}
            </Button>
          </>
        }
      >
        {deleteMutation.isError && (
          <p className="text-sm text-destructive">Failed to delete this event. Try again.</p>
        )}
      </Modal>
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
