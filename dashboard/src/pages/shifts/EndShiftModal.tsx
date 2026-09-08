import { useState, type ReactNode } from "react";
import { Button, Checkbox, Input, Modal } from "@/components/ui";
import { useEndShiftMutation } from "./api";
import { fromDatetimeLocalValue } from "./format";
import type { Shift } from "./types";
import { useResetOnChange } from "@/lib/useResetOnChange";

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

  // Same rule as EditShiftModal: key on the shift's id, not the object, so a
  // background refetch of the shifts list does not reset a figure the
  // operator is part-way through keying in.
  useResetOnChange(open ? (shift?.id ?? null) : null, () => {
    if (!shift) return;
    setEndAt("");
    setPslOwed(shift.psl_owed ?? "0");
    setReconciled(true);
    endMutation.reset();
  });

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
        <Checkbox
          label="Driver's counted cash matches the recomputed cash total"
          checked={reconciled}
          onChange={(e) => setReconciled(e.target.checked)}
        />

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
