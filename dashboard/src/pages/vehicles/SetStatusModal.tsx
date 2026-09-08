import { useEffect, useState } from "react";
import { Button, Modal, Select, useToast } from "@/components/ui";
import { errorMessage } from "@/lib/format";
import { useUpdateVehicle } from "@/pages/fleet/api";
import { VEHICLE_STATUS_OPTIONS, type Vehicle, type VehicleStatus } from "@/pages/fleet/types";
import { vehicleToFormValues } from "./vehicleForm";

export interface SetStatusModalProps {
  vehicle: Vehicle | null;
  onClose: () => void;
}

/**
 * A quick "Set status" action, separate from the full Edit sheet -- the
 * plan lists it as its own header action (plan §5). There is no dedicated
 * `PATCH .../status` endpoint; this sends the same full `VehicleUpdate`
 * payload `useUpdateVehicle` always has (see `vehicleToFormValues`), built
 * from the vehicle's current record with only `status` changed, so nothing
 * else on the row is clobbered.
 */
export function SetStatusModal({ vehicle, onClose }: SetStatusModalProps) {
  const toast = useToast();
  const updateVehicle = useUpdateVehicle();
  const [status, setStatus] = useState<VehicleStatus>("active");

  useEffect(() => {
    if (vehicle) setStatus(vehicle.status);
  }, [vehicle]);

  async function submit() {
    if (!vehicle) return;
    try {
      await updateVehicle.mutateAsync({
        id: vehicle.id,
        values: { ...vehicleToFormValues(vehicle), status },
      });
      toast.success("Vehicle status updated", { description: `${vehicle.rego} → ${status}` });
      onClose();
    } catch (err) {
      toast.error("Failed to update status", { description: errorMessage(err) });
    }
  }

  return (
    <Modal
      open={vehicle !== null}
      onClose={onClose}
      title={vehicle ? `Set status for ${vehicle.rego}` : "Set status"}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={submit} disabled={updateVehicle.isPending || !vehicle || status === vehicle.status}>
            Save
          </Button>
        </>
      }
    >
      <label className="mb-1 block text-xs font-medium text-muted-foreground">Vehicle status</label>
      <Select
        options={VEHICLE_STATUS_OPTIONS}
        value={status}
        onChange={(e) => setStatus(e.target.value as VehicleStatus)}
      />
    </Modal>
  );
}
