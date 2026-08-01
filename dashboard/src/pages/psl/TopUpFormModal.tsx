import { useEffect, useState } from "react";
import axios from "axios";
import { Button, Input, Modal, Select } from "@/components/ui";
import { useCreateTopUpMutation } from "@/hooks/usePSLCentre";
import type { DriverLite } from "@/hooks/useTrips";
import { currentPeriod, PAYMENT_METHOD_OPTIONS } from "./format";

export interface TopUpFormModalProps {
  open: boolean;
  onClose: () => void;
  drivers: DriverLite[];
  /** Pre-fill from the current ledger filters when the modal is opened from the toolbar. */
  defaultDriverId?: string;
  defaultPeriod?: string;
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

/** Records a driver's PSL top-up payment (POST /v1/psl/topup). This is an
 * append-only action — there's no edit/delete endpoint for top-ups, matching
 * the backend contract (a top-up is a payment record, not a draft). */
export function TopUpFormModal({
  open,
  onClose,
  drivers,
  defaultDriverId,
  defaultPeriod,
}: TopUpFormModalProps) {
  const [driverId, setDriverId] = useState("");
  const [period, setPeriod] = useState(currentPeriod());
  const [amount, setAmount] = useState("");
  const [paymentMethod, setPaymentMethod] = useState("card");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const createMutation = useCreateTopUpMutation();

  useEffect(() => {
    if (open) {
      setDriverId(defaultDriverId || "");
      setPeriod(defaultPeriod || currentPeriod());
      setAmount("");
      setPaymentMethod("card");
      setError(null);
      setSuccess(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, defaultDriverId, defaultPeriod]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSuccess(null);

    if (!driverId || !period) {
      setError("Driver and period are required.");
      return;
    }
    const amountNum = Number(amount);
    if (!amount || Number.isNaN(amountNum) || amountNum <= 0) {
      setError("Amount must be a positive number.");
      return;
    }
    try {
      const topup = await createMutation.mutateAsync({
        driver_id: driverId,
        period,
        amount,
        payment_method: paymentMethod,
      });
      setSuccess(`Top-up recorded — status ${topup.status}.`);
      setAmount("");
    } catch (err) {
      setError(extractErrorMessage(err));
    }
  }

  const driverOptions = drivers.map((d) => ({ value: d.id, label: d.name }));

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Record PSL top-up"
      description="Logs a driver's PSL payment for a period. This tops up amount_collected on their ledger entry for that period."
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose}>
            Close
          </Button>
          <Button type="submit" form="psl-topup-form" disabled={createMutation.isPending}>
            {createMutation.isPending ? "Recording…" : "Record top-up"}
          </Button>
        </>
      }
    >
      <form id="psl-topup-form" onSubmit={handleSubmit} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="flex flex-col gap-1 sm:col-span-2">
          <label className="text-xs font-medium text-muted-foreground">Driver</label>
          <Select
            options={driverOptions}
            placeholder="Select driver"
            value={driverId}
            onChange={(e) => setDriverId(e.target.value)}
            required
          />
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Period</label>
          <Input type="month" value={period} onChange={(e) => setPeriod(e.target.value)} required />
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Amount ($)</label>
          <Input
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            inputMode="decimal"
            placeholder="0.00"
            required
          />
        </div>
        <div className="flex flex-col gap-1 sm:col-span-2">
          <label className="text-xs font-medium text-muted-foreground">Payment method</label>
          <Select
            options={PAYMENT_METHOD_OPTIONS}
            value={paymentMethod}
            onChange={(e) => setPaymentMethod(e.target.value)}
          />
        </div>

        {error && (
          <p className="sm:col-span-2 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
            {error}
          </p>
        )}
        {success && (
          <p className="sm:col-span-2 rounded-md bg-success/10 px-3 py-2 text-sm text-success">
            {success}
          </p>
        )}
      </form>
    </Modal>
  );
}
