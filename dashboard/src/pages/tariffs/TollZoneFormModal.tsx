import { useEffect, useState } from "react";
import { AlertTriangle } from "lucide-react";
import { Button, Input, Modal } from "@/components/ui";
import {
  useCreateGeofenceMutation,
  useUpdateGeofenceMutation,
  type Geofence,
  type GeofenceCreateInput,
} from "@/hooks/useGeofences";
import { extractErrorMessage } from "./format";
import { TollZoneMapPicker } from "./TollZoneMapPicker";

export interface TollZoneFormModalProps {
  open: boolean;
  onClose: () => void;
  mode: "create" | "edit";
  zone?: Geofence;
}

interface FormState {
  name: string;
  centerLat: number | null;
  centerLng: number | null;
  radiusM: string;
  tollAmount: string;
}

const SYDNEY_DEFAULT = { lat: -33.8688, lng: 151.2093 };

function emptyForm(): FormState {
  return { name: "", centerLat: null, centerLng: null, radiusM: "500", tollAmount: "" };
}

function formFromZone(zone: Geofence): FormState {
  return {
    name: zone.name,
    centerLat: zone.center_lat,
    centerLng: zone.center_lng,
    radiusM: String(zone.radius_m),
    tollAmount: zone.toll_amount ?? "",
  };
}

/** Create/edit modal for a circular toll geofence (`kind: "toll"`). Center is
 * picked by clicking the embedded map (see TollZoneMapPicker) or, without a
 * Mapbox token configured, typed directly as lat/lng. */
export function TollZoneFormModal({ open, onClose, mode, zone }: TollZoneFormModalProps) {
  const [form, setForm] = useState<FormState>(zone ? formFromZone(zone) : emptyForm());
  const [error, setError] = useState<string | null>(null);

  const createMutation = useCreateGeofenceMutation();
  const updateMutation = useUpdateGeofenceMutation();
  const isPending = createMutation.isPending || updateMutation.isPending;

  useEffect(() => {
    if (open) {
      setForm(zone ? formFromZone(zone) : emptyForm());
      setError(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, zone?.id]);

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
    if (form.centerLat == null || form.centerLng == null || Number.isNaN(form.centerLat) || Number.isNaN(form.centerLng)) {
      setError("Set the zone center — click the map (or enter lat/lng) below.");
      return;
    }
    const radius = Number(form.radiusM);
    if (!form.radiusM.trim() || Number.isNaN(radius) || radius <= 0) {
      setError("Radius must be a positive number of meters.");
      return;
    }
    if (form.tollAmount.trim() !== "" && Number.isNaN(Number(form.tollAmount))) {
      setError("Toll amount must be a number.");
      return;
    }

    const payload: GeofenceCreateInput = {
      name: form.name.trim(),
      kind: "toll",
      center_lat: form.centerLat,
      center_lng: form.centerLng,
      radius_m: radius,
      toll_amount: form.tollAmount.trim() === "" ? null : form.tollAmount.trim(),
    };

    try {
      if (mode === "create") {
        await createMutation.mutateAsync(payload);
      } else {
        if (!zone) return;
        await updateMutation.mutateAsync({ id: zone.id, input: payload });
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
      title={mode === "create" ? "New toll zone" : `Edit toll zone — ${zone?.name}`}
      description="Circular geofence. Trips crossing into this radius auto-detect the toll from GPS ticks (PATCH /v1/trips/{id}/tick)."
      className="max-w-2xl"
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button type="submit" form="toll-zone-form" disabled={isPending}>
            {isPending ? "Saving…" : mode === "create" ? "Create zone" : "Save changes"}
          </Button>
        </>
      }
    >
      <form id="toll-zone-form" onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Name</label>
          <Input
            value={form.name}
            onChange={(e) => update("name", e.target.value)}
            placeholder="e.g. Sydney Harbour Bridge"
            required
          />
        </div>

        <TollZoneMapPicker
          lat={form.centerLat}
          lng={form.centerLng}
          radiusM={Number(form.radiusM) || 0}
          onPick={(lat, lng) => setForm((f) => ({ ...f, centerLat: lat, centerLng: lng }))}
        />

        {form.centerLat == null && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="w-fit"
            onClick={() => setForm((f) => ({ ...f, centerLat: SYDNEY_DEFAULT.lat, centerLng: SYDNEY_DEFAULT.lng }))}
          >
            Start from Sydney CBD
          </Button>
        )}

        <div className="grid grid-cols-2 gap-4">
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Radius (meters)</label>
            <Input
              type="number"
              min={1}
              step="any"
              value={form.radiusM}
              onChange={(e) => update("radiusM", e.target.value)}
              required
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Toll amount (AUD, optional)</label>
            <Input
              inputMode="decimal"
              value={form.tollAmount}
              onChange={(e) => update("tollAmount", e.target.value)}
              placeholder="e.g. 4.50"
            />
          </div>
        </div>

        {form.centerLat != null && form.centerLng != null && (
          <p className="font-mono text-xs text-muted-foreground">
            {form.centerLat.toFixed(5)}, {form.centerLng.toFixed(5)}
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
