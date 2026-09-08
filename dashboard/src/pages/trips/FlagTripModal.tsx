import { useState } from "react";
import { Button, Input, Modal } from "@/components/ui";
import { errorMessage } from "@/lib/format";
import { useFlagTripMutation, type Trip } from "@/hooks/useTrips";

export interface FlagTripModalProps {
  open: boolean;
  onClose: () => void;
  trip: Trip;
}

/**
 * Trip page "Flag for review" action (dashboard command-centre plan §7) --
 * ported from `TripDetailModal`'s inline flag/unflag section. Only closed
 * trips can be flagged (409 server-side otherwise); clearing an existing
 * flag is a one-click action with no reason required, same as before.
 */
export function FlagTripModal({ open, onClose, trip }: FlagTripModalProps) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const flagMutation = useFlagTripMutation();

  function handleClose() {
    setReason("");
    setError(null);
    onClose();
  }

  async function handleFlag() {
    setError(null);
    try {
      await flagMutation.mutateAsync({ id: trip.id, input: { flagged: true, reason: reason.trim() } });
      handleClose();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title="Flag for dispute review"
      description={`Trip ${trip.id.slice(0, 8)}`}
      footer={
        <>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button onClick={handleFlag} disabled={flagMutation.isPending || reason.trim().length === 0}>
            {flagMutation.isPending ? "Flagging…" : "Confirm flag"}
          </Button>
        </>
      }
    >
      <div className="space-y-2">
        <Input
          placeholder="Reason for dispute (required)"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
        />
        {error && <p className="text-sm text-destructive">{error}</p>}
      </div>
    </Modal>
  );
}
