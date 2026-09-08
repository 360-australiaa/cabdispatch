import { useEffect, useState } from "react";
import { Button, Input, Select, Sheet, useToast } from "@/components/ui";
import { errorMessage } from "@/lib/format";
import { useUpdateVehicle } from "@/pages/fleet/api";
import { EMPTY_VEHICLE_FORM, VEHICLE_CLASS_OPTIONS, VEHICLE_STATUS_OPTIONS, type Vehicle, type VehicleFormValues } from "@/pages/fleet/types";
import { vehicleToFormValues } from "./vehicleForm";

export interface EditVehicleSheetProps {
  vehicle: Vehicle | null;
  open: boolean;
  onClose: () => void;
}

/**
 * The vehicle Edit form, moved from `VehiclesPanel`'s centred `Modal` into a
 * `Sheet` opened from the Vehicle page's own Edit action (plan §5 acceptance:
 * "the edit form becomes a Sheet opened by the Edit action"). Same fields,
 * same `useUpdateVehicle` mutation -- `VehiclesPanel` still opens its own
 * copy of this form for now (this pass does not touch that file), so the two
 * surfaces share the mutation but not yet the markup.
 */
export function EditVehicleSheet({ vehicle, open, onClose }: EditVehicleSheetProps) {
  const toast = useToast();
  const updateVehicle = useUpdateVehicle();
  const [values, setValues] = useState<VehicleFormValues>(EMPTY_VEHICLE_FORM);
  const [formError, setFormError] = useState<string | null>(null);

  // Reset the form to the vehicle's current record every time the sheet is
  // (re)opened for it -- mirrors VehiclesPanel.openEdit, which rebuilds the
  // form from the row rather than trusting whatever the fields last held.
  useEffect(() => {
    if (open && vehicle) {
      setValues(vehicleToFormValues(vehicle));
      setFormError(null);
    }
  }, [open, vehicle]);

  async function submit() {
    if (!vehicle) return;
    setFormError(null);
    try {
      await updateVehicle.mutateAsync({ id: vehicle.id, values });
      toast.success("Vehicle saved", { description: values.rego.trim() || undefined });
      onClose();
    } catch (err) {
      setFormError(errorMessage(err));
      toast.error("Failed to save vehicle", { description: errorMessage(err) });
    }
  }

  return (
    <Sheet
      open={open}
      onClose={onClose}
      title={vehicle ? `Edit ${vehicle.rego}` : "Edit vehicle"}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={submit} disabled={!values.rego.trim() || updateVehicle.isPending}>
            Save changes
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Rego *</label>
          <Input
            value={values.rego}
            onChange={(e) => setValues((f) => ({ ...f, rego: e.target.value }))}
            maxLength={20}
            required
          />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">VIN</label>
          <Input value={values.vin} onChange={(e) => setValues((f) => ({ ...f, vin: e.target.value }))} maxLength={32} />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Make</label>
          <Input value={values.make} onChange={(e) => setValues((f) => ({ ...f, make: e.target.value }))} maxLength={60} />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Model</label>
          <Input value={values.model} onChange={(e) => setValues((f) => ({ ...f, model: e.target.value }))} maxLength={60} />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Class</label>
          <Select
            options={VEHICLE_CLASS_OPTIONS}
            value={values.vehicle_class}
            onChange={(e) => setValues((f) => ({ ...f, vehicle_class: e.target.value as VehicleFormValues["vehicle_class"] }))}
          />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Status</label>
          <Select
            options={VEHICLE_STATUS_OPTIONS}
            value={values.status}
            onChange={(e) => setValues((f) => ({ ...f, status: e.target.value as VehicleFormValues["status"] }))}
          />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Camera serial</label>
          <Input value={values.camera_serial} onChange={(e) => setValues((f) => ({ ...f, camera_serial: e.target.value }))} />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Tracking device ID</label>
          <Input
            value={values.tracking_device_id}
            onChange={(e) => setValues((f) => ({ ...f, tracking_device_id: e.target.value }))}
          />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Meter device ID</label>
          <Input value={values.meter_device_id} onChange={(e) => setValues((f) => ({ ...f, meter_device_id: e.target.value }))} />
        </div>
        <p className="text-xs text-muted-foreground">
          Required under NSW Point to Point Transport regulation to keep this vehicle compliant. Cab
          Dispatch reminds you when these are expiring but does not verify or enforce them. Both are
          optional -- leave blank if unknown for now.
        </p>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Registration expiry</label>
          <Input
            type="date"
            value={values.registration_expiry}
            onChange={(e) => setValues((f) => ({ ...f, registration_expiry: e.target.value }))}
          />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Insurance expiry</label>
          <Input
            type="date"
            value={values.insurance_expiry}
            onChange={(e) => setValues((f) => ({ ...f, insurance_expiry: e.target.value }))}
          />
        </div>
      </div>
      {formError && <p className="mt-3 text-sm text-destructive">{formError}</p>}
    </Sheet>
  );
}
