import { useState } from "react";
import { AlertTriangle, ArrowLeftRight } from "lucide-react";
import { Button, Input, Modal } from "@/components/ui";
import {
  useCreateGeofenceMutation,
  useUpdateGeofenceMutation,
  type Geofence,
  type GeofenceCreateInput,
} from "@/hooks/useGeofences";
import { extractErrorMessage } from "./format";
import { SYDNEY_AIRPORT_CENTER, TollZoneMapPicker, type PickerZone } from "./TollZoneMapPicker";
import { useResetOnChange } from "@/lib/useResetOnChange";
import { SWAP_HINT, formatHumanCoords, looksSwapped } from "@/pages/zones/coordinateHints";

/** Which flavour of circular geofence the modal is editing. Same backend
 * row shape either way; the copy, defaults and fee semantics differ. */
export type ZoneFormKind = "toll" | "airport";

export interface TollZoneFormModalProps {
  open: boolean;
  onClose: () => void;
  mode: "create" | "edit";
  zone?: Geofence;
  /** Defaults to `"toll"` so existing call sites are unchanged. */
  kind?: ZoneFormKind;
  /** Sibling zones of the same kind, drawn faintly on the picker so a new
   * terminal rank can be placed without overlapping an existing one. The
   * zone being edited is filtered out here. */
  siblingZones?: PickerZone[];
}

interface FormState {
  name: string;
  centerLat: number | null;
  centerLng: number | null;
  radiusM: string;
  tollAmount: string;
}

const SYDNEY_DEFAULT = { lat: -33.8688, lng: 151.2093 };
const SYDNEY_AIRPORT_DEFAULT = { lat: SYDNEY_AIRPORT_CENTER[1], lng: SYDNEY_AIRPORT_CENTER[0] };

/** A terminal rank is a few hundred metres across; 400 m keeps T2 and T3
 * (which sit side by side) from swallowing each other. */
const DEFAULT_RADIUS_M: Record<ZoneFormKind, string> = { toll: "500", airport: "400" };

const COPY: Record<
  ZoneFormKind,
  {
    createTitle: string;
    editTitle: (name: string) => string;
    description: string;
    namePlaceholder: string;
    feeLabel: string;
    feeHelper?: string;
    feePlaceholder: string;
    seedLabel: string;
    seed: { lat: number; lng: number };
    submitCreate: string;
  }
> = {
  toll: {
    createTitle: "New toll zone",
    editTitle: (name) => `Edit toll zone — ${name}`,
    description:
      "Circular geofence. Trips crossing into this radius auto-detect the toll from GPS ticks (PATCH /v1/trips/{id}/tick).",
    namePlaceholder: "e.g. Sydney Harbour Bridge",
    feeLabel: "Toll amount (AUD, optional)",
    feePlaceholder: "e.g. 4.50",
    seedLabel: "Start from Sydney CBD",
    seed: SYDNEY_DEFAULT,
    submitCreate: "Create zone",
  },
  airport: {
    createTitle: "Airport pickup zone",
    editTitle: (name) => `Edit airport pickup zone — ${name}`,
    description:
      "Circular geofence around a terminal taxi rank. The access fee is charged once when a hiring starts inside this zone — a pickup at the rank. It is never charged on a drop-off, and never added under the Sydney Airport fixed fare, which already includes it.",
    namePlaceholder: "e.g. Sydney Airport T1 International",
    feeLabel: "Airport access fee (AUD)",
    feeHelper: "Charged once at pickup, never on drop-off",
    feePlaceholder: "e.g. 6.43",
    seedLabel: "Start from Sydney Airport",
    seed: SYDNEY_AIRPORT_DEFAULT,
    submitCreate: "Create airport zone",
  },
};

