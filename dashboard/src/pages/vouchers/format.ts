/** Display formatting for the Vouchers page.
 *
 * The `format*` helpers below are re-exported from `@/lib/format`, which is
 * now the single implementation of each (see that module's header: this file
 * used to carry its own copy, one of 12 near-identical ones across the
 * per-page `format.ts` modules). Only the page-specific helpers below are local.
 */

export {
  formatMoney,
  formatDateTime,
  toDatetimeLocalValue,
  fromDatetimeLocalValue,
  extractErrorMessage,
} from "@/lib/format";

export function voucherStatus(voucher: { redeemed_at: string | null; expires_at: string | null }): {
  label: string;
  variant: "success" | "outline" | "destructive";
} {
  if (voucher.redeemed_at) return { label: "Redeemed", variant: "outline" };
  if (voucher.expires_at && new Date(voucher.expires_at).getTime() <= Date.now()) {
    return { label: "Expired", variant: "destructive" };
  }
  return { label: "Available", variant: "success" };
}
