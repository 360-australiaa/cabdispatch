import { useEffect, useState } from "react";
import { AlertTriangle } from "lucide-react";
import { Button, Input, Modal } from "@/components/ui";
import {
  useCreateIncentiveMutation,
  useUpdateIncentiveMutation,
  type Incentive,
  type IncentiveCreateInput,
} from "./hooks";
import { extractErrorMessage, fromDatetimeLocalValue, toDatetimeLocalValue } from "./format";

export interface IncentiveFormModalProps {
  open: boolean;
  onClose: () => void;
  mode: "create" | "edit";
  incentive?: Incentive;
}

interface FormState {
  title: string;
  description: string;
  target_trips: string;
  reward_aud: string;
  starts_at: string; // datetime-local
  ends_at: string; // datetime-local
  active: boolean;
}

function emptyForm(): FormState {
  const now = new Date();
  const weekOut = new Date(now.getTime() + 7 * 24 * 60 * 60 * 1000);
  return {
    title: "",
    description: "",
    target_trips: "",
    reward_aud: "",
    starts_at: toDatetimeLocalValue(now.toISOString()),
    ends_at: toDatetimeLocalValue(weekOut.toISOString()),
    active: true,
  };
}

function formFromIncentive(i: Incentive): FormState {
  return {
    title: i.title,
    description: i.description ?? "",
    target_trips: String(i.target_trips),
    reward_aud: i.reward_aud,
    starts_at: toDatetimeLocalValue(i.starts_at),
    ends_at: toDatetimeLocalValue(i.ends_at),
    active: i.active,
  };
}

/** Create/edit modal for one Incentive — `POST`/`PATCH /v1/incentives[/{id}]`.
 * Progress is never entered here: the driver tablet derives it from the
 * driver's real closed trips inside the window (`GET /v1/me/incentives`). */
export function IncentiveFormModal({ open, onClose, mode, incentive }: IncentiveFormModalProps) {
  const [form, setForm] = useState<FormState>(incentive ? formFromIncentive(incentive) : emptyForm());
  const [error, setError] = useState<string | null>(null);

  const createMutation = useCreateIncentiveMutation();
  const updateMutation = useUpdateIncentiveMutation();
  const isPending = createMutation.isPending || updateMutation.isPending;

  useEffect(() => {
    if (open) {
      setForm(incentive ? formFromIncentive(incentive) : emptyForm());
      setError(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, incentive?.id]);

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (!form.title.trim()) {
      setError("Title is required.");
      return;
    }
    const target = Number(form.target_trips);
    if (!Number.isInteger(target) || target <= 0) {
      setError("Target trips must be a whole number greater than zero.");
      return;
    }
    const reward = Number(form.reward_aud);
    if (form.reward_aud.trim() === "" || Number.isNaN(reward) || reward <= 0) {
      setError("Reward (AUD) must be a positive number.");
      return;
    }
    const startsAt = fromDatetimeLocalValue(form.starts_at);
    const endsAt = fromDatetimeLocalValue(form.ends_at);
    if (!startsAt || !endsAt) {
      setError("Start and end times are both required.");
      return;
    }
    if (new Date(endsAt).getTime() <= new Date(startsAt).getTime()) {
      setError("End time must be after the start time.");
      return;
    }

    const payload: IncentiveCreateInput = {
      title: form.title.trim(),
      description: form.description.trim() || null,
      target_trips: target,
      reward_aud: form.reward_aud.trim(),
      starts_at: startsAt,
      ends_at: endsAt,
      active: form.active,
    };

    try {
      if (mode === "create") {
        await createMutation.mutateAsync(payload);
      } else {
        if (!incentive) return;
        await updateMutation.mutateAsync({ id: incentive.id, input: payload });
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
      title={mode === "create" ? "New incentive" : `Edit incentive — ${incentive?.title}`}
      description="'Complete N trips between these dates and earn $X.' Each driver's progress is counted live from their closed trips."
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button type="submit" form="incentive-form" disabled={isPending}>
            {isPending ? "Saving…" : mode === "create" ? "Create incentive" : "Save changes"}
          </Button>
        </>
      }
    >
      <form id="incentive-form" onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Title</label>
          <Input
            value={form.title}
            onChange={(e) => update("title", e.target.value)}
            placeholder="e.g. Weekend push — 20 trips"
            maxLength={200}
            required
          />
        </div>

        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Description (optional)</label>
          <textarea
            className="flex min-h-[72px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            value={form.description}
            onChange={(e) => update("description", e.target.value)}
            placeholder="Shown under the title on the driver tablet"
            maxLength={5000}
          />
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Target trips</label>
            <Input
              inputMode="numeric"
              value={form.target_trips}
              onChange={(e) => update("target_trips", e.target.value)}
              placeholder="e.g. 20"
              required
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Reward (AUD)</label>
            <Input
              inputMode="decimal"
              value={form.reward_aud}
              onChange={(e) => update("reward_aud", e.target.value)}
              placeholder="e.g. 50.00"
              required
            />
          </div>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Starts</label>
            <Input
              type="datetime-local"
              value={form.starts_at}
              onChange={(e) => update("starts_at", e.target.value)}
              required
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Ends</label>
            <Input
              type="datetime-local"
              value={form.ends_at}
              onChange={(e) => update("ends_at", e.target.value)}
              required
            />
          </div>
        </div>

        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            className="h-4 w-4 rounded border-input"
            checked={form.active}
            onChange={(e) => update("active", e.target.checked)}
          />
          Active (uncheck to hide from drivers without deleting)
        </label>

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
