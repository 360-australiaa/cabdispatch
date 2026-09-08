/** Display formatting for the Shifts page.
 *
 * The `format*` helpers below are re-exported from `@/lib/format`, which is
 * now the single implementation of each (see that module's header: this file
 * used to carry its own copy, one of 12 near-identical ones across the
 * per-page `format.ts` modules). Only the page-specific helpers below are local.
 */

export {
  formatMoney,
  formatKm,
  formatDateTime,
  toDatetimeLocalValue,
  fromDatetimeLocalValue,
  formatDurationMinutes,
} from "@/lib/format";

export function reconciledBadgeVariant(reconciled: boolean): "success" | "destructive" {
  return reconciled ? "success" : "destructive";
}

export function shiftStatusLabel(shift: { end_at: string | null }): string {
  return shift.end_at ? "Ended" : "Active";
}

export function shiftStatusBadgeVariant(shift: { end_at: string | null }): "accent" | "outline" {
  return shift.end_at ? "outline" : "accent";
}
