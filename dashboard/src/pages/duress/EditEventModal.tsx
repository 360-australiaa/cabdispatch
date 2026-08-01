import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { useMutation } from "@tanstack/react-query";
import { Button, Input, Modal } from "@/components/ui";
import { updateDuressEvent } from "./api";
import type { DuressEvent } from "./types";

/**
 * Admin correction of the non-state-machine fields on a duress event
 * (`PATCH /v1/duress/{id}` — `DuressEventUpdate`). Intentionally does NOT
 * expose `status`: state transitions go through cancel/escalate/close only.
 */
export function EditEventModal({
  event,
  open,
  onClose,
  onSaved,
}: {
  event: DuressEvent;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [vehicleId, setVehicleId] = useState(event.vehicle_id);
  const [driverId, setDriverId] = useState(event.driver_id);
  const [gpsStreamRef, setGpsStreamRef] = useState(event.gps_stream_ref);
  const [audioRef, setAudioRef] = useState(event.audio_ref ?? "");

  useEffect(() => {
    if (!open) return;
    setVehicleId(event.vehicle_id);
    setDriverId(event.driver_id);
    setGpsStreamRef(event.gps_stream_ref);
    setAudioRef(event.audio_ref ?? "");
  }, [open, event]);

  const mutation = useMutation({
    mutationFn: () =>
      updateDuressEvent(event.id, {
        vehicle_id: vehicleId,
        driver_id: driverId,
        gps_stream_ref: gpsStreamRef,
        audio_ref: audioRef || null,
      }),
    onSuccess: () => onSaved(),
  });

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    mutation.mutate();
  }

  return (
    <Modal open={open} onClose={onClose} title="Edit duress event" className="max-w-md">
      <form onSubmit={handleSubmit} className="flex flex-col gap-3">
        <FormField label="Vehicle ID">
          <Input value={vehicleId} onChange={(e) => setVehicleId(e.target.value)} required />
        </FormField>
        <FormField label="Driver ID">
          <Input value={driverId} onChange={(e) => setDriverId(e.target.value)} required />
        </FormField>
        <FormField label="GPS stream ref">
          <Input value={gpsStreamRef} onChange={(e) => setGpsStreamRef(e.target.value)} required />
        </FormField>
        <FormField label="Audio ref (optional)">
          <Input value={audioRef} onChange={(e) => setAudioRef(e.target.value)} />
        </FormField>

        {mutation.isError && (
          <p className="text-sm text-destructive">Failed to save changes. Try again.</p>
        )}

        <div className="mt-2 flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Saving…" : "Save changes"}
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
