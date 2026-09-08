/** Mirrors backend `app/schemas/shifts.py` (shared/openapi.json: ShiftRead /
 * ShiftCreate / ShiftUpdate / ShiftStart / ShiftEnd / ShiftReport /
 * ShiftListResponse). Money fields (km_total, cash_total, card_total,
 * psl_owed, total_takings) are decimal strings straight off the wire —
 * never parsed here, only formatted for display in ./format.ts. */

export interface Shift {
  id: string;
  tenant_id: string;
  driver_id: string;
  vehicle_id: string;
  start_at: string;
  end_at: string | null;
  inspection_json: Record<string, unknown> | null;
  trips_count: number;
  km_total: string;
  cash_total: string;
  card_total: string;
  psl_owed: string;
  reconciled: boolean;
  created_at: string;
  updated_at: string;
}

export interface ShiftListResponse {
  items: Shift[];
  total: number;
  limit: number;
  offset: number;
}

export interface ShiftListFilters {
  limit?: number;
  offset?: number;
  driver_id?: string;
  vehicle_id?: string;
  reconciled?: boolean;
  active_only?: boolean;
  start_at_from?: string;
  start_at_to?: string;
}

/** Body for `POST /v1/shifts/start` — the normal dashboard-side way to open
 * a shift (e.g. backfilling one the driver app failed to open). */
export interface ShiftStartInput {
  driver_id: string;
  vehicle_id: string;
  start_at?: string | null;
  inspection_json?: Record<string, unknown> | null;
  /** Only needed when a prior attempt came back 409 (see ShiftConflictDetail
   * below) — confirms a real handover: end the other driver's open shift on
   * this vehicle and proceed, instead of the request being rejected. */
  force_handover?: boolean;
}

/** Structured `detail` on a 409 from `POST /v1/shifts/start` — the vehicle
 * already has an open shift under a different driver. */
export interface ShiftConflictDetail {
  message: string;
  conflicting_shift_id: string;
  conflicting_driver_id: string;
  conflicting_driver_name: string;
  conflicting_shift_start_at: string;
}

/** Body for `POST /v1/shifts/{id}/end` — reconciliation figures only;
 * trips_count/km_total/cash_total/card_total are recomputed server-side. */
export interface ShiftEndInput {
  end_at?: string | null;
  psl_owed?: string | number;
  reconciled?: boolean;
}

/** Body for `PATCH /v1/shifts/{id}` — admin corrections, every field optional. */
export interface ShiftUpdateInput {
  driver_id?: string | null;
  vehicle_id?: string | null;
  start_at?: string | null;
  end_at?: string | null;
  inspection_json?: Record<string, unknown> | null;
  trips_count?: number | null;
  km_total?: string | number | null;
  cash_total?: string | number | null;
  card_total?: string | number | null;
  psl_owed?: string | number | null;
  reconciled?: boolean | null;
}

/** Body for `POST /v1/shifts` — generic admin-backfill create (full record). */
export interface ShiftCreateInput {
  driver_id: string;
  vehicle_id: string;
  start_at: string;
  end_at?: string | null;
  inspection_json?: Record<string, unknown> | null;
  trips_count?: number;
  km_total?: string | number;
  cash_total?: string | number;
  card_total?: string | number;
  psl_owed?: string | number;
  reconciled?: boolean;
}

export interface ShiftReport {
  shift_id: string;
  tenant_id: string;
  driver_id: string;
  vehicle_id: string;
  start_at: string;
  end_at: string | null;
  duration_minutes: number | null;
  trips_count: number;
  km_total: string;
  cash_total: string;
  card_total: string;
  total_takings: string;
  psl_owed: string;
  reconciled: boolean;
  inspection_json: Record<string, unknown> | null;
  generated_at: string;
}

export interface DriverLite {
  id: string;
  name: string;
  phone: string | null;
  user_status: string;
  on_shift: boolean;
}

export interface VehicleLite {
  id: string;
  rego: string;
  vehicle_class: string;
  vehicle_status: string;
}
