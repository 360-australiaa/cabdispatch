import { useEffect, useState } from "react";
import { AlertTriangle } from "lucide-react";
import { Button, Input, Modal, Select } from "@/components/ui";
import {
  useCreateWalletTransactionMutation,
  type DriverOption,
  type OperatorWalletKind,
  type WalletTransactionCreateInput,
} from "./hooks";
import { extractErrorMessage } from "./format";

export interface WalletTransactionFormModalProps {
  open: boolean;
  onClose: () => void;
  driver: DriverOption;
}

interface FormState {
  kind: OperatorWalletKind;
  /** Always entered as a positive number; the sign is derived from `kind`
   * (+ for top_up, - for payout) or from `direction` for adjustments. */
  amount: string;
  direction: "credit" | "debit";
  reference: string;
  note: string;
}

const KIND_OPTIONS: { value: OperatorWalletKind; label: string }[] = [
  { value: "top_up", label: "Top-up (credit the driver)" },
  { value: "payout", label: "Payout (money paid out to the driver)" },
  { value: "adjustment", label: "Adjustment (correction, either direction)" },
];

const DIRECTION_OPTIONS = [
  { value: "credit", label: "Credit (+)" },
  { value: "debit", label: "Debit (−)" },
];

function emptyForm(): FormState {
  return { kind: "top_up", amount: "", direction: "credit", reference: "", note: "" };
}

/** Post one ledger line to a driver's wallet — `POST /v1/wallet/transactions`
 * (owner/admin). The API takes a SIGNED `amount_aud`; this form asks for a
 * positive amount and applies the sign so an operator can't accidentally
 * post a negative top-up. */
export function WalletTransactionFormModal({ open, onClose, driver }: WalletTransactionFormModalProps) {
  const [form, setForm] = useState<FormState>(emptyForm());
  const [error, setError] = useState<string | null>(null);
  const mutation = useCreateWalletTransactionMutation();

  useEffect(() => {
    if (open) {
      setForm(emptyForm());
      setError(null);
    }
  }, [open, driver.id]);

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  const signed = (() => {
    const raw = form.amount.trim();
    if (raw === "") return null;
    const n = Number(raw);
    if (Number.isNaN(n) || n <= 0) return null;
    const negative = form.kind === "payout" || (form.kind === "adjustment" && form.direction === "debit");
    return negative ? `-${raw}` : raw;
  })();

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (signed == null) {
      setError("Amount (AUD) must be a positive number — the direction is set by the kind.");
      return;
    }
    const payload: WalletTransactionCreateInput = {
      driver_id: driver.id,
      amount_aud: signed,
      kind: form.kind,
      reference: form.reference.trim() || null,
      note: form.note.trim() || null,
    };
    try {
      await mutation.mutateAsync(payload);
      onClose();
    } catch (err) {
      setError(extractErrorMessage(err));
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={`Post wallet transaction — ${driver.name}`}
      description="Adds one signed line to the driver's ledger. The balance is always the sum of the ledger, so a mistake is corrected with an opposite adjustment, not by editing history."
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={mutation.isPending}>
            Cancel
          </Button>
          <Button type="submit" form="wallet-transaction-form" disabled={mutation.isPending}>
            {mutation.isPending ? "Posting…" : "Post transaction"}
          </Button>
        </>
      }
    >
      <form id="wallet-transaction-form" onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Kind</label>
          <Select
            options={KIND_OPTIONS}
            value={form.kind}
            onChange={(e) => update("kind", e.target.value as OperatorWalletKind)}
          />
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Amount (AUD)</label>
            <Input
              inputMode="decimal"
              value={form.amount}
              onChange={(e) => update("amount", e.target.value)}
              placeholder="e.g. 50.00"
              required
            />
          </div>
          {form.kind === "adjustment" ? (
            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-muted-foreground">Direction</label>
              <Select
                options={DIRECTION_OPTIONS}
                value={form.direction}
                onChange={(e) => update("direction", e.target.value as "credit" | "debit")}
              />
            </div>
          ) : (
            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-muted-foreground">Applied as</label>
              <p className="flex h-10 items-center text-sm text-muted-foreground">
                {form.kind === "payout" ? "Debit (−) — money leaving the wallet" : "Credit (+) — added to the wallet"}
              </p>
            </div>
          )}
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Reference (optional)</label>
            <Input
              value={form.reference}
              onChange={(e) => update("reference", e.target.value)}
              placeholder="e.g. bank ref / trip id"
              maxLength={100}
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Note (optional)</label>
            <Input
              value={form.note}
              onChange={(e) => update("note", e.target.value)}
              placeholder="Why this was posted"
              maxLength={2000}
            />
          </div>
        </div>

        {signed != null && (
          <p className="text-sm text-muted-foreground">
            Ledger line to post: <span className="font-mono font-medium text-foreground">{signed}</span> AUD
          </p>
        )}

        {error && (
          <div className="flex items-start gap-2 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{error}</span>
          </div>
        )}
      </form>
    </Modal>
  );
}
