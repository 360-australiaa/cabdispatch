import { useState } from "react";
import { ChevronDown, ChevronUp, Lightbulb } from "lucide-react";
import { Badge, Button, Card, CardContent, Input, Select } from "@/components/ui";
import {
  useTariffSuggestQuery,
  VALID_VEHICLE_CLASSES,
  VEHICLE_CLASS_LABELS,
  type TariffSuggestParams,
  type VehicleClass,
} from "@/hooks/useTariffStudio";
import { extractErrorMessage } from "./format";

const VEHICLE_CLASS_OPTIONS = [
  { value: "", label: "Any vehicle class" },
  ...VALID_VEHICLE_CLASSES.map((vc) => ({ value: vc, label: VEHICLE_CLASS_LABELS[vc] })),
];

/** Small, secondary hint panel: "what tariff would apply here, right now?"
 * Wraps `GET /v1/tariffs/suggest` — purely informational, doesn't touch the
 * create/edit flow. Collapsed by default so it never competes with the
 * preset picker (the primary way to start a new tariff) for attention. */
export function TariffSuggestPanel() {
  const [open, setOpen] = useState(false);
  const [lat, setLat] = useState("");
  const [lng, setLng] = useState("");
  const [vehicleClass, setVehicleClass] = useState<VehicleClass | "">("");
  const [submitted, setSubmitted] = useState<TariffSuggestParams | null>(null);

  const suggestQuery = useTariffSuggestQuery(submitted);

  const latNum = Number(lat);
  const lngNum = Number(lng);
  const canSubmit = lat.trim() !== "" && lng.trim() !== "" && !Number.isNaN(latNum) && !Number.isNaN(lngNum);

  function handleCheck(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitted({ lat: latNum, lng: lngNum, vehicleClass: vehicleClass || undefined });
  }

  return (
    <Card className="mb-4">
      <CardContent className="pt-4">
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          className="flex w-full items-center justify-between gap-2 text-left"
        >
          <span className="flex items-center gap-2 text-sm font-medium text-foreground">
            <Lightbulb className="h-4 w-4 text-brand-primary" />
            Suggested tariff for a location/time
          </span>
          {open ? (
            <ChevronUp className="h-4 w-4 text-muted-foreground" />
          ) : (
            <ChevronDown className="h-4 w-4 text-muted-foreground" />
          )}
        </button>

        {open && (
          <>
            <form onSubmit={handleCheck} className="mt-3 flex flex-wrap items-end gap-3">
              <div className="flex flex-col gap-1">
                <label className="text-xs font-medium text-muted-foreground">Latitude</label>
                <Input
                  className="w-32"
                  value={lat}
                  onChange={(e) => setLat(e.target.value)}
                  placeholder="-33.8688"
                  inputMode="decimal"
                />
              </div>
              <div className="flex flex-col gap-1">
                <label className="text-xs font-medium text-muted-foreground">Longitude</label>
                <Input
                  className="w-32"
                  value={lng}
                  onChange={(e) => setLng(e.target.value)}
                  placeholder="151.2093"
                  inputMode="decimal"
                />
              </div>
              <div className="flex flex-col gap-1">
                <label className="text-xs font-medium text-muted-foreground">Vehicle class</label>
                <Select
                  className="w-44"
                  options={VEHICLE_CLASS_OPTIONS}
                  value={vehicleClass}
                  onChange={(e) => setVehicleClass(e.target.value as VehicleClass | "")}
                />
              </div>
              <Button type="submit" size="sm" variant="outline" disabled={!canSubmit}>
                Check
              </Button>
            </form>

            {submitted && (
              <div className="mt-3 text-sm">
                {suggestQuery.isLoading && <p className="text-muted-foreground">Checking…</p>}
                {suggestQuery.isError && (
                  <p className="text-destructive">{extractErrorMessage(suggestQuery.error)}</p>
                )}
                {!suggestQuery.isLoading && !suggestQuery.isError && suggestQuery.data === null && (
                  <p className="text-muted-foreground">No tariff resolves for this location/time.</p>
                )}
                {suggestQuery.data && (
                  <p className="flex flex-wrap items-center gap-2 text-foreground">
                    <Badge variant="primary">{suggestQuery.data.tariff_name}</Badge>
                    <span className="text-muted-foreground">({suggestQuery.data.time_class})</span>
                    <span className="text-muted-foreground">— {suggestQuery.data.reason}</span>
                  </p>
                )}
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}
