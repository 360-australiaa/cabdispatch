/** Display formatting for the Platform console page.
 *
 * The `format*` helpers below are re-exported from `@/lib/format`, which is
 * now the single implementation of each (see that module's header: this file
 * used to carry its own copy, one of 12 near-identical ones across the
 * per-page `format.ts` modules). Only the page-specific helpers below are local.
 */

export {
  errorMessage,
  formatDateTimeShort as formatDateTime,
  /** Was a local, subtly different implementation — see `formatMoney`'s doc
   * in `@/lib/format` for the three divergences D5 resolved against it. */
  formatAud,
} from "@/lib/format";

/** Tenant lifecycle status -> Badge variant. active=green, trial=gold/amber,
 * suspended=red. Same "one switch per status field" convention as
 * `pages/duress/format.ts`'s `statusBadgeVariant`. */
export function tenantStatusBadgeVariant(
  status: string,
): "default" | "primary" | "accent" | "success" | "destructive" | "outline" {
  switch (status) {
    case "active":
      return "success";
    case "trial":
      return "accent";
    case "suspended":
      return "destructive";
    default:
      return "default";
  }
}
