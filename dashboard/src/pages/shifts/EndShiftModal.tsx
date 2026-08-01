import { useEffect, useState, type ReactNode } from "react";
import { Button, Input, Modal } from "@/components/ui";
import { useEndShiftMutation } from "./api";
import { fromDatetimeLocalValue } from "./format";
import type { Shift } from "./types";

/** `POST /v1/shifts/{id}/end` — closes an active shift. `trips_count` /
 * `km_total` / `cash_total` / `card_total` are recomputed server-side from
 * the shift's trips, so this form only collects what the endpoint actually
 * accepts: end time, PSL owed, and whether the driver's counted cash
 * matches the recomputed cash_total. */
export function EndShiftModal({
  shift,
  open,
  onClose,
}: {
  shift: Shift | null;
  open: boolean;
  onClose: () => void;
}) {
  const [endAt, setEndAt] = useState("");
  const [pslOwed, setPslOwed] = useState("0");
  const [reconciled, setReconciled] = useState(true);
  const endMutation = useEndShiftMutation();

  useEffect(() => {
    if (open && shift) {
      setEndAt("");
      setPslOwed(shift.psl_owed ?? "0");
      setReconciled(true);
      endMutation.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, shift]);

  async function handleSubmit() {
    if (!shift) return;
    try {
      await endMutation.mutateAsync({
        id: shift.id,
        body: {
          end_at: fromDatetimeLocalValue(endAt) ?? null,
          psl_owed: pslOwed,
          reconciled,
        },
      });
      onClose();
    } catch {
      // surfaced below via endMutation.isError
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="End shift"
      description={shift ? `Shift ${shift.id.slice(0, 8)} — trips/km/cash/card totals are recomputed from its trips on close.` : undefined}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={endMutation.isPending} onClick={handleSubmit}>
            {endMutation.isPending ? "Ending…" : "End shift"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <Field label="End time (optional — defaults to now)">
          <Input type="datetime-local" value={endAt} onChange={(e) => setEndAt(e.target.value)} />
        </Field>
        <Field label="PSL owed ($)">
          <Input
            type="number"
            min={0}
            step="0.01"
            value={pslOwed}
            onChange={(e) => setPslOwed(e.target.value)}
          />
        </Field>
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            className="h-4 w-4 rounded border-border"
            checked={reconciled}
            onChange={(e) => setReconciled(e.target.checked)}
          />
          Driver's counted cash matches the recomputed cash total
        </label>

        {endMutation.isError && (
          <p className="text-sm text-destructive">Failed to end this shift. Try again.</p>
        )}
      </div>
    </Modal>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label className="text-xs font-medium text-muted-foreground">{label}</label>
      {children}
    </div>
  );
}
