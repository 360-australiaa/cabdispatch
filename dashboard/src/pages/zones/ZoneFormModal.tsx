import { useState } from "react";
import { AlertTriangle, ArrowLeftRight } from "lucide-react";
import { Button, Input, Modal } from "@/components/ui";
import {
  useCreateZoneMutation,
  useUpdateZoneMutation,
  type Zone,
  type ZoneWriteInput,
} from "@/hooks/useZones";
import { extractErrorMessage } from "./format";
import { useResetOnChange } from "@/lib/useResetOnChange";
import { SWAP_HINT, formatHumanCoords, looksSwapped, parseCoords } from "./coordinateHints";

export interface ZoneFormModalProps {
  open: boolean;
  onClose: () => void;
  mode: "create" | "edit";
  zone?: Zone;
}

interface FormState {
  name: string;
  number: string;
  centerLat: string;
  centerLng: string;
  radiusM: string;
}

const SYDNEY_DEFAULT = { lat: -33.8688, lng: 151.2093 };

function emptyForm(): FormState {
  return {
    name: "",
    number: "",
    centerLat: String(SYDNEY_DEFAULT.lat),
    centerLng: String(SYDNEY_DEFAULT.lng),
    radiusM: "1000",
  };
}

function formFromZone(zone: Zone): FormState {
  return {
    name: zone.name,
    number: zone.number,
    centerLat: String(zone.center_lat),
    centerLng: String(zone.center_lng),
    radiusM: String(zone.radius_m),
  };
}

/** Create/edit modal for a dispatch zone (POST/PUT /v1/zones) - a
 * driver-facing numbered circular region a driver plots into while waiting
 * for work, matching a real competitor taxi meter's zone board. */
export function ZoneFormModal({ open, onClose, mode, zone }: ZoneFormModalProps) {
  const [form, setForm] = useState<FormState>(zone ? formFromZone(zone) : emptyForm());
  const [error, setError] = useState<string | null>(null);

  const createMutation = useCreateZoneMutation();
  const updateMutation = useUpdateZoneMutation();
  const isPending = createMutation.isPending || updateMutation.isPending;

  // Re-seed the form when the modal opens, and when it is re-pointed at a
  // different row without closing. Keyed on the id alone, never the whole
  // object: a background refetch produces a new-but-equal row, and resetting
  // on that would wipe what the operator is part-way through typing.
  useResetOnChange(open ? (zone?.id ?? "new") : null, () => {
    setForm(zone ? formFromZone(zone) : emptyForm());
    setError(null);
  });

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  function swapCoords() {
    setForm((f) => ({ ...f, centerLat: f.centerLng, centerLng: f.centerLat }));
  }

  // Human check under the coordinate inputs: what was typed, read back with
  // hemisphere letters, plus a non-blocking "these look swapped" hint. Both
  // values can be perfectly valid and still be the wrong way round -- see
  // coordinateHints.ts.
  const coords = parseCoords(form.centerLat, form.centerLng);
  const swapped = coords != null && looksSwapped(coords.lat, coords.lng);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (!form.name.trim()) {
      setError("Name is required.");
      return;
    }
    if (!form.number.trim()) {
      setError("Number is required - the short driver-facing code, e.g. \"17\".");
      return;
    }
    const lat = Number(form.centerLat);
    const lng = Number(form.centerLng);
    if (Number.isNaN(lat) || lat < -90 || lat > 90) {
      setError("Center latitude must be a number between -90 and 90.");
      return;
    }
    if (Number.isNaN(lng) || lng < -180 || lng > 180) {
      setError("Center longitude must be a number between -180 and 180.");
      return;
    }
    const radius = Number(form.radiusM);
    if (!form.radiusM.trim() || Number.isNaN(radius) || radius <= 0) {
      setError("Radius must be a positive number of meters.");
      return;
    }

    const payload: ZoneWriteInput = {
      name: form.name.trim(),
      number: form.number.trim(),
      center_lat: lat,
      center_lng: lng,
      radius_m: radius,
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
      title={mode === "create" ? "New zone" : "Edit zone - " + (zone?.name ?? "")}
      description="Circular dispatch zone. Drivers plot into this zone by its number from their app; the live stats screen below reads per-zone supply/demand off this same list."
      className="max-w-lg"
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button type="submit" form="zone-form" disabled={isPending}>
            {isPending ? "Saving..." : mode === "create" ? "Create zone" : "Save changes"}
          </Button>
        </>
      }
    >
      <form id="zone-form" onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div className="grid grid-cols-3 gap-4">
          <div className="col-span-2 flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Name</label>
            <Input
              value={form.name}
              onChange={(e) => update("name", e.target.value)}
              placeholder="e.g. Sydney CBD"
              required
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Number</label>
            <Input
              value={form.number}
              onChange={(e) => update("number", e.target.value)}
              placeholder="e.g. 17"
              maxLength={10}
              required
            />
          </div>
        </div>

        <div className="flex flex-col gap-2">
          <div className="grid grid-cols-2 gap-4">
            <div className="flex flex-col gap-1">
              <label htmlFor="zone-center-lat" className="text-xs font-medium text-muted-foreground">
                Center latitude
              </label>
              <Input
                id="zone-center-lat"
                type="number"
                step="any"
                value={form.centerLat}
                onChange={(e) => update("centerLat", e.target.value)}
                required
              />
            </div>
            <div className="flex flex-col gap-1">
              <label htmlFor="zone-center-lng" className="text-xs font-medium text-muted-foreground">
                Center longitude
              </label>
              <Input
                id="zone-center-lng"
                type="number"
                step="any"
                value={form.centerLng}
                onChange={(e) => update("centerLng", e.target.value)}
                required
              />
            </div>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
            <span className="font-mono text-muted-foreground" data-testid="zone-coords-readback">
              {coords ? formatHumanCoords(coords.lat, coords.lng) : "—"}
            </span>
            <Button type="button" variant="outline" size="sm" onClick={swapCoords} className="gap-1">
              <ArrowLeftRight className="h-3.5 w-3.5" aria-hidden="true" />
              Swap
            </Button>
          </div>
          {swapped && (
            <p className="flex items-start gap-2 rounded-md bg-warning/10 px-3 py-2 text-xs text-warning" role="status">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              <span>{SWAP_HINT}</span>
            </p>
          )}
        </div>

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
