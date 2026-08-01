import { useEffect, useState } from "react";
import axios from "axios";
import { Button, Input, Modal, Select } from "@/components/ui";
import {
  useCreateLedgerEntryMutation,
  useUpdateLedgerEntryMutation,
  type PSLLedgerEntry,
} from "@/hooks/usePSLCentre";
import type { DriverLite } from "@/hooks/useTrips";
import { currentPeriod } from "./format";

export interface LedgerFormModalProps {
  open: boolean;
  onClose: () => void;
  mode: "create" | "edit";
  entry?: PSLLedgerEntry;
  drivers: DriverLite[];
}

interface FormState {
  driver_id: string;
  period: string;
  trips_count: string;
  amount_owed: string;
  amount_collected: string;
  remitted: boolean;
  remitted_at: string;
}

function emptyForm(): FormState {
  return {
    driver_id: "",
    period: currentPeriod(),
    trips_count: "0",
    amount_owed: "0.00",
    amount_collected: "0.00",
    remitted: false,
    remitted_at: "",
  };
}

function formFromEntry(entry: PSLLedgerEntry): FormState {
  return {
    driver_id: entry.driver_id,
    period: entry.period,
    trips_count: String(entry.trips_count),
    amount_owed: entry.amount_owed,
    amount_collected: entry.amount_collected,
    remitted: entry.remitted_at != null,
    remitted_at: entry.remitted_at ? entry.remitted_at.slice(0, 10) : "",
  };
}

function extractErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const detail = err.response?.data?.detail;
    if (typeof detail === "string") return detail;
    if (Array.isArray(detail)) {
      return detail.map((d) => d.msg ?? JSON.stringify(d)).join("; ");
    }
    return err.message;
  }
  return err instanceof Error ? err.message : "Something went wrong";
}

/** Create/edit modal for a psl_ledger row. Create sends the full
 * PSLLedgerCreate shape (driver + period are immutable once created, so
 * they're locked in edit mode); edit only sends the PSLLedgerUpdate fields. */
export function LedgerFormModal({ open, onClose, mode, entry, drivers }: LedgerFormModalProps) {
  const [form, setForm] = useState<FormState>(entry ? formFromEntry(entry) : emptyForm());
  const [error, setError] = useState<string | null>(null);

  const createMutation = useCreateLedgerEntryMutation();
  const updateMutation = useUpdateLedgerEntryMutation();
  const isPending = createMutation.isPending || updateMutation.isPending;

  useEffect(() => {
    if (open) {
      setForm(entry ? formFromEntry(entry) : emptyForm());
      setError(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, entry?.id]);

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    const tripsCount = Number(form.trips_count);
    if (Number.isNaN(tripsCount) || tripsCount < 0) {
      setError("Trip count must be a non-negative number.");
      return;
    }
    if (form.amount_owed.trim() === "" || Number.isNaN(Number(form.amount_owed))) {
      setError("Amount owed must be a number.");
      return;
    }
    if (form.amount_collected.trim() === "" || Number.isNaN(Number(form.amount_collected))) {
      setError("Amount collected must be a number.");
      return;
    }
    const remitted_at = form.remitted ? new Date(`${form.remitted_at || new Date().toISOString().slice(0, 10)}T00:00:00Z`).toISOString() : null;

    if (mode === "create") {
      if (!form.driver_id || !form.period) {
        setError("Driver and period are required.");
        return;
      }
      try {
        await createMutation.mutateAsync({
          driver_id: form.driver_id,
          period: form.period,
          trips_count: tripsCount,
          amount_owed: form.amount_owed,
          amount_collected: form.amount_collected,
          remitted_at,
        });
        onClose();
      } catch (err) {
        setError(extractErrorMessage(err));
      }
    } else {
      if (!entry) return;
      try {
        await updateMutation.mutateAsync({
          id: entry.id,
          input: {
            trips_count: tripsCount,
            amount_owed: form.amount_owed,
            amount_collected: form.amount_collected,
            remitted_at,
          },
        });
        onClose();
      } catch (err) {
        setError(extractErrorMessage(err));
      }
    }
  }

  const driverOptions = drivers.map((d) => ({ value: d.id, label: d.name }));

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={mode === "create" ? "New PSL ledger entry" : "Edit PSL ledger entry"}
      description={
        mode === "create"
          ? "Accrues a driver's PSL liability for a period. One entry per driver/period."
          : "Driver and period are locked once an entry exists — correct the accrual/collection amounts or mark it remitted."
      }
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button type="submit" form="psl-ledger-form" disabled={isPending}>
            {isPending ? "Saving…" : mode === "create" ? "Create entry" : "Save changes"}
          </Button>
        </>
      }
    >
      <form
        id="psl-ledger-form"
        onSubmit={handleSubmit}
        className="grid grid-cols-1 gap-4 sm:grid-cols-2"
      >
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Driver</label>
          {mode === "create" ? (
            <Select
              options={driverOptions}
              placeholder="Select driver"
              value={form.driver_id}
              onChange={(e) => update("driver_id", e.target.value)}
              required
            />
          ) : (
            <Input
              value={drivers.find((d) => d.id === form.driver_id)?.name ?? form.driver_id}
              disabled
            />
          )}
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Period</label>
          <Input
            type="month"
            value={form.period}
            onChange={(e) => update("period", e.target.value)}
            required
            disabled={mode === "edit"}
          />
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Trips count</label>
          <Input
            value={form.trips_count}
            onChange={(e) => update("trips_count", e.target.value)}
            inputMode="numeric"
          />
        </div>
        <div />
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Amount owed ($)</label>
          <Input
            value={form.amount_owed}
            onChange={(e) => update("amount_owed", e.target.value)}
            inputMode="decimal"
          />
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Amount collected ($)</label>
          <Input
            value={form.amount_collected}
            onChange={(e) => update("amount_collected", e.target.value)}
            inputMode="decimal"
          />
        </div>
        <div className="flex items-center gap-2 pt-5">
          <input
            id="remitted"
            type="checkbox"
            checked={form.remitted}
            onChange={(e) => update("remitted", e.target.checked)}
            className="h-4 w-4 rounded border-input"
          />
          <label htmlFor="remitted" className="text-sm text-foreground">
            Remitted to regulator
          </label>
        </div>
        {form.remitted && (
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Remitted on</label>
            <Input
              type="date"
              value={form.remitted_at}
              onChange={(e) => update("remitted_at", e.target.value)}
            />
          </div>
        )}

        {error && (
          <p className="sm:col-span-2 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
            {error}
          </p>
        )}
      </form>
    </Modal>
  );
}
