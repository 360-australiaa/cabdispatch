# Dashboard Messages page: templates, read receipts, pagination

Frontend-only fixes for three gaps found in a Messages page audit. No backend
files were touched — every endpoint used here already existed in
`backend/app/api/v1/messages.py` / `backend/app/services/messages.py` /
`backend/app/schemas/messages.py`, and was verified live against a running
backend (`http://127.0.0.1:8001`, logged in as `owner@lillycabs.test`) before
and after writing the UI.

## 1. Canned/quick-tap templates

Backend: `GET /v1/messages/templates` returns
`[{code, label, sender_type}]` for a **fixed** list — verified live:

```json
[
  {"code":"no_job","label":"No Job","sender_type":"driver"},
  {"code":"recall","label":"Recall","sender_type":"driver"},
  {"code":"job_query","label":"Job Query","sender_type":"driver"},
  {"code":"other","label":"Other","sender_type":"driver"},
  {"code":"check_in","label":"Please check in","sender_type":"dispatch"},
  {"code":"return_to_depot","label":"Return to depot","sender_type":"dispatch"},
  {"code":"contact_base_urgent","label":"Contact base urgently","sender_type":"dispatch"}
]
```

`POST /v1/messages/templates/{code}` (`backend/app/api/v1/messages.py:184-219`,
`backend/app/services/messages.py:178-206`) resolves `code` **server-side**
into a real `Message` row through the exact same write path as
`POST /v1/messages` — it does not just return template text for the client to
send separately. Verified live: `POST /v1/messages/templates/check_in` with
`{"driver_id": "..."}` returned a full `MessageRead` object
(`sent_at`/`read_at` included, `body: "Please check in."`) with
`201 Created`, identical shape to a normal send.

Frontend changes:
- `dashboard/src/pages/messages/types.ts:42-60` — added `MessageTemplate` and
  `TemplateSendBody`, mirroring `MessageTemplateRead` /
  `TemplateMessageCreate`.
- `dashboard/src/pages/messages/api.ts:71-88` — added `listMessageTemplates()`
  (`GET /v1/messages/templates`) and `sendTemplateMessage(code, body)`
  (`POST /v1/messages/templates/{code}`).
