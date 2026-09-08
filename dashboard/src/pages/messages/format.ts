/** Display formatting for the Messages page.
 *
 * The `format*` helpers below are re-exported from `@/lib/format`, which is
 * now the single implementation of each (see that module's header: this file
 * used to carry its own copy, one of 12 near-identical ones across the
 * per-page `format.ts` modules). Only the page-specific helpers below are local.
 */

export {
  formatDateTimeSeconds as formatDateTime,
  formatTimeShort as formatTime,
  initials,
} from "@/lib/format";

export function senderLabel(senderType: string): string {
  return senderType === "driver" ? "Driver" : "Dispatch";
}
