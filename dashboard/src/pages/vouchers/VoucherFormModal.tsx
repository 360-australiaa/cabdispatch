import { useEffect, useState } from "react";
import { AlertTriangle } from "lucide-react";
import { Button, Input, Modal } from "@/components/ui";
import {
  useCreateVoucherMutation,
  useUpdateVoucherMutation,
  type Voucher,
  type VoucherCreateInput,
} from "./hooks";
import { extractErrorMessage, fromDatetimeLocalValue, toDatetimeLocalValue } from "./format";

export interface VoucherFormModalProps {
  open: boolean;
  onClose: () => void;
  mode: "create" | "edit";
  voucher?: Voucher;
}

interface FormState {
  code: string;
  value_aud: string;
  expires_at: string; // datetime-local value, "" = no expiry
}

function emptyForm(): FormState {
  return { code: "", value_aud: "", expires_at: "" };
}

function formFromVoucher(voucher: Voucher): FormState {
  return {
    code: voucher.code,
    value_aud: voucher.value_aud,
    expires_at: toDatetimeLocalValue(voucher.expires_at),
  };
}

/** Create/edit modal for a single Voucher — `POST`/`PATCH /v1/vouchers[/{id}]`.
 * `code` is immutable after creation (the backend rejects changing it, since
 * redemption looks vouchers up by it) — disabled in edit mode. */
export function VoucherFormModal({ open, onClose, mode, voucher }: VoucherFormModalProps) {
  const [form, setForm] = useState<FormState>(voucher ? formFromVoucher(voucher) : emptyForm());
  const [error, setError] = useState<string | null>(null);

  const createMutation = useCreateVoucherMutation();
  const updateMutation = useUpdateVoucherMutation();
  const isPending = createMutation.isPending || updateMutation.isPending;

  useEffect(() => {
    if (open) {
      setForm(voucher ? formFromVoucher(voucher) : emptyForm());
      setError(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, voucher?.id]);

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (!form.code.trim()) {
      setError("Code is required.");
      return;
    }
    const amount = Number(form.value_aud);
    if (form.value_aud.trim() === "" || Number.isNaN(amount) || amount <= 0) {
      setError("Value (AUD) must be a positive number.");
      return;
    }

    try {
      if (mode === "create") {
        const payload: VoucherCreateInput = {
          code: form.code.trim(),
          value_aud: form.value_aud.trim(),
          expires_at: fromDatetimeLocalValue(form.expires_at) ?? null,
        };
        await createMutation.mutateAsync(payload);
      } else {
        if (!voucher) return;
        await updateMutation.mutateAsync({
          id: voucher.id,
          input: {
            value_aud: form.value_aud.trim(),
            expires_at: fromDatetimeLocalValue(form.expires_at) ?? null,
          },
        });
      }
      onClose();
    } catch (err) {
      setError(extractErrorMessage(err));
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={mode === "create" ? "New voucher" : `Edit voucher — ${voucher?.code}`}
      description="A promo code / prepaid voucher redeemable once against a trip's total (payment_method = 'voucher')."
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button type="submit" form="voucher-form" disabled={isPending}>
            {isPending ? "Saving…" : mode === "create" ? "Create voucher" : "Save changes"}
          </Button>
        </>
      }
    >
      <form id="voucher-form" onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Code</label>
          <Input
            value={form.code}
            onChange={(e) => update("code", e.target.value.toUpperCase())}
            placeholder="e.g. WELCOME10"
            disabled={mode === "edit"}
            required
          />
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Value (AUD)</label>
            <Input
              inputMode="decimal"
              value={form.value_aud}
              onChange={(e) => update("value_aud", e.target.value)}
              placeholder="e.g. 10.00"
              required
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Expires (optional)</label>
            <Input
              type="datetime-local"
              value={form.expires_at}
              onChange={(e) => update("expires_at", e.target.value)}
            />
          </div>
        </div>

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
