import { useState } from "react";
import { Button, Modal, useToast } from "@/components/ui";
import { errorMessage } from "@/lib/format";
import { useResetOnChange } from "@/lib/useResetOnChange";
import { useResetDriverPin } from "./api";

/**
 * Confirm-then-reveal dialog for `POST /v1/users/{id}/reset-pin` (dashboard
 * command-centre plan §4's Reset meter PIN action). The new PIN is shown in
 * plaintext exactly once, matching the endpoint's own contract ("returns
 * the new PIN in PLAINTEXT exactly once" -- see
 * `backend/app/api/v1/users.py::reset_pin`) -- there is nowhere else this
 * value can be read back afterwards, so the dialog stays open with a copy
 * button until the operator dismisses it, rather than auto-closing.
 */
export function ResetPinDialog({
  driverId,
  driverName,
  open,
  onClose,
}: {
  driverId: string | null;
  driverName: string;
  open: boolean;
  onClose: () => void;
}) {
  const [pin, setPin] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const mutation = useResetDriverPin();
  const toast = useToast();

  useResetOnChange(open ? driverId : null, () => {
    setPin(null);
    setError(null);
    mutation.reset();
  });

  async function handleConfirm() {
    if (!driverId) return;
    setError(null);
    try {
      const newPin = await mutation.mutateAsync(driverId);
      setPin(newPin);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  function handleCopy() {
    if (!pin) return;
    navigator.clipboard.writeText(pin).then(
      () => toast.success("PIN copied"),
      () => toast.error("Couldn't copy to clipboard"),
    );
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Reset meter PIN"
      description={pin ? undefined : `Generates a new meter login PIN for ${driverName}. The current PIN stops working immediately.`}
      footer={
        pin ? (
          <Button onClick={onClose}>Done</Button>
        ) : (
          <>
            <Button variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button onClick={handleConfirm} disabled={mutation.isPending}>
              {mutation.isPending ? "Resetting…" : "Reset PIN"}
            </Button>
          </>
        )
      }
    >
      {pin ? (
        <div className="flex flex-col gap-3">
          <p className="text-sm text-muted-foreground">
            New PIN for {driverName} — shown once. Give it to the driver now; it cannot be shown again.
          </p>
          <div className="flex items-center gap-2">
            <span className="rounded-md border border-border bg-muted px-3 py-2 font-mono text-lg tracking-widest">
              {pin}
            </span>
            <Button variant="outline" size="sm" onClick={handleCopy}>
              Copy
            </Button>
          </div>
        </div>
      ) : (
        error && <p className="text-sm text-destructive">{error}</p>
      )}
    </Modal>
  );
}
