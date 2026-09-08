import { useEffect, useState } from "react";
import { AlertTriangle } from "lucide-react";
import { Button, Input, Modal } from "@/components/ui";
import {
  useCreateCorporateAccountMutation,
  useUpdateCorporateAccountMutation,
  type CorporateAccount,
  type CorporateAccountCreateInput,
} from "./hooks";
import { extractErrorMessage } from "./format";

export interface CorporateAccountFormModalProps {
  open: boolean;
  onClose: () => void;
  mode: "create" | "edit";
  account?: CorporateAccount;
}

interface FormState {
  reference: string;
  company_name: string;
  active: boolean;
}

function emptyForm(): FormState {
  return { reference: "", company_name: "", active: true };
}

function formFromAccount(account: CorporateAccount): FormState {
  return { reference: account.reference, company_name: account.company_name, active: account.active };
}

/** Create/edit modal for a single CorporateAccount —
 * `POST`/`PATCH /v1/corporate-accounts[/{id}]`. `reference` is immutable
 * after creation (the backend rejects changing it, since payment validation
 * looks accounts up by it) — disabled in edit mode. */
export function CorporateAccountFormModal({ open, onClose, mode, account }: CorporateAccountFormModalProps) {
  const [form, setForm] = useState<FormState>(account ? formFromAccount(account) : emptyForm());
  const [error, setError] = useState<string | null>(null);

  const createMutation = useCreateCorporateAccountMutation();
  const updateMutation = useUpdateCorporateAccountMutation();
  const isPending = createMutation.isPending || updateMutation.isPending;

  useEffect(() => {
    if (open) {
      setForm(account ? formFromAccount(account) : emptyForm());
      setError(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, account?.id]);

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (!form.reference.trim()) {
      setError("Reference is required.");
      return;
    }
    if (!form.company_name.trim()) {
      setError("Company name is required.");
      return;
    }

    try {
      if (mode === "create") {
        const payload: CorporateAccountCreateInput = {
          reference: form.reference.trim(),
          company_name: form.company_name.trim(),
          active: form.active,
        };
        await createMutation.mutateAsync(payload);
      } else {
        if (!account) return;
        await updateMutation.mutateAsync({
          id: account.id,
          input: { company_name: form.company_name.trim(), active: form.active },
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
      title={mode === "create" ? "New corporate account" : `Edit corporate account — ${account?.reference}`}
      description="A pre-registered, pay-later/invoiced corporate account (payment_method = 'account')."
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button type="submit" form="corporate-account-form" disabled={isPending}>
            {isPending ? "Saving…" : mode === "create" ? "Create account" : "Save changes"}
          </Button>
        </>
      }
    >
      <form id="corporate-account-form" onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Reference</label>
          <Input
            value={form.reference}
            onChange={(e) => update("reference", e.target.value.toUpperCase())}
            placeholder="e.g. ACME-CORP-0042"
            disabled={mode === "edit"}
            required
          />
        </div>

        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Company name</label>
          <Input
            value={form.company_name}
            onChange={(e) => update("company_name", e.target.value)}
            placeholder="e.g. Acme Pty Ltd"
            required
          />
        </div>

        {mode === "edit" && (
          <label className="flex items-center gap-2 text-sm text-foreground">
            <input
              type="checkbox"
              className="h-4 w-4 rounded border-input"
              checked={form.active}
              onChange={(e) => update("active", e.target.checked)}
            />
            Active
          </label>
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
