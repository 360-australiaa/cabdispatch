import apiClient from "@/lib/apiClient";
import type {
  DriverOption,
  DriverOptionsResponse,
  Message,
  MessageCreateBody,
  MessageListParams,
  MessageListResponse,
} from "./types";

/**
 * First 100 drivers, used as the thread picker on the left of the Messages
 * page. The backend has no "which drivers have a thread" endpoint, so every
 * driver is listed whether or not they've exchanged messages yet — same
 * "good enough for demo/dev fleet scale" lookup cap as
 * `src/pages/fleet/api.ts`'s `LOOKUP_LIMIT` (no search-as-you-type endpoint
 * exists yet either).
 */
const DRIVER_LOOKUP_LIMIT = 100;

export async function listDriverOptions(): Promise<DriverOption[]> {
  const res = await apiClient.get<DriverOptionsResponse>("/v1/drivers", {
    params: { skip: 0, limit: DRIVER_LOOKUP_LIMIT },
  });
  return res.data.items;
}

export async function listThread(params: MessageListParams): Promise<MessageListResponse> {
  const res = await apiClient.get<MessageListResponse>("/v1/messages", { params });
  return res.data;
}

export async function sendMessage(body: MessageCreateBody): Promise<Message> {
  const res = await apiClient.post<Message>("/v1/messages", body);
  return res.data;
}

export async function markMessageRead(id: string): Promise<Message> {
  const res = await apiClient.post<Message>(`/v1/messages/${id}/read`);
  return res.data;
}
