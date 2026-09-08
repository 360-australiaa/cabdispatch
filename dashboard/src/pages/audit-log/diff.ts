/**
 * Field-level diffing for one audit-log entry.
 *
 * Split out of `index.tsx` so the classification logic can be unit-tested
 * directly and reused by the CSV export (`./csv.ts`) without duplicating it.
 *
 * WHY THIS EXISTS AS ITS OWN MODULE (bug this fixes): the page used to treat
 * `before_json === null` as proof the entity was just created ("Created"
 * badge, no Before column). That is only true for the handful of actions that
 * are actually creation events. `record_audit()` (backend
 * `app/services/audit_log.py`) lets any caller omit `before` independently of
 * `after`, and at least one real call site does exactly that for a
 * non-creation action: `device_secret_rotated`
 * (`backend/app/services/fleet.py:357-365`) rotates a secret on an *existing*
 * device and never captures the prior secret, so `before_json` is `null` on
 * an entry whose action is not a create. The old logic would have rendered
 * that as "Created" -- a confidently wrong diff implying nothing existed
 * before. This module tells those two cases apart and gives the second one
 * an honest "no prior state recorded" status instead of guessing.
 *
 * Action vocabulary is enumerated from every `record_audit(..., action=...)`
 * call site in the backend today (`git grep 'action="'` under
 * `backend/app/services` and `backend/app/api`): close, create, delete,
 * device_registered, device_secret_rotated, reassign,
 * shift_device_vehicle_mismatch, shift_force_closed_driver_deleted,
 * shift_force_closed_vehicle_deleted, tick, update. Only `create` and
 * `device_registered` are genuine "this entity did not exist before" events;
 * everything else that shows up with a null `before_json` gets the honest
 * label rather than being folded into "Created".
 */

/** Actions where a null `before_json` means "this is the first record of the
 * entity", not "we didn't capture the prior state". Kept as a literal list
 * (not inferred from the string) since guessing from the shape of the action
 * name is exactly the kind of plausible-looking-but-wrong shortcut this
 * module exists to avoid. */
const CREATE_LIKE_ACTIONS = new Set(["create", "device_registered"]);

export interface DiffField {
  field: string;
  from: unknown;
  to: unknown;
}

export type DiffStatus = "create" | "delete" | "update" | "before-not-recorded";

export interface EntryDiff {
  status: DiffStatus;
  changes: DiffField[];
}

/** Diffs a generic before/after snapshot pair into {field, from, to} rows
 * for every key present on either side whose stringified value changed. */
export function diffJson(
  before: Record<string, unknown> | null,
  after: Record<string, unknown> | null,
): DiffField[] {
  const keys = new Set<string>([
    ...(before ? Object.keys(before) : []),
    ...(after ? Object.keys(after) : []),
  ]);
  const rows: DiffField[] = [];
  for (const key of keys) {
    const fromVal = before ? before[key] : undefined;
    const toVal = after ? after[key] : undefined;
    if (String(fromVal) !== String(toVal)) {
      rows.push({ field: key, from: fromVal, to: toVal });
    }
  }
  return rows.sort((a, b) => a.field.localeCompare(b.field));
}

/** Classifies one entry's before/after pair and, only when it is actually
 * safe to, computes the field-level diff. See the module doc above for why
 * `before_json === null` is not always "created". */
export function classifyEntry(entry: {
  action: string;
  before_json: Record<string, unknown> | null;
  after_json: Record<string, unknown> | null;
}): EntryDiff {
  if (entry.before_json == null) {
    if (CREATE_LIKE_ACTIONS.has(entry.action)) {
      return { status: "create", changes: diffJson(null, entry.after_json) };
    }
    // Not a creation action, and no prior state was captured for it -- do
    // not render a diff that implies otherwise.
    return { status: "before-not-recorded", changes: [] };
  }
  if (entry.after_json == null) {
    // A genuine delete: there is nothing "after" by definition, not a gap.
    return { status: "delete", changes: diffJson(entry.before_json, null) };
  }
  return { status: "update", changes: diffJson(entry.before_json, entry.after_json) };
}