- `dashboard/src/pages/messages/ThreadPanel.tsx:66-74` — fetches templates
  (`staleTime: Infinity`, it's a fixed list) and filters to
  `sender_type === "dispatch"` (`dispatchTemplates`) — the dashboard is
  dispatch-only, so driver-facing codes (`no_job`/`recall`/`job_query`/
  `other`) are never shown here.
- `dashboard/src/pages/messages/ThreadPanel.tsx:123-128` — `templateMutation`
  sends directly on tap (matches the endpoint's actual "resolve + send
  immediately" behavior) and merges the returned message into the thread the
  same way a free-text send does.
- `dashboard/src/pages/messages/ThreadPanel.tsx:286-300` — renders a row of
  chip buttons above the compose box, one per dispatch template
  (`check_in` / `return_to_depot` / `contact_base_urgent`), disabled while a
  send is in flight; error state at `ThreadPanel.tsx:327-329`.

## 2. Read receipts

Backend: `POST /v1/messages/{message_id}/read`
(`backend/app/api/v1/messages.py:151-165`,
`backend/app/services/messages.py:101-108`) takes a **single message id**
only — there is no bulk/batch variant in this domain. It's idempotent
(`if message.read_at is None: ...`). Verified live: marking a message read
returned the same row with `read_at` populated; re-reading the response shape
confirmed it matches `Message`/`MessageRead` exactly (including `read_at:
string | null` already in `dashboard/src/pages/messages/types.ts:17`).

Frontend changes (`dashboard/src/pages/messages/ThreadPanel.tsx`):
- `ThreadPanel.tsx:55`, `162-183` — a `useEffect` keyed on the loaded
  `messages` array finds every message with `sender_type === "driver" &&
  read_at === null` not already marked, calls `markMessageRead(id)` for each
  (looped per-message, since no bulk endpoint exists), and merges the
  updated row (with the real server `read_at`) back into the React Query
  cache. A ref-backed `Set` of already-processed ids prevents re-firing the
  same POST on every re-render; a failed call removes the id from the set so
  it's retried on the next render.
- `ThreadPanel.tsx:91-95` — the tracking set (and pagination cursor) reset
  when the selected driver changes.
- `ThreadPanel.tsx:335-361` (`MessageBubble`) — unread driver-sent messages
  (`!isDispatch && read_at === null`) get a distinct ring highlight, a small
  dot, and an "· Unread" label — all driven off the real `read_at` field,
  not a fabricated flag. Because messages are marked read as soon as they're
  visible in an opened thread, this state is intentionally transient (it
  clears within one round-trip of opening the thread), matching normal
  chat-app read-receipt behavior.
- `dashboard/src/pages/messages/index.tsx:39-65` — an unread-count `Badge`
  on the driver picker/roster (`index.tsx:118-133`). The backend has no
  "unread count per driver" aggregate endpoint, so this fans out one
  `listLatestThread` fetch per currently-listed driver (same query
  key/fetcher `ThreadPanel` itself uses, so opening a thread reuses this
  cache instead of double-fetching) and counts real `read_at == null`
  driver-sent messages in that recent window. This is the same "good enough
  for demo/dev fleet scale" tradeoff already documented for this page's
  `DRIVER_LOOKUP_LIMIT` (`api.ts`) — a true aggregate endpoint would be
  needed to do this cheaply at larger fleet scale; noted here rather than
  silently hidden.

## 3. Pagination for older messages

Backend: `GET /v1/messages` (`backend/app/api/v1/messages.py:136-148`,
`backend/app/services/messages.py:87-98`) paginates via plain
`skip`/`limit` (`limit` capped at 200), ordered **oldest-first**
(`order_by(Message.sent_at)`) — confirmed live: with 2 messages in a thread,
`skip=0&limit=1` returned the *oldest* one and `skip=1&limit=1` returned the
newest. This means the page's old fixed `{ limit: THREAD_LIMIT }` fetch
(`skip` defaulting to 0) was anchoring the view on the **oldest** messages in
a thread with more than `THREAD_LIMIT` history, not the most recent
conversation — and "load older" is only meaningful once the view is anchored
on the tail, since `skip=0` has nothing before it.

Frontend changes:
- `dashboard/src/pages/messages/api.ts:17`, `41-56` — exported shared
  `THREAD_LIMIT = 50`; added `listLatestThread(driverId, limit)`, which does
  a cheap 1-row probe for `total` then fetches the tail window
  `[max(0, total - limit), total)`, so the initial thread view shows the
  most recent messages regardless of history length.
- `dashboard/src/pages/messages/ThreadPanel.tsx:59-62` — the thread query now
  calls `listLatestThread` instead of a bare `listThread({ limit })`.
- `ThreadPanel.tsx:52`, `97-102` — `oldestSkip` state tracks the server-side
  `skip` of the oldest currently-loaded page (from the response's echoed
  `skip` field), set once per driver from the first successful load.
- `ThreadPanel.tsx:130-158` — `loadOlderMutation` fetches the preceding
  chunk (`skip = max(0, oldestSkip - THREAD_LIMIT)`), prepends
  de-duplicated results to the cached item list, and moves the cursor back;
  `hasOlder` (`oldestSkip > 0`) gates the button's visibility.
- `ThreadPanel.tsx:253-270` — a "Load older messages" button renders above
  the message list only when `hasOlder` is true, with a loading/error state.
- `ThreadPanel.tsx:44-48`, `104-113`, `151-158` — scroll handling
  distinguishes a new-message append (scroll to bottom, unchanged prior
  behavior) from an older-page prepend (adjust `scrollTop` by the added
  `scrollHeight` delta so the view doesn't jump to the top of the newly
  loaded content — not pixel-perfect, but not jarring).

## Verification

- `cd dashboard && npm run lint` (`tsc --noEmit`) — clean, zero errors/output.
- Backend reachable at `http://127.0.0.1:8001` — logged in as
  `owner@lillycabs.test` / `ChangeMe123!` and exercised all three endpoints
  directly with `curl` (see quoted responses above): `GET
  /v1/messages/templates`, `POST /v1/messages/templates/check_in`, `POST
  /v1/messages` (built up thread history), `GET /v1/messages` with varying
  `skip`/`limit` (confirmed oldest-first ordering empirically), and `POST
  /v1/messages/{id}/read`.
