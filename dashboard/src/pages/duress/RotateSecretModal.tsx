import { useEffect, useState, type FormEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle } from "lucide-react";
import { Button, Input, Modal } from "@/components/ui";
import { rotateDuressDeviceSecret } from "./api";
import { errorMessage } from "./format";
import type { DuressDevice } from "./types";

/**
 * Re-provisioning flow for `POST /v1/duress-devices/{id}/rotate-secret` --
 * replaces the device row's stored (Fernet-encrypted) shared secret.
 *
 * Unlike the fleet Vehicle "pair a device" flow (`VehiclesPanel.tsx`), the
 * SERVER never generates or returns this secret — the operator must already
 * have re-flashed the physical unit's firmware with a new K_dev, and types
 * that same value in here so the server can verify it going forward. There
 * is therefore no "reveal it once, then it's gone" step: the value the
 * operator types is never echoed back by this or any other endpoint, full
 * stop. This is a real security operation (it invalidates the device's
 * CURRENT credential immediately) so it's gated the same
 * confirm-before-you-fire way as the destructive delete modal below it.
 */
export function RotateSecretModal({
  device,
  open,
  onClose,
  onRotated,
}: {
  device: DuressDevice | null;
  open: boolean;
  onClose: () => void;
  onRotated: () => void;
}) {
  const [secret, setSecret] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const queryClient = useQueryClient();

  useEffect(() => {
    if (!open) return;
    setSecret("");
    setConfirmed(false);
  }, [open]);

  const mutation = useMutation({
    mutationFn: () => {
      if (!device) throw new Error("No device selected");
      return rotateDuressDeviceSecret(device.id, { plaintext_secret: secret });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["duress-devices"] });
      onRotated();
    },
  });

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!confirmed) return;
    mutation.mutate();
  }

  function handleClose() {
    mutation.reset();
    onClose();
  }

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title={device ? `Rotate secret for ${device.device_code}` : "Rotate secret"}
      description="Replaces this device's stored shared secret. The physical unit's firmware must already be re-flashed with the SAME value below, or it will fail authentication immediately after this save."
      className="max-w-md"
    >
      <form onSubmit={handleSubmit} className="flex flex-col gap-3">
        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium" htmlFor="rotate-secret-input">
            New shared secret (K_dev)
          </label>
          <Input
            id="rotate-secret-input"
            value={secret}
            onChange={(e) => setSecret(e.target.value)}
            placeholder="16–200 characters, matching the re-flashed firmware"
            minLength={16}
            maxLength={200}
            required
          />
        </div>

        <div className="flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/5 p-3">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" />
          <label className="flex items-start gap-2 text-xs text-foreground">
            <input
              type="checkbox"
              className="mt-0.5 h-4 w-4 rounded border-border"
              checked={confirmed}
              onChange={(e) => setConfirmed(e.target.checked)}
            />
            I understand this immediately invalidates the device's current credential, and I have
            already re-flashed its firmware with this new secret.
          </label>
        </div>

        {mutation.isError && (
          <p className="text-sm text-destructive">{errorMessage(mutation.error)}</p>
        )}

        <div className="mt-2 flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button type="submit" variant="destructive" disabled={!confirmed || mutation.isPending}>
            {mutation.isPending ? "Rotating…" : "Rotate secret"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