function emptyForm(kind: ZoneFormKind): FormState {
  return { name: "", centerLat: null, centerLng: null, radiusM: DEFAULT_RADIUS_M[kind], tollAmount: "" };
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

/** Create/edit modal for a circular geofence: `kind: "toll"` (charged on
 * entry) or `kind: "airport"` (access fee charged once at pickup). Center is
 * picked by clicking the embedded map (see TollZoneMapPicker) or, without a
 * Mapbox token configured, typed directly as lat/lng. */
export function TollZoneFormModal({
  open,
  onClose,
  mode,
  zone,
  kind = "toll",
  siblingZones = [],
}: TollZoneFormModalProps) {
  const copy = COPY[kind];
  const [form, setForm] = useState<FormState>(zone ? formFromZone(zone) : emptyForm(kind));
  const [error, setError] = useState<string | null>(null);

  const createMutation = useCreateGeofenceMutation();
  const updateMutation = useUpdateGeofenceMutation();
  const isPending = createMutation.isPending || updateMutation.isPending;

  // Re-seed the form when the modal opens, and when it is re-pointed at a
  // different row without closing. Keyed on the id alone, never the whole
  // object: a background refetch produces a new-but-equal row, and resetting
  // on that would wipe what the operator is part-way through typing.
  useResetOnChange(open ? (zone?.id ?? "new") : null, () => {
    setForm(zone ? formFromZone(zone) : emptyForm(kind));
    setError(null);
  });

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  const center =
    form.centerLat != null &&
    form.centerLng != null &&
    !Number.isNaN(form.centerLat) &&
    !Number.isNaN(form.centerLng)
      ? { lat: form.centerLat, lng: form.centerLng }
      : null;
  // Both values can be in range and still be the wrong way round (a zone
  // once landed in Norway that way) -- see pages/zones/coordinateHints.ts.
  const swapped = center != null && looksSwapped(center.lat, center.lng);

  const otherZones = siblingZones.filter((z) => z.id !== zone?.id);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (!form.name.trim()) {
      setError("Name is required.");
      return;
    }
    if (!center) {
      setError("Set the zone center — click the map (or enter lat/lng) below.");
      return;
    }
    const radius = Number(form.radiusM);
    if (!form.radiusM.trim() || Number.isNaN(radius) || radius <= 0) {
      setError("Radius must be a positive number of meters.");
      return;
    }
    const fee = form.tollAmount.trim();
    if (kind === "airport") {
      if (fee === "" || Number.isNaN(Number(fee)) || Number(fee) <= 0) {
        setError("Airport access fee is required and must be a positive amount.");
        return;
      }
    } else if (fee !== "" && Number.isNaN(Number(fee))) {
      setError("Toll amount must be a number.");
      return;
    }

    const payload: GeofenceCreateInput = {
      name: form.name.trim(),
      kind,
      center_lat: center.lat,
      center_lng: center.lng,
      radius_m: radius,
      toll_amount: fee === "" ? null : fee,
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

  const formId = `${kind}-zone-form`;

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={mode === "create" ? copy.createTitle : copy.editTitle(zone?.name ?? "")}
      description={copy.description}
      className="max-w-2xl"
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button type="submit" form={formId} disabled={isPending}>
            {isPending ? "Saving…" : mode === "create" ? copy.submitCreate : "Save changes"}
          </Button>
        </>
      }
    >
      <form id={formId} onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground" htmlFor={`${formId}-name`}>
            Name
          </label>
          <Input
            id={`${formId}-name`}
            value={form.name}
            onChange={(e) => update("name", e.target.value)}
            placeholder={copy.namePlaceholder}
            required
          />
        </div>

        <TollZoneMapPicker
          lat={form.centerLat}
          lng={form.centerLng}
          radiusM={Number(form.radiusM) || 0}
          onPick={(lat, lng) => setForm((f) => ({ ...f, centerLat: lat, centerLng: lng }))}
          defaultCenter={[copy.seed.lng, copy.seed.lat]}
          otherZones={otherZones}
        />

        {form.centerLat == null && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="w-fit"
            onClick={() => setForm((f) => ({ ...f, centerLat: copy.seed.lat, centerLng: copy.seed.lng }))}
          >
            {copy.seedLabel}
          </Button>
        )}

        <div className="grid grid-cols-2 gap-4">
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground" htmlFor={`${formId}-radius`}>
              Radius (meters)
            </label>
            <Input
              id={`${formId}-radius`}
              type="number"
              min={1}
              step="any"
              value={form.radiusM}
              onChange={(e) => update("radiusM", e.target.value)}
              required
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground" htmlFor={`${formId}-fee`}>
              {copy.feeLabel}
            </label>
            <Input
              id={`${formId}-fee`}
              inputMode="decimal"
              value={form.tollAmount}
              onChange={(e) => update("tollAmount", e.target.value)}
              placeholder={copy.feePlaceholder}
              required={kind === "airport"}
            />
            {copy.feeHelper && <p className="text-xs text-muted-foreground">{copy.feeHelper}</p>}
          </div>
        </div>

        {center && (
          <div className="flex flex-col gap-1.5">
            <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
              <span className="font-mono text-muted-foreground" data-testid="zone-coords-readback">
                {center.lat.toFixed(5)}, {center.lng.toFixed(5)}
                {" · "}
                {formatHumanCoords(center.lat, center.lng)}
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="gap-1"
                onClick={() => setForm((f) => ({ ...f, centerLat: f.centerLng, centerLng: f.centerLat }))}
              >
                <ArrowLeftRight className="h-3.5 w-3.5" aria-hidden="true" />
                Swap
              </Button>
            </div>
            {swapped && (
              <p
                className="flex items-start gap-2 rounded-md bg-warning/10 px-3 py-2 text-xs text-warning"
                role="status"
              >
                <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                <span>{SWAP_HINT}</span>
              </p>
            )}
          </div>
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
