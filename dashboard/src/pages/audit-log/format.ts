/** Display formatting for the Audit log page.
 *
 * The `format*` helpers below are re-exported from `@/lib/format`, which is
 * now the single implementation of each (see that module's header: this file
 * used to carry its own copy, one of 12 near-identical ones across the
 * per-page `format.ts` modules). Only the page-specific helpers below are local.
 */

import type { AuditLogEntry } from "./types";

export { errorMessage, formatDateTimeSeconds as formatDateTime, truncateId as shortId } from "@/lib/format";

/**
 * Who an entry's actor is, in order of preference: the name the API joined
 * onto the row (`actor_name`), else the name from the `GET /v1/users` lookup
 * the page already fetches, else the id shortened to eight characters -- and
 * an em dash for a system-attributed entry with no actor at all. The raw id
 * is never the first choice again (admin-panel plan §5).
 */
export function resolveActorName(
  entry: Pick<AuditLogEntry, "actor_user_id" | "actor_name">,
  namesById: ReadonlyMap<string, string>,
): string {
  if (entry.actor_name) return entry.actor_name;
  if (!entry.actor_user_id) return "—";
  return namesById.get(entry.actor_user_id) ?? entry.actor_user_id.slice(0, 8);
}

/** "vehicle.update" / "create" -> a badge variant that roughly buckets the
 * action by CRUD-ish verb, purely cosmetic (the backend imposes no fixed enum
 * on `action` — it's a free-text field, see `AuditLogCreate.action`). */
export function actionBadgeVariant(
  action: string,
): "default" | "primary" | "accent" | "success" | "destructive" | "outline" {
  const a = action.toLowerCase();
  if (a.includes("delete") || a.includes("remove") || a.includes("cancel")) return "destructive";
  if (a.includes("create") || a.includes("add") || a.includes("trigger")) return "success";
  if (a.includes("update") || a.includes("edit") || a.includes("rotate")) return "accent";
  return "outline";
}
