import apiClient from "@/lib/apiClient";
import type {
  DuressCallResult,
  DuressDevice,
  DuressDeviceCreateBody,
  DuressDeviceListResponse,
  DuressDeviceRotateSecretBody,
  DuressDeviceUpdateBody,
  DuressEvent,
  DuressEventListParams,
  DuressEventListResponse,
  DuressEventUpdateBody,
  DuressNoteBody,
  DuressSnapshotListResponse,
  DuressTriggerRequestBody,
} from "./types";

export async function listDuressEvents(
  params: DuressEventListParams,
): Promise<DuressEventListResponse> {
  const res = await apiClient.get<DuressEventListResponse>("/v1/duress", { params });
  return res.data;
}

export async function getDuressEvent(id: string): Promise<DuressEvent> {
  const res = await apiClient.get<DuressEvent>(`/v1/duress/${id}`);
  return res.data;
}

export async function triggerDuressEvent(body: DuressTriggerRequestBody): Promise<DuressEvent> {
  const res = await apiClient.post<DuressEvent>("/v1/duress/trigger", body);
  return res.data;
}

export async function cancelDuressEvent(id: string, body: DuressNoteBody): Promise<DuressEvent> {
  const res = await apiClient.post<DuressEvent>(`/v1/duress/${id}/cancel`, body);
  return res.data;
}

export async function escalateDuressEvent(id: string, body: DuressNoteBody): Promise<DuressEvent> {
  const res = await apiClient.post<DuressEvent>(`/v1/duress/${id}/escalate`, body);
  return res.data;
}

export async function closeDuressEvent(id: string, body: DuressNoteBody): Promise<DuressEvent> {
  const res = await apiClient.post<DuressEvent>(`/v1/duress/${id}/close`, body);
  return res.data;
}

/** Dials the paired physical duress device's phone via Twilio (or returns a
 * mock/skip result when no call-centre number is configured) —
 * `POST /v1/duress/{id}/call`. Only meaningful once the event has a
 * `device_id`. */
export async function callDuressEvent(id: string, body: DuressNoteBody): Promise<DuressCallResult> {
  const res = await apiClient.post<DuressCallResult>(`/v1/duress/${id}/call`, body);
  return res.data;
}

export async function updateDuressEvent(
  id: string,
  body: DuressEventUpdateBody,
): Promise<DuressEvent> {
  const res = await apiClient.patch<DuressEvent>(`/v1/duress/${id}`, body);
  return res.data;
}

export async function deleteDuressEvent(id: string): Promise<void> {
  await apiClient.delete(`/v1/duress/${id}`);
}

// --- camera snapshot gallery (GET /v1/duress/{id}/snapshots + .../snapshot/{id}) --

/** Metadata list for the post-incident scrub bar -- `GET /v1/duress/{id}/snapshots`.
 * Newest first, per the backend's own ordering. Image bytes for one frame
 * come from `GET /v1/duress/{id}/snapshot/{snapshot_id}` (fetched on demand
 * as a blob by `SnapshotGallery`, not here — see that component for why a
 * plain `<img src="...">` can't hit an authenticated route directly). */
export async function listDuressSnapshots(
  eventId: string,
  params?: { limit?: number; offset?: number },
): Promise<DuressSnapshotListResponse> {
  const res = await apiClient.get<DuressSnapshotListResponse>(
    `/v1/duress/${eventId}/snapshots`,
    { params },
  );
  return res.data;
}

// --- captured audio playback (GET /v1/duress/{id}/audio) ------------------------

/** Fetches the tablet-side captured audio recording as a raw blob --
 * `GET /v1/duress/{id}/audio` requires the same bearer auth as every other
 * duress route, so a plain `<audio src="...">` can't point at it directly.
 * `DuressAudioPlayer` turns the result into an object URL. Note there is no
 * equivalent read route for `device_audio_ref` (the physical device's own
 * recording) in this backend pass -- only the tablet's `audio_ref` streams
 * back. */
export async function fetchDuressAudioBlob(eventId: string): Promise<Blob> {
  const res = await apiClient.get(`/v1/duress/${eventId}/audio`, { responseType: "blob" });
  return res.data as Blob;
}

// --- duress hardware (device) provisioning -- /v1/duress-devices ----------------
//
// Full CRUD for the physical CT-DPD-01 panic-button unit itself (as opposed
// to the DuressEvent incidents it can open) -- see
// backend/app/api/v1/duress_device.py's module docstring for the role split:
// list/get is any authenticated tenant user (read-only visibility), while
// create/update/rotate-secret/delete are owner/admin/dispatcher only, same
// _DISPATCH_ROLES gate as the DuressEvent lifecycle actions above.

export async function listDuressDevices(params?: {
  limit?: number;
  offset?: number;
}): Promise<DuressDeviceListResponse> {
  const res = await apiClient.get<DuressDeviceListResponse>("/v1/duress-devices", { params });
  return res.data;
}

export async function createDuressDevice(body: DuressDeviceCreateBody): Promise<DuressDevice> {
  const res = await apiClient.post<DuressDevice>("/v1/duress-devices", body);
  return res.data;
}

export async function updateDuressDevice(
  id: string,
  body: DuressDeviceUpdateBody,
): Promise<DuressDevice> {
  const res = await apiClient.patch<DuressDevice>(`/v1/duress-devices/${id}`, body);
  return res.data;
}

/** Re-provisioning flow: the operator supplies the NEW plaintext secret
 * (already re-flashed into the device's firmware) — the server never
 * generates or returns one, so there is nothing to reveal-once here (unlike
 * the fleet Vehicle pairing-code flow). See `DuressDeviceRotateSecretBody`'s
 * doc comment. */
export async function rotateDuressDeviceSecret(
  id: string,
  body: DuressDeviceRotateSecretBody,
): Promise<DuressDevice> {
  const res = await apiClient.post<DuressDevice>(`/v1/duress-devices/${id}/rotate-secret`, body);
  return res.data;
}

/** Hard delete (not a soft deactivate) — see `DELETE /v1/duress-devices/{id}`
 * in the backend router. To deactivate a device WITHOUT losing its history,
 * use `updateDuressDevice(id, { active: false })` instead. */
export async function deleteDuressDevice(id: string): Promise<void> {
  await apiClient.delete(`/v1/duress-devices/${id}`);
}

// --- lightweight vehicle lookup, for the device-link dropdown --------------------

export interface DuressVehicleOption {
  id: string;
  rego: string;
}

/** Unpaginated-ish (first 100 -- `GET /v1/fleet/vehicles`'s own server-side
 * cap, confirmed live) vehicle list for the device create/edit form's
 * "linked vehicle" dropdown — same lightweight lookup-query pattern (and
 * same 100-item cap) as `pages/fleet/api.ts`'s `useVehicleOptions`, kept
 * local to this domain rather than importing across page boundaries. */
export async function listVehicleOptionsForDeviceLink(): Promise<DuressVehicleOption[]> {
  const res = await apiClient.get<{ items: DuressVehicleOption[] }>("/v1/fleet/vehicles", {
    params: { skip: 0, limit: 100 },
  });
  return res.data.items;
}
