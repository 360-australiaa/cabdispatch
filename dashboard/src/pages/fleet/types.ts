/**
 * Types mirroring the backend `/v1/fleet` (vehicles + devices) and `/v1/drivers`
 * schemas — see shared/openapi.json for the source of truth.
 */

export type VehicleClass = "standard" | "premium" | "maxi" | "wat";
export type VehicleStatus = "active" | "maintenance" | "suspended" | "retired";

export const VEHICLE_CLASS_OPTIONS: { value: VehicleClass; label: string }[] = [
  { value: "standard", label: "Standard" },
  { value: "premium", label: "Premium" },
  { value: "maxi", label: "Maxi" },
  { value: "wat", label: "WAT (wheelchair accessible)" },
];

export const VEHICLE_STATUS_OPTIONS: { value: VehicleStatus; label: string }[] = [
  { value: "active", label: "Active" },
  { value: "maintenance", label: "Maintenance" },
  { value: "suspended", label: "Suspended" },
  { value: "retired", label: "Retired" },
];

/** `VehicleRead` */
export interface Vehicle {
  id: string;
  tenant_id: string;
  rego: string;
  vin: string | null;
  vehicle_class: VehicleClass;
  camera_serial: string | null;
  tracking_device_id: string | null;
  meter_device_id: string | null;
  status: VehicleStatus;
  created_at: string;
  updated_at: string;
}

/** Form state for the create/edit Vehicle modal — everything as strings for controlled inputs. */
export interface VehicleFormValues {
  rego: string;
  vin: string;
  vehicle_class: VehicleClass;
  camera_serial: string;
  tracking_device_id: string;
  meter_device_id: string;
  status: VehicleStatus;
}

export const EMPTY_VEHICLE_FORM: VehicleFormValues = {
  rego: "",
  vin: "",
  vehicle_class: "standard",
  camera_serial: "",
  tracking_device_id: "",
  meter_device_id: "",
  status: "active",
};

/** `DeviceRead` */
export interface Device {
  id: string;
  tenant_id: string;
  android_id: string;
  model: string | null;
  app_version: string | null;
  vehicle_id: string | null;
  kiosk_locked: boolean;
  force_update_pending: boolean;
  locate_requested: boolean;
  reboot_requested: boolean;
  last_seen_at: string | null;
  battery: number | null;
  network: string | null;
  created_at: string;
  updated_at: string;
}

export interface DeviceFormValues {
  android_id: string;
  model: string;
  app_version: string;
  vehicle_id: string; // "" = unassigned
  kiosk_locked: boolean;
}

export const EMPTY_DEVICE_FORM: DeviceFormValues = {
  android_id: "",
  model: "",
  app_version: "",
  vehicle_id: "",
  kiosk_locked: false,
};

/** `PairingCodeRead` */
export interface PairingCode {
  code: string;
  vehicle_id: string;
  tenant_id: string;
  expires_at: string;
}

/**
 * `DriverLiveRead` — one row of `GET /v1/drivers`, a User (role=driver) joined
 * with on-shift status. This endpoint is READ-ONLY: the backend exposes no
 * POST/PATCH/DELETE for the user domain, so this page cannot offer real
 * driver create/edit/delete without inventing endpoints that don't exist.
 */
export interface Driver {
  id: string;
  tenant_id: string;
  name: string;
  phone: string | null;
  user_status: string;
  on_shift: boolean;
  shift_id: string | null;
  vehicle_id: string | null;
  shift_start_at: string | null;
  current_trip_id: string | null;
}

export interface Page<T> {
  items: T[];
  total: number;
  skip: number;
  limit: number;
}

/**
 * `FatigueAlertRead` — driving-hours monitoring alerts raised as a side effect
 * of `PATCH /v1/trips/{id}/tick` (see `app.services.fatigue`). List/acknowledge
 * only via `GET /v1/fatigue-alerts` and `POST /v1/fatigue-alerts/{id}/acknowledge`.
 */
export type FatigueAlertKind = "shift_duration_exceeded" | "no_break_taken" | "speed_exceeded";

export const FATIGUE_ALERT_KIND_LABELS: Record<FatigueAlertKind, string> = {
  shift_duration_exceeded: "Shift duration exceeded",
  no_break_taken: "No break taken",
  speed_exceeded: "Speed exceeded",
};

export interface FatigueAlert {
  id: string;
  tenant_id: string;
  driver_id: string;
  shift_id: string | null;
  kind: FatigueAlertKind;
  triggered_at: string;
  details_json: Record<string, unknown> | null;
  acknowledged: boolean;
  created_at: string;
  updated_at: string;
}
