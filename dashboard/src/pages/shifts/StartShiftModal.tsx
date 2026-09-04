import { useEffect, useState, type ReactNode } from "react";
import axios from "axios";
import { Button, Input, Modal, Select } from "@/components/ui";
import { useStartShiftMutation } from "./api";
import { fromDatetimeLocalValue, formatDateTime } from "./format";
import type { DriverLite, ShiftConflictDetail, VehicleLite } from "./types";

function conflictDetail(err: unknown): ShiftConflictDetail | null {
  if (!axios.isAxiosError(err) || err.response?.status !== 409) return null;
  const detail = (err.response.data as { detail?: unknown } | undefined)?.detail;
  return detail && typeof detail === "object" && "conflicting_driver_name" in detail
    ? (detail as ShiftConflictDetail)
    : null;
}

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
  const [conflict, setConflict] = useState<ShiftConflictDetail | null>(null);
  const startMutation = useStartShiftMutation();

  useEffect(() => {
    if (open) {
      setDriverId("");
      setVehicleId("");
      setStartAt("");
      setConflict(null);
      startMutation.reset();
    }
    // Reset the form only when the modal opens/closes — `startMutation` is
    // intentionally omitted, it's stable per-mount from useMutation.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const driverOptions = drivers.map((d) => ({ value: d.id, label: d.name }));
  const vehicleOptions = vehicles.map((v) => ({ value: v.id, label: v.rego }));
  const canSubmit = driverId !== "" && vehicleId !== "" && !startMutation.isPending;

  async function handleSubmit(forceHandover = false) {
    if (!canSubmit) return;
    setConflict(null);
    try {
      await startMutation.mutateAsync({
        driver_id: driverId,
        vehicle_id: vehicleId,
        start_at: fromDatetimeLocalValue(startAt) ?? null,
        force_handover: forceHandover,
      });
      onClose();
    } catch (err) {
      // A real handover conflict gets its own confirmation prompt below,
      // naming the driver currently on this vehicle — everything else
      // (unexpected 4xx/5xx) falls through to the generic message.
      setConflict(conflictDetail(err));
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Start shift"
      description="Opens a new shift for a driver/vehicle pair — the same action the driver app performs at clock-on."
      footer={
        conflict ? (
          <>
            <Button variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button onClick={() => handleSubmit(true)} disabled={startMutation.isPending}>
              {startMutation.isPending ? "Ending their shift…" : "End their shift & start mine"}
            </Button>
          </>
        ) : (
          <>
            <Button variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button disabled={!canSubmit} onClick={() => handleSubmit(false)}>
              {startMutation.isPending ? "Starting…" : "Start shift"}
            </Button>
          </>
        )
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

        {conflict ? (
          <p className="text-sm text-amber-600">
            This vehicle already has an open shift for <strong>{conflict.conflicting_driver_name}</strong>,
            started {formatDateTime(conflict.conflicting_shift_start_at)}. Ending their shift
            and starting this one is the real shift-changeover action — only do this once they've
            actually handed the vehicle over.
          </p>
        ) : (
          startMutation.isError && (
            <p className="text-sm text-destructive">Failed to start the shift. Check the details and try again.</p>
          )
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
