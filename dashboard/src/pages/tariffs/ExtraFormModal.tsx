import { useEffect, useState } from "react";
import { AlertTriangle } from "lucide-react";
import { Button, Input, Modal, Select } from "@/components/ui";
import {
  useCreateExtraMutation,
  useUpdateExtraMutation,
  type Extra,
  type ExtraCreateInput,
  type ExtraType,
} from "@/hooks/useTariffStudio";
import { extractErrorMessage } from "./format";

export interface ExtraFormModalProps {
  open: boolean;
  onClose: () => void;
  mode: "create" | "edit";
  tariffId: string;
  extra?: Extra;
}

interface FormState {
  name: string;
  amount: string;
  type: ExtraType;
}

const EXTRA_TYPE_OPTIONS: { value: ExtraType; label: string }[] = [
  { value: "fixed", label: "Fixed" },
  { value: "passthrough", label: "Passthrough" },
];

function emptyForm(): FormState {
  return { name: "", amount: "", type: "fixed" };
}

function formFromExtra(extra: Extra): FormState {
  return { name: extra.name, amount: extra.amount, type: extra.type };
}

/** Create/edit modal for a single named Extra (fixed/passthrough fee) scoped
 * to one tariff — `POST`/`PATCH /v1/tariffs/{tariffId}/extras[/{id}]`. Opened
 * from `ExtrasSection`, which lives inside `TariffFormModal`'s edit-mode
 * body — this modal renders via a portal (see `components/ui/Modal`), so it
 * is never actually nested inside that outer `<form>` in the DOM. */
export function ExtraFormModal({ open, onClose, mode, tariffId, extra }: ExtraFormModalProps) {
  const [form, setForm] = useState<FormState>(extra ? formFromExtra(extra) : emptyForm());
  const [error, setError] = useState<string | null>(null);

  const createMutation = useCreateExtraMutation();
  const updateMutation = useUpdateExtraMutation();
  const isPending = createMutation.isPending || updateMutation.isPending;

  useEffect(() => {
    if (open) {
      setForm(extra ? formFromExtra(extra) : emptyForm());
      setError(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, extra?.id]);

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (!form.name.trim()) {
      setError("Name is required.");
      return;
    }
    if (form.amount.trim() === "" || Number.isNaN(Number(form.amount))) {
      setError("Amount must be a number.");
      return;
    }

    const payload: ExtraCreateInput = {
      name: form.name.trim(),
      amount: form.amount.trim(),
      type: form.type,
    };

    try {
      if (mode === "create") {
        await createMutation.mutateAsync({ tariffId, input: payload });
      } else {
        if (!extra) return;
        await updateMutation.mutateAsync({ tariffId, extraId: extra.id, input: payload });
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
      title={mode === "create" ? "New extra" : `Edit extra — ${extra?.name}`}
      description="A named fixed or passthrough fee (e.g. a cleaning fee or equipment surcharge) scoped to this tariff."
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button type="submit" form="extra-form" disabled={isPending}>
            {isPending ? "Saving…" : mode === "create" ? "Create extra" : "Save changes"}
          </Button>
        </>
      }
    >
      <form id="extra-form" onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Name</label>
          <Input
            value={form.name}
            onChange={(e) => update("name", e.target.value)}
            placeholder="e.g. Cleaning fee"
            required
          />
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Amount</label>
            <Input
              inputMode="decimal"
              value={form.amount}
              onChange={(e) => update("amount", e.target.value)}
              placeholder="e.g. 25.00"
              required
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Type</label>
            <Select
              options={EXTRA_TYPE_OPTIONS}
              value={form.type}
              onChange={(e) => update("type", e.target.value as ExtraType)}
              required
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
