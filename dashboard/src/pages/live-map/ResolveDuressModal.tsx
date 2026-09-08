import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button, Input, Modal } from "@/components/ui";
// Cross-page import of the Duress Desk's own close/cancel actions
// (`POST /v1/duress/{id}/close|cancel`) -- reused here rather than
// duplicated so a resolve from the Live Map behaves identically to one from
// the Duress Desk itself (same endpoint, same note field, same query-key
// invalidation shape).
import { cancelDuressEvent, closeDuressEvent } from "@/pages/duress/api";
import { isTerminalStatus } from "@/pages/duress/types";
import type { DuressEventRead } from "./types";

/**
 * Quick "resolve without leaving Live Map" action. Before this, a dispatcher
 * watching the map had to click through to the Duress Desk (`/duress?event=`)
 * just to close/cancel an event they could already see was a false alarm --
 * a real friction point given this is the page dispatchers actually keep
 * open. Deliberately still one confirmation step (not a bare one-click
 * button on the row) because closing a live duress event is a safety action,
 * not a routine list edit -- same "note + explicit confirm" shape the full
 * Duress Desk detail panel already uses (`pages/duress/EventDetailPanel.tsx`).
 */
export function ResolveDuressModal({
  event,
  onClose,
}: {
  event: DuressEventRead | null;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [note, setNote] = useState("");

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ["live-map", "duress"] });
    // Also refreshes the Duress Desk's own list/detail query keys if that
    // page is open in another tab/mounted elsewhere in this session.
    queryClient.invalidateQueries({ queryKey: ["duress-events"] });
    if (event) queryClient.invalidateQueries({ queryKey: ["duress-event", event.id] });
  }

  const closeMutation = useMutation({
    mutationFn: () => closeDuressEvent(event!.id, { note: note || null }),
    onSuccess: () => {
      setNote("");
      invalidate();
      onClose();
    },
  });
  const cancelMutation = useMutation({
    mutationFn: () => cancelDuressEvent(event!.id, { note: note || null }),
    onSuccess: () => {
      setNote("");
      invalidate();
      onClose();
    },
  });

  const anyPending = closeMutation.isPending || cancelMutation.isPending;
  const anyError = closeMutation.isError || cancelMutation.isError;
  const canAct = !!event && !isTerminalStatus(event.status);

  return (
    <Modal
      open={event != null}
      onClose={() => {
        setNote("");
        closeMutation.reset();
        cancelMutation.reset();
        onClose();
      }}
      title="Resolve duress event"
      description={
        event
          ? `Vehicle ${event.vehicle_id.slice(0, 8)} — opened ${new Date(event.opened_at).toLocaleString("en-AU")}. Closing marks it resolved; cancelling marks it a false alarm. For the full escalation timeline and live GPS trace, open it on the Duress Desk instead.`
          : undefined
      }
      footer={
        <>
          <Button
            variant="outline"
            onClick={() => {
              setNote("");
              onClose();
            }}
          >
            Never mind
          </Button>
          <Button
            variant="secondary"
            disabled={!canAct || anyPending}
            onClick={() => cancelMutation.mutate()}
          >
            {cancelMutation.isPending ? "Cancelling…" : "Cancel (false alarm)"}
          </Button>
          <Button variant="primary" disabled={!canAct || anyPending} onClick={() => closeMutation.mutate()}>
            {closeMutation.isPending ? "Resolving…" : "Resolve / close"}
          </Button>
        </>
      }
    >
      <label htmlFor="resolve-duress-note" className="mb-1 block text-xs font-medium text-muted-foreground">
        Note (optional)
      </label>
      <Input
        id="resolve-duress-note"
        value={note}
        onChange={(e) => setNote(e.target.value)}
        maxLength={500}
        placeholder="e.g. confirmed false alarm by phone"
        disabled={anyPending}
      />
      {anyError && (
        <p className="mt-2 text-xs text-destructive">
          Action failed — the event may no longer be in a state that allows it. Refresh and try again.
        </p>
      )}
    </Modal>
  );
}
