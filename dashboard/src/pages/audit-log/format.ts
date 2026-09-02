import axios from "axios";

/** Best-effort human message out of an Axios/FastAPI error -- same shape as
 * `pages/duress/format.ts`'s helper of the same name, duplicated here rather
 * than shared cross-domain (no shared error-formatting util exists yet). */
export function errorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const detail = (err.response?.data as { detail?: unknown } | undefined)?.detail;
    if (typeof detail === "string") return detail;
    if (Array.isArray(detail)) {
      const first = detail[0] as { msg?: string; loc?: unknown[] } | undefined;
      if (first?.msg) {
        const field = Array.isArray(first.loc) ? first.loc.at(-1) : undefined;
        return field ? `${String(field)}: ${first.msg}` : first.msg;
      }
    }
    if (err.response?.status) return `Request failed (${err.response.status}).`;
    return err.message;
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "medium" });
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

/** Truncated id for compact display, full value available on hover via `title`. */
export function shortId(id: string): string {
  return id.length > 8 ? `${id.slice(0, 8)}…` : id;
}
