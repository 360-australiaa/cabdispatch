import { type Vehicle, type VehicleFormValues } from "@/pages/fleet/types";

/**
 * `Vehicle` (the CRUD read shape) -> `VehicleFormValues` (the controlled-input
 * shape `useUpdateVehicle`/`useCreateVehicle` take) -- the exact mapping
 * `VehiclesPanel.openEdit` already uses, lifted out here so the Vehicle
 * page's Edit sheet and its "Set status" quick action can both build a full,
 * valid update payload from whatever the vehicle's current record is,
 * without duplicating this field list a third time.
 */
export function vehicleToFormValues(v: Vehicle): VehicleFormValues {
  return {
    rego: v.rego,
    vin: v.vin ?? "",
    make: v.make ?? "",
    model: v.model ?? "",
    vehicle_class: v.vehicle_class,
    camera_serial: v.camera_serial ?? "",
    tracking_device_id: v.tracking_device_id ?? "",
    meter_device_id: v.meter_device_id ?? "",
    status: v.status,
    registration_expiry: v.registration_expiry ?? "",
    insurance_expiry: v.insurance_expiry ?? "",
  };
}
