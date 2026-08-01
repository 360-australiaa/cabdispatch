/** All money fields on Shift/ShiftReport arrive as decimal strings (see
 * shared/API_SUMMARY.md). This is the one place that turns them into a
 * display string — never parsed for further arithmetic. */
export function formatMoney(value: string | null | undefined): string {
  if (value == null || value === "") return "—";
  const n = Number(value);
  if (Number.isNaN(n)) return value;
  return new Intl.NumberFormat("en-AU", { style: "currency", currency: "AUD" }).format(n);
}

export function formatKm(value: string | null | undefined): string {
  if (value == null || value === "") return "—";
  const n = Number(value);
  if (Number.isNaN(n)) return value;
  return `${n.toFixed(1)} km`;
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString("en-AU", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** For pre-filling <input type="datetime-local">: strips to
 * "YYYY-MM-DDTHH:mm" in the viewer's local timezone. */
export function toDatetimeLocalValue(value: string | null | undefined): string {
  if (!value) return "";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Converts a `datetime-local` input value back to an ISO string for the
 * API, or undefined if empty (letting the backend default to server time). */
export function fromDatetimeLocalValue(value: string): string | undefined {
  if (!value) return undefined;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return undefined;
  return d.toISOString();
}

export function formatDurationMinutes(minutes: number | null | undefined): string {
  if (minutes == null) return "—";
  const total = Math.round(minutes);
  const hrs = Math.floor(total / 60);
  const mins = total % 60;
  return hrs > 0 ? `${hrs}h ${mins}m` : `${mins}m`;
}

export function reconciledBadgeVariant(reconciled: boolean): "success" | "destructive" {
  return reconciled ? "success" : "destructive";
}

export function shiftStatusLabel(shift: { end_at: string | null }): string {
  return shift.end_at ? "Ended" : "Active";
}

export function shiftStatusBadgeVariant(shift: { end_at: string | null }): "accent" | "outline" {
  return shift.end_at ? "outline" : "accent";
}
