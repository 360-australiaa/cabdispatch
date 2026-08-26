import apiClient from "@/lib/apiClient";
import type {
  DuressCallResult,
  DuressEvent,
  DuressEventListParams,
  DuressEventListResponse,
  DuressEventUpdateBody,
  DuressNoteBody,
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
