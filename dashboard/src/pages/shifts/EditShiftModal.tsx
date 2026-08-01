import { useEffect, useState, type ReactNode } from "react";
import { Button, Input, Modal, Select } from "@/components/ui";
import { useUpdateShiftMutation } from "./api";
import { fromDatetimeLocalValue, toDatetimeLocalValue } from "./format";
import type { DriverLite, Shift, VehicleLite } from "./types";

/** `PATCH /v1/shifts/{id}` — admin corrections: reassign driver/vehicle, fix
 * a mis-keyed reconciliation figure, or flip the `reconciled` flag by hand.
 * Every field is independently optional server-side; this form always sends
 * the full current set so nothing is accidentally cleared. */
export function EditShiftModal({
  shift,
  open,
  onClose,
  drivers,
  vehicles,
}: {
  shift: Shift | null;
  open: boolean;
  onClose: () => void;
  drivers: DriverLite[];
  vehicles: VehicleLite[];
}) {
  const [driverId, setDriverId] = useState("");
  const [vehicleId, setVehicleId] = useState("");
  const [startAt, setStartAt] = useState("");
  const [endAt, setEndAt] = useState("");
  const [tripsCount, setTripsCount] = useState("0");
  const [kmTotal, setKmTotal] = useState("0");
  const [cashTotal, setCashTotal] = useState("0");
  const [cardTotal, setCardTotal] = useState("0");
  const [pslOwed, setPslOwed] = useState("0");
  const [reconciled, setReconciled] = useState(false);

  const updateMutation = useUpdateShiftMutation();

  useEffect(() => {
    if (open && shift) {
      setDriverId(shift.driver_id);
      setVehicleId(shift.vehicle_id);
      setStartAt(toDatetimeLocalValue(shift.start_at));
      setEndAt(toDatetimeLocalValue(shift.end_at));
      setTripsCount(String(shift.trips_count));
      setKmTotal(shift.km_total);
      setCashTotal(shift.cash_total);
      setCardTotal(shift.card_total);
      setPslOwed(shift.psl_owed);
      setReconciled(shift.reconciled);
      updateMutation.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, shift]);

  const driverOptions = drivers.map((d) => ({ value: d.id, label: d.name }));
  const vehicleOptions = vehicles.map((v) => ({ value: v.id, label: v.rego }));
  const canSubmit = driverId !== "" && vehicleId !== "" && startAt !== "" && !updateMutation.isPending;

  async function handleSubmit() {
    if (!shift || !canSubmit) return;
    try {
      await updateMutation.mutateAsync({
        id: shift.id,
        body: {
          driver_id: driverId,
          vehicle_id: vehicleId,
          start_at: fromDatetimeLocalValue(startAt) ?? null,
          end_at: endAt ? (fromDatetimeLocalValue(endAt) ?? null) : null,
          trips_count: Number(tripsCount) || 0,
          km_total: kmTotal,
          cash_total: cashTotal,
          card_total: cardTotal,
          psl_owed: pslOwed,
          reconciled,
        },
      });
      onClose();
    } catch {
      // surfaced below via updateMutation.isError
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Edit shift"
      description={shift ? `Shift ${shift.id.slice(0, 8)}` : undefined}
      className="max-w-xl"
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={!canSubmit} onClick={handleSubmit}>
            {updateMutation.isPending ? "Saving…" : "Save changes"}
          </Button>
        </>
      }
    >
      {shift && (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
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
          <Field label="Start time">
            <Input
              type="datetime-local"
              value={startAt}
              onChange={(e) => setStartAt(e.target.value)}
            />
          </Field>
          <Field label="End time (blank = still active)">
            <Input type="datetime-local" value={endAt} onChange={(e) => setEndAt(e.target.value)} />
          </Field>
          <Field label="Trips count">
            <Input
              type="number"
              min={0}
              step={1}
              value={tripsCount}
              onChange={(e) => setTripsCount(e.target.value)}
            />
          </Field>
          <Field label="Distance (km)">
            <Input
              type="number"
              min={0}
              step="0.1"
              value={kmTotal}
              onChange={(e) => setKmTotal(e.target.value)}
            />
          </Field>
          <Field label="Cash total ($)">
            <Input
              type="number"
              min={0}
              step="0.01"
              value={cashTotal}
              onChange={(e) => setCashTotal(e.target.value)}
            />
          </Field>
          <Field label="Card total ($)">
            <Input
              type="number"
              min={0}
              step="0.01"
              value={cardTotal}
              onChange={(e) => setCardTotal(e.target.value)}
            />
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
          <label className="mt-6 flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              className="h-4 w-4 rounded border-border"
              checked={reconciled}
              onChange={(e) => setReconciled(e.target.checked)}
            />
            Reconciled
          </label>

          {updateMutation.isError && (
            <p className="sm:col-span-2 text-sm text-destructive">
              Failed to save changes to this shift. Try again.
            </p>
          )}
        </div>
      )}
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
