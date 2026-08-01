import apiClient from "@/lib/apiClient";
import type {
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
