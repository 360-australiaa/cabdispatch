import { useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { Button, Checkbox, Input, Select, Sheet } from "@/components/ui";
import { errorMessage } from "@/lib/format";
import {
  useCloseTripMutation,
  type PaymentMethod,
  type SplitPaymentItem,
  type Trip,
} from "@/hooks/useTrips";
import { PAYMENT_METHOD_OPTIONS } from "./format";

export interface CloseTripSheetProps {
  open: boolean;
  onClose: () => void;
  trip: Trip;
}

/**
 * Trip page "Close" action (dashboard command-centre plan §7) -- the
 * close-with-split-payment-legs editor `TripDetailModal` already had,
 * ported as-is into a standalone `Sheet` opened from the page's own
 * actions instead of living inside a row-detail modal. Logic (validation,
 * the mutation call) is unchanged from `TripDetailModal.handleClose`.
 */
export function CloseTripSheet({ open, onClose, trip }: CloseTripSheetProps) {
  const [surchargePct, setSurchargePct] = useState("");
  const [cleaningFee, setCleaningFee] = useState("0");
  const [includePsl, setIncludePsl] = useState(false);
  const [closePaymentMethod, setClosePaymentMethod] = useState<string>("");
  const [closeVoucherCode, setCloseVoucherCode] = useState("");
  const [closeAccountReference, setCloseAccountReference] = useState("");
  const [closeSplitPayments, setCloseSplitPayments] = useState<SplitPaymentItem[]>([]);
  const [closeError, setCloseError] = useState<string | null>(null);

  const closeMutation = useCloseTripMutation();

  function reset() {
    setSurchargePct("");
    setCleaningFee("0");
    setIncludePsl(false);
    setClosePaymentMethod("");
    setCloseVoucherCode("");
    setCloseAccountReference("");
    setCloseSplitPayments([]);
    setCloseError(null);
  }

  function handleClose() {
    reset();
    onClose();
  }

  async function handleSubmit() {
    setCloseError(null);

    const method = closePaymentMethod ? (closePaymentMethod as PaymentMethod) : undefined;
    if (method === "voucher" && !closeVoucherCode.trim()) {
      setCloseError("Voucher code is required for the voucher payment method.");
      return;
    }
    if (method === "account" && !closeAccountReference.trim()) {
      setCloseError("Account reference is required for the account payment method.");
      return;
    }
    if (method === "split_fare") {
      if (closeSplitPayments.length < 2) {
        setCloseError("Split fare needs at least two payment legs.");
        return;
      }
      for (const leg of closeSplitPayments) {
        if (!leg.method.trim() || !leg.amount.trim() || Number.isNaN(Number(leg.amount))) {
          setCloseError("Every split-fare leg needs a method and a valid amount.");
          return;
        }
      }
    }

    try {
      await closeMutation.mutateAsync({
        id: trip.id,
        input: {
          surcharge_pct: surchargePct || undefined,
          cleaning_fee: cleaningFee || "0",
          include_psl: includePsl,
          payment_method: method,
          voucher_code: method === "voucher" ? closeVoucherCode.trim() : undefined,
          account_reference: method === "account" ? closeAccountReference.trim() : undefined,
          split_payments: method === "split_fare" ? closeSplitPayments : undefined,
        },
      });
      handleClose();
    } catch (err) {
      setCloseError(errorMessage(err));
    }
  }

  function addSplitLeg() {
    setCloseSplitPayments((legs) => [...legs, { method: "cash", amount: "" }]);
  }
  function removeSplitLeg(index: number) {
    setCloseSplitPayments((legs) => legs.filter((_, i) => i !== index));
  }
  function updateSplitLeg(index: number, patch: Partial<SplitPaymentItem>) {
    setCloseSplitPayments((legs) => legs.map((leg, i) => (i === index ? { ...leg, ...patch } : leg)));
  }

  return (
    <Sheet
      open={open}
      onClose={handleClose}
      title="Close trip & run fare engine"
      description={`Trip ${trip.id.slice(0, 8)}`}
      footer={
        <>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={closeMutation.isPending}>
            {closeMutation.isPending ? "Closing…" : "Confirm close"}
          </Button>
        </>
      }
    >
      <div className="space-y-2">
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          <Input
            placeholder="Surcharge %"
            value={surchargePct}
            onChange={(e) => setSurchargePct(e.target.value)}
            inputMode="decimal"
          />
          <Input
            placeholder="Cleaning fee"
            value={cleaningFee}
            onChange={(e) => setCleaningFee(e.target.value)}
            inputMode="decimal"
          />
          <Select
            options={PAYMENT_METHOD_OPTIONS}
            placeholder="Payment"
            value={closePaymentMethod}
            onChange={(e) => setClosePaymentMethod(e.target.value)}
          />
          <Checkbox label="Include PSL" checked={includePsl} onChange={(e) => setIncludePsl(e.target.checked)} />
        </div>
        {closePaymentMethod === "voucher" && (
          <Input
            placeholder="Voucher code"
            value={closeVoucherCode}
            onChange={(e) => setCloseVoucherCode(e.target.value)}
          />
        )}
        {closePaymentMethod === "account" && (
          <Input
            placeholder="Account reference"
            value={closeAccountReference}
            onChange={(e) => setCloseAccountReference(e.target.value)}
          />
        )}
        {closePaymentMethod === "split_fare" && (
          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-medium text-muted-foreground">
                Split-fare legs (must sum to the total)
              </span>
              <Button type="button" variant="outline" size="sm" onClick={addSplitLeg}>
                <Plus className="h-3.5 w-3.5" /> Add leg
              </Button>
            </div>
            {closeSplitPayments.map((leg, index) => (
              <div key={index} className="flex items-center gap-2">
                <Select
                  className="w-28"
                  options={[
                    { value: "cash", label: "Cash" },
                    { value: "card", label: "Card" },
                  ]}
                  value={leg.method}
                  onChange={(e) => updateSplitLeg(index, { method: e.target.value })}
                />
                <Input
                  className="w-28"
                  inputMode="decimal"
                  placeholder="Amount"
                  value={leg.amount}
                  onChange={(e) => updateSplitLeg(index, { amount: e.target.value })}
                />
                <Button type="button" variant="ghost" size="icon" onClick={() => removeSplitLeg(index)}>
                  <Trash2 className="h-4 w-4 text-destructive" />
                </Button>
              </div>
            ))}
          </div>
        )}
        {closeError && <p className="text-sm text-destructive">{closeError}</p>}
      </div>
    </Sheet>
  );
}
