/** Mirrors backend `app/schemas/messages.py` (shared/openapi.json). */

export type MessageSenderType = "dispatch" | "driver";

/** `MessageRead` — one message in a driver's thread (`thread_id == driver_id`,
 * see `app/api/v1/messages.py`'s module docstring: this is intentionally one
 * thread per driver, not a generic multi-thread system). */
export interface Message {
  id: string;
  tenant_id: string;
  thread_id: string;
  driver_id: string;
  sender_type: MessageSenderType | string;
  sender_user_id: string | null;
  body: string;
  sent_at: string;
  read_at: string | null;
}

export interface MessageListResponse {
  items: Message[];
  total: number;
  skip: number;
  limit: number;
}

export interface MessageListParams {
  driver_id: string;
  skip?: number;
  limit?: number;
}

/** Body for `POST /v1/messages`. `driver_id` is required here since the
 * dashboard always calls this as a dispatch-side sender (owner/admin/
 * dispatcher) — a `driver`-role caller would have it ignored server-side in
 * favour of their own id, but no driver logs into this dashboard. */
export interface MessageCreateBody {
  driver_id: string;
  body: string;
}

/**
 * Lightweight driver row pulled from `GET /v1/drivers` (the full read-only
 * rollup is `src/pages/fleet/types.ts::Driver`) — only the fields the
 * Messages page's driver picker and thread header actually need.
 */
export interface DriverOption {
  id: string;
  name: string;
  phone: string | null;
  on_shift: boolean;
}

export interface DriverOptionsResponse {
  items: DriverOption[];
  total: number;
  skip: number;
  limit: number;
}

/**
 * One entry of `GET /v1/messages/templates` — the fixed canned-message menu
 * (mirrors backend `app/schemas/messages.py::MessageTemplateRead`). Includes
 * both driver-side quick-request codes (e.g. "no_job"/"recall"/"job_query"/
 * "other") and dispatch-side quick-status codes (e.g. "check_in"/
 * "return_to_depot"/"contact_base_urgent") — the dashboard only ever renders
 * the `sender_type === "dispatch"` subset, since no driver logs into it.
 */
export interface MessageTemplate {
  code: string;
  label: string;
  sender_type: MessageSenderType | string;
}

/** Body for `POST /v1/messages/templates/{code}`. Mirrors backend
 * `TemplateMessageCreate` — `note` is an optional free-text suffix appended
 * to the template's canned body. */
export interface TemplateSendBody {
  driver_id: string;
  note?: string;
}
