import { useEffect, useState, type ReactNode } from "react";
import { Button, Input, Modal, Select } from "@/components/ui";
import { useStartShiftMutation } from "./api";
import { fromDatetimeLocalValue } from "./format";
import type { DriverLite, VehicleLite } from "./types";

/** `POST /v1/shifts/start` — opens a new shift. This is the normal
 * dashboard-side "create" path (mirrors how the driver app opens one);
 * `start_at` defaults to server time if left blank. */
export function StartShiftModal({
  open,
  onClose,
  drivers,
  vehicles,
}: {
  open: boolean;
  onClose: () => void;
  drivers: DriverLite[];
  vehicles: VehicleLite[];
}) {
  const [driverId, setDriverId] = useState("");
  const [vehicleId, setVehicleId] = useState("");
  const [startAt, setStartAt] = useState("");
  const startMutation = useStartShiftMutation();

  useEffect(() => {
    if (open) {
      setDriverId("");
      setVehicleId("");
      setStartAt("");
      startMutation.reset();
    }
    // Reset the form only when the modal opens/closes — `startMutation` is
    // intentionally omitted, it's stable per-mount from useMutation.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const driverOptions = drivers.map((d) => ({ value: d.id, label: d.name }));
  const vehicleOptions = vehicles.map((v) => ({ value: v.id, label: v.rego }));
  const canSubmit = driverId !== "" && vehicleId !== "" && !startMutation.isPending;

  async function handleSubmit() {
    if (!canSubmit) return;
    try {
      await startMutation.mutateAsync({
        driver_id: driverId,
        vehicle_id: vehicleId,
        start_at: fromDatetimeLocalValue(startAt) ?? null,
      });
      onClose();
    } catch {
      // surfaced below via startMutation.isError
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Start shift"
      description="Opens a new shift for a driver/vehicle pair — the same action the driver app performs at clock-on."
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={!canSubmit} onClick={handleSubmit}>
            {startMutation.isPending ? "Starting…" : "Start shift"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <Field label="Driver">
          <Select
            options={driverOptions}
            placeholder="Select a driver"
            value={driverId}
            onChange={(e) => setDriverId(e.target.value)}
          />
        </Field>
        <Field label="Vehicle">
          <Select
            options={vehicleOptions}
            placeholder="Select a vehicle"
            value={vehicleId}
            onChange={(e) => setVehicleId(e.target.value)}
          />
        </Field>
        <Field label="Start time (optional — defaults to now)">
          <Input
            type="datetime-local"
            value={startAt}
            onChange={(e) => setStartAt(e.target.value)}
          />
        </Field>

        {startMutation.isError && (
          <p className="text-sm text-destructive">
            Failed to start the shift. The driver may already have an open shift on another
            vehicle. Check and try again.
          </p>
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
