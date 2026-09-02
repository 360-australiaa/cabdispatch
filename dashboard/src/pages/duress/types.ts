/** Mirrors backend `app/schemas/duress.py` + `app/models/duress.py` (shared/openapi.json). */

export type DuressTrigger = "button" | "gesture" | "voice" | "auto";

export type DuressStatus = "open" | "escalating" | "dispatched" | "resolved" | "cancelled";

export const DURESS_TERMINAL_STATUSES: readonly DuressStatus[] = ["resolved", "cancelled"];

export function isTerminalStatus(status: string): boolean {
  return (DURESS_TERMINAL_STATUSES as readonly string[]).includes(status);
}

/** Fixed escalation cascade — `app.models.duress.ESCALATION_STAGES`. Each
 * `POST /{id}/escalate` call advances exactly one stage; the first flips
 * open -> escalating, the last flips escalating -> dispatched. */
export const ESCALATION_STAGES = [
  "cancel_window_expired",
  "notify_dispatch",
  "sms_emergency_contacts",
  "present_000_call_script",
] as const;

export interface EscalationLogEntry {
  stage: string;
  at: string;
  detail?: string;
  note?: string | null;
}

/** Shape of `escalation_log_json` — single source of truth for the timeline
 * AND the state-machine bookkeeping (cancel deadline, next stage index). */
export interface EscalationLog {
  cancel_window_seconds?: number;
  cancel_deadline_at?: string;
  next_stage_index?: number;
  entries?: EscalationLogEntry[];
  [key: string]: unknown;
}

/** Which side(s) contributed to this incident — a tablet trigger, a paired
 * physical duress device, or both reporting in. */
export type DuressEventSource = "tablet" | "device" | "both";

/** Result of `POST /v1/duress/{id}/call` (and the persisted shape of
 * `device_call_result_json`) — dialing the physical device's paired phone
 * via Twilio, or a mock/skip fallback when no call-centre number is
 * configured. `status` reflects the last Twilio status-webhook update, when
 * one has landed. */
export interface DuressCallResult {
  mock: boolean;
  to_phone: string | null;
  twilio_call_sid: string | null;
  skipped: boolean | null;
  reason: string | null;
  status?: string | null;
  [key: string]: unknown;
}

export interface DuressEvent {
  id: string;
  tenant_id: string;
  vehicle_id: string;
  driver_id: string;
  trigger: DuressTrigger | string;
  status: DuressStatus | string;
  opened_at: string;
  closed_at: string | null;
  gps_stream_ref: string;
  audio_ref: string | null;
  escalation_log_json: EscalationLog;
  device_id: string | null;
  source: DuressEventSource | string;
  device_audio_ref: string | null;
  device_call_result_json: DuressCallResult | null;
  created_at: string;
  updated_at: string;
}

export interface DuressEventListResponse {
  items: DuressEvent[];
  total: number;
  limit: number;
  offset: number;
}

export interface DuressEventListParams {
  limit?: number;
  offset?: number;
  driver_id?: string;
  vehicle_id?: string;
  status?: string;
  trigger?: string;
  open_only?: boolean;
}

export interface DuressTriggerRequestBody {
  vehicle_id: string;
  driver_id: string;
  trigger: DuressTrigger;
  gps_stream_ref?: string | null;
  audio_ref?: string | null;
}

export interface DuressEventUpdateBody {
  vehicle_id?: string | null;
  driver_id?: string | null;
  gps_stream_ref?: string | null;
  audio_ref?: string | null;
}

export interface DuressNoteBody {
  note?: string | null;
}

/** A single fix broadcast over `WS /v1/duress/{id}/live` — the raw
 * `DuressGpsPoint` the caller POSTed to `/{id}/gps`, plus server-stamped
 * `ts`/`event_id` (see `app/api/v1/duress.py::post_gps`). Not persisted. Now
 * also carries which side reported the fix, so the live trace can tell a
 * device's points apart from the tablet's. */
export interface DuressGpsPoint {
  lat: number;
  lng: number;
  speed_kmh?: number | null;
  accuracy_m?: number | null;
  ts?: string | null;
  event_id?: string;
  source?: "tablet" | "device" | null;
}

/** One captured cabin-camera still-frame -- app/schemas/duress_snapshot.py.
 * See app/models/duress_snapshot.py for why this is a still-frame gallery
 * (POST /v1/duress/{id}/snapshot, called every ~2-5s while an event stays
 * open) rather than continuous video. */
export interface DuressSnapshotMeta {
  id: string;
  event_id: string;
  captured_at: string;
  created_at: string;
}

export interface DuressSnapshotListResponse {
  items: DuressSnapshotMeta[];
  total: number;
}

/** Broadcast over the SAME WS /v1/duress/{id}/live feed as DuressGpsPoint,
 * tagged kind: "snapshot" (GPS points never carry that key) so
 * useDuressLiveGps can route the two apart on one socket. */
export interface DuressSnapshotNotification {
  kind: "snapshot";
  event_id: string;
  snapshot_id: string;
  captured_at: string;
}

/** Mirrors backend `app/schemas/duress_device.py` + `app/models/duress_device.py`
 * -- the physical CT-DPD-01 panic-button hardware, factory-provisioned and
 * bound to one vehicle. `DuressDeviceRead` NEVER includes the shared secret
 * (encrypted or plaintext) -- see that model's module docstring for why this
 * is the one place in the codebase a secret is stored reversibly (Fernet,
 * not hashed): HMAC verification needs the plaintext back, unlike password
 * checking. Neither `create` nor `rotate-secret` returns the secret either --
 * the operator supplies it (it's already burned into/re-flashed onto the
 * physical unit's firmware); this backend never generates one server-side. */
export interface DuressDevice {
  id: string;
  tenant_id: string;
  device_code: string;
  vehicle_id: string | null;
  phone_number: string | null;
  battery_pct: number | null;
  on_battery: boolean;
  gnss_fix: boolean;
  signal_csq: number | null;
  firmware_version: string | null;
  last_seen_at: string | null;
  active: boolean;
  created_at: string;
  updated_at: string;
}

export interface DuressDeviceListResponse {
  items: DuressDevice[];
  total: number;
  limit: number;
  offset: number;
}

/** Body for `POST /v1/duress-devices`. `plaintext_secret` is the device's
 * K_dev shared secret as already provisioned into its firmware -- Fernet-
 * encrypted at rest immediately and never returned by any read endpoint
 * again after this call. */
export interface DuressDeviceCreateBody {
  device_code: string;
  vehicle_id?: string | null;
  phone_number?: string | null;
  plaintext_secret: string;
}

/** Body for `PATCH /v1/duress-devices/{id}`. Notably does NOT include
 * `device_code` (immutable after provisioning) or the secret (rotated via
 * its own dedicated endpoint below). */
export interface DuressDeviceUpdateBody {
  vehicle_id?: string | null;
  phone_number?: string | null;
  active?: boolean;
}

/** Body for `POST /v1/duress-devices/{id}/rotate-secret` -- the
 * re-provisioning flow when a device's firmware is re-flashed with a new
 * K_dev. The caller supplies the new plaintext secret; the response is a
 * plain `DuressDeviceRead` (the secret itself is never echoed back). */
export interface DuressDeviceRotateSecretBody {
  plaintext_secret: string;
}
