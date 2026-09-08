import { Copy } from "lucide-react";
import { Button, Modal, useToast } from "@/components/ui";
import { errorMessage, formatDateTimeShort } from "@/lib/format";
import { useGeneratePairingCode } from "@/pages/fleet/api";
import type { Vehicle } from "@/pages/fleet/types";

export interface PairTabletModalProps {
  vehicle: Vehicle | null;
  onClose: () => void;
}

/**
 * The pairing-code flow, ported from `VehiclesPanel`'s own pairing `Modal`
 * (same `useGeneratePairingCode` mutation) so the Vehicle page's "Pair
 * tablet" action doesn't reimplement it.
 */
export function PairTabletModal({ vehicle, onClose }: PairTabletModalProps) {
  const toast = useToast();
  const generatePairingCode = useGeneratePairingCode();

  function handleClose() {
    onClose();
    generatePairingCode.reset();
  }

  return (
    <Modal
      open={vehicle !== null}
      onClose={handleClose}
      title={vehicle ? `Pair a device to ${vehicle.rego}` : "Pair a device"}
      description="Generate a one-time code for the driver's tablet to enter during device registration."
      footer={
        generatePairingCode.data ? (
          <Button variant="outline" onClick={handleClose}>
            Done
          </Button>
        ) : (
          <>
            <Button variant="outline" onClick={handleClose}>
              Cancel
            </Button>
            <Button
              onClick={() =>
                vehicle &&
                generatePairingCode.mutate(vehicle.id, {
                  onSuccess: () => toast.success("Pairing code generated", { description: vehicle.rego }),
                  onError: (err) =>
                    toast.error("Failed to generate pairing code", { description: errorMessage(err) }),
                })
              }
              disabled={generatePairingCode.isPending}
            >
              Generate code
            </Button>
          </>
        )
      }
    >
      {generatePairingCode.data ? (
        <div className="flex items-center justify-between gap-3 rounded-md border border-border bg-muted p-4">
          <div>
            <p className="font-mono text-2xl tracking-widest">{generatePairingCode.data.code}</p>
            <p className="mt-1 text-xs text-muted-foreground">
              Expires {formatDateTimeShort(generatePairingCode.data.expires_at)}
            </p>
          </div>
          <Button
            variant="ghost"
            size="icon"
            aria-label="Copy code"
            onClick={() => navigator.clipboard.writeText(generatePairingCode.data!.code)}
          >
            <Copy className="h-4 w-4" />
          </Button>
        </div>
      ) : generatePairingCode.isError ? (
        <p className="text-sm text-destructive">{errorMessage(generatePairingCode.error)}</p>
      ) : (
        <p className="text-sm text-muted-foreground">
          The device enters this code in the kiosk app to bind itself to the vehicle.
        </p>
      )}
    </Modal>
  );
}
