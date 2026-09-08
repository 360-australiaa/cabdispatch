import apiClient from "@/lib/apiClient";
import type {
  DriverOption,
  DriverOptionsResponse,
  Message,
  MessageCreateBody,
  MessageListParams,
  MessageListResponse,
  MessageTemplate,
  TemplateSendBody,
} from "./types";

/**
 * Default page size for a driver's thread — see `listLatestThread` below for
 * why the *initial* fetch is not simply `{ skip: 0, limit: THREAD_LIMIT }`.
 */
export const THREAD_LIMIT = 50;

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

/**
 * Fetches the most recent `limit` messages of a driver's thread.
 *
 * `GET /v1/messages` (backend `list_thread`) orders **oldest-first** and
 * paginates via plain `skip`/`limit` — so `skip=0` returns the OLDEST
 * messages in the thread, not the latest ones. To anchor the thread view on
 * the current conversation (with "load older" then walking back toward
 * `skip=0`), this first does a cheap 1-row probe to learn `total`, then
 * fetches the tail window `[max(0, total - limit), total)`.
 */
export async function listLatestThread(driverId: string, limit: number): Promise<MessageListResponse> {
  const probe = await listThread({ driver_id: driverId, skip: 0, limit: 1 });
  if (probe.total === 0) {
    return { items: [], total: 0, skip: 0, limit };
  }
  const skip = Math.max(0, probe.total - limit);
  return listThread({ driver_id: driverId, skip, limit });
}

export async function sendMessage(body: MessageCreateBody): Promise<Message> {
  const res = await apiClient.post<Message>("/v1/messages", body);
  return res.data;
}

export async function markMessageRead(id: string): Promise<Message> {
  const res = await apiClient.post<Message>(`/v1/messages/${id}/read`);
  return res.data;
}

/** `GET /v1/messages/templates` — the fixed canned-message menu. */
export async function listMessageTemplates(): Promise<MessageTemplate[]> {
  const res = await apiClient.get<MessageTemplate[]>("/v1/messages/templates");
  return res.data;
}

/**
 * `POST /v1/messages/templates/{code}` — resolves `code` server-side into a
 * real `Message` (through the same write path as `sendMessage` above), so
 * the response is sent/delivered immediately, not just resolved text for the
 * caller to send separately.
 */
export async function sendTemplateMessage(code: string, body: TemplateSendBody): Promise<Message> {
  const res = await apiClient.post<Message>(`/v1/messages/templates/${encodeURIComponent(code)}`, body);
  return res.data;
}
