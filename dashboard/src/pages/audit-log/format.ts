/** Display formatting for the Audit log page.
 *
 * The `format*` helpers below are re-exported from `@/lib/format`, which is
 * now the single implementation of each (see that module's header: this file
 * used to carry its own copy, one of 12 near-identical ones across the
 * per-page `format.ts` modules). Only the page-specific helpers below are local.
 */

export { errorMessage, formatDateTimeSeconds as formatDateTime, truncateId as shortId } from "@/lib/format";

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
