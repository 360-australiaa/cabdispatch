import { useState, type FormEvent, type ReactNode } from "react";
import { useMutation } from "@tanstack/react-query";
import { Button, Input, Modal } from "@/components/ui";
import { createJob } from "./api";
import type { Job } from "./types";

const EMPTY_FORM = {
  originAddress: "",
  originLat: "",
  originLng: "",
  destAddress: "",
  destLat: "",
  destLng: "",
  fareLow: "",
  fareHigh: "",
};

/**
 * Opens a new job via `POST /v1/jobs` — this is what actually broadcasts a
 * `JobOffer` to every currently-available driver (toggled available + open
 * shift + not mid-trip, see `app/services/jobs.py`) over `WS /v1/jobs/live`,
 * which the Android app's Available Trips wheel slot is already listening
 * on. This modal is the dashboard-side half of that loop that didn't exist
 * before — dispatch could never actually create a job to test against.
 */
export function CreateJobModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (job: Job) => void;
}) {
  const [form, setForm] = useState(EMPTY_FORM);

  const mutation = useMutation({
    mutationFn: () =>
      createJob({
        origin_address: form.originAddress,
        origin_lat: Number.parseFloat(form.originLat),
        origin_lng: Number.parseFloat(form.originLng),
        dest_address: form.destAddress,
        dest_lat: Number.parseFloat(form.destLat),
        dest_lng: Number.parseFloat(form.destLng),
        fare_estimate_low: Number.parseFloat(form.fareLow),
        fare_estimate_high: Number.parseFloat(form.fareHigh),
      }),
    onSuccess: (job) => {
      setForm(EMPTY_FORM);
      onCreated(job);
    },
  });

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    mutation.mutate();
  }

  function handleClose() {
    mutation.reset();
    onClose();
  }

  function set<K extends keyof typeof EMPTY_FORM>(key: K, value: string) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title="New job"
      description="Broadcasts to every currently-available driver on shift. First to accept wins; sibling offers expire automatically."
      className="max-w-lg"
    >
      <form onSubmit={handleSubmit} className="flex flex-col gap-3">
        <FormField label="Pickup address">
          <Input
            value={form.originAddress}
            onChange={(e) => set("originAddress", e.target.value)}
            placeholder="123 George St, Sydney"
            required
          />
        </FormField>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Pickup latitude">
            <Input
              type="number"
              step="any"
              value={form.originLat}
              onChange={(e) => set("originLat", e.target.value)}
              placeholder="-33.8688"
              required
            />
          </FormField>
          <FormField label="Pickup longitude">
            <Input
              type="number"
              step="any"
              value={form.originLng}
              onChange={(e) => set("originLng", e.target.value)}
              placeholder="151.2093"
              required
            />
          </FormField>
        </div>

        <FormField label="Drop-off address">
          <Input
            value={form.destAddress}
            onChange={(e) => set("destAddress", e.target.value)}
            placeholder="Sydney Airport, T1"
            required
          />
        </FormField>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Drop-off latitude">
            <Input
              type="number"
              step="any"
              value={form.destLat}
              onChange={(e) => set("destLat", e.target.value)}
              placeholder="-33.9399"
              required
            />
          </FormField>
          <FormField label="Drop-off longitude">
            <Input
              type="number"
              step="any"
              value={form.destLng}
              onChange={(e) => set("destLng", e.target.value)}
              placeholder="151.1753"
              required
            />
          </FormField>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <FormField label="Fare estimate — low ($)">
            <Input
              type="number"
              step="0.01"
              min="0"
              value={form.fareLow}
              onChange={(e) => set("fareLow", e.target.value)}
              placeholder="18.00"
              required
            />
          </FormField>
          <FormField label="Fare estimate — high ($)">
            <Input
              type="number"
              step="0.01"
              min="0"
              value={form.fareHigh}
              onChange={(e) => set("fareHigh", e.target.value)}
              placeholder="22.00"
              required
            />
          </FormField>
        </div>

        {mutation.isError && (
          <p className="text-sm text-destructive">
            Failed to create the job. Check the fields and try again.
          </p>
        )}

        <div className="mt-2 flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" disabled={mutation.isPending}>
            {mutation.isPending ? "Broadcasting…" : "Create & broadcast"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

function FormField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label className="text-sm font-medium">{label}</label>
      {children}
    </div>
  );
}
