import { useState } from "react";
import { Button, Input, Modal, useToast } from "@/components/ui";
import { errorMessage, formatMoney } from "@/lib/format";
import { useCorrectFareMutation, type Trip } from "@/hooks/useTrips";

export interface CorrectFareModalProps {
  open: boolean;
  onClose: () => void;
  trip: Trip;
}

/** Mirrors `TripFareCorrectionRequest.reason`'s `min_length=3`. */
const MIN_REASON_LENGTH = 3;

/**
 * Trip page "Correct fare" action (admin-panel plan §3): an owner/admin
 * setting a closed trip's fare of record to a stated amount, with a reason
 * the server appends to `review_notes`. This is the endpoint the owner had
 * been calling by hand (the 14 Sept T5453 tunnel trip, $32.52 stored for a
 * $59.03 fare). It records a decision; it recomputes nothing, and the
 * review flag is left exactly as it was, so a corrected trip still surfaces
 * on the review list with the whole story in its notes.
 *
 * The result is announced as a toast with both totals, and every trips
 * query is invalidated by the mutation so the page's own figures follow.
 */
export function CorrectFareModal({ open, onClose, trip }: CorrectFareModalProps) {
  const [total, setTotal] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const correctMutation = useCorrectFareMutation();
  const { success } = useToast();

  const totalNumber = Number(total);
  const totalValid = total.trim() !== "" && Number.isFinite(totalNumber) && totalNumber > 0;
  const reasonValid = reason.trim().length >= MIN_REASON_LENGTH;

  function handleClose() {
    setTotal("");
    setReason("");
    setError(null);
    onClose();
  }

  async function handleSubmit() {
    setError(null);
    try {
      const previousTotal = trip.total;
      const corrected = await correctMutation.mutateAsync({
        id: trip.id,
        input: { total: totalNumber.toFixed(2), reason: reason.trim() },
      });
      success(`Fare corrected: ${formatMoney(previousTotal)} → ${formatMoney(corrected.total)}`, {
        description: "The reason has been appended to the trip's review notes.",
      });
      handleClose();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title="Correct fare of record"
      description={`Trip ${trip.id.slice(0, 8)} — stored total ${formatMoney(trip.total)}`}
      footer={
        <>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={correctMutation.isPending || !totalValid || !reasonValid}>
            {correctMutation.isPending ? "Saving…" : "Save correction"}
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <p className="text-sm text-muted-foreground">
          Sets the total the passenger actually paid as this trip's fare of record. Nothing is recomputed;
          the GST component is scaled proportionally and your reason is kept with the trip.
        </p>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="correct-fare-total" className="text-xs font-medium text-muted-foreground">
            New total (AUD, GST-inclusive)
          </label>
          <Input
            id="correct-fare-total"
            type="number"
            inputMode="decimal"
            min="0.01"
            step="0.01"
            placeholder="e.g. 59.03"
            value={total}
            onChange={(e) => setTotal(e.target.value)}
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="correct-fare-reason" className="text-xs font-medium text-muted-foreground">
            Reason (required)
          </label>
          <Input
            id="correct-fare-reason"
            placeholder="Why the stored total is not what the passenger paid"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </div>
        {error && <p className="text-sm text-destructive">{error}</p>}
      </div>
    </Modal>
  );
}
