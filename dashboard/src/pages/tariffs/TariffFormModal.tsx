import { useEffect, useMemo, useState } from "react";
import { AlertTriangle, Sparkles } from "lucide-react";
import { Badge, Button, Input, Modal, Select } from "@/components/ui";
import {
  FARES_ORDER_CAPPED_FIELDS,
  UNCAPPED_RATE_FIELDS,
  useCreateTariffMutation,
  useFaresOrderQuery,
  useTariffPresetsQuery,
  useUpdateTariffMutation,
  type CappedRateField,
  type Region,
  type Tariff,
  type TariffCreateInput,
  type TariffPreset,
  type UncappedRateField,
} from "@/hooks/useTariffStudio";
import {
  errorMentionsField,
  extractErrorMessage,
  formatRateValue,
  fromDatetimeLocalValue,
  RATE_FIELD_META,
  REGION_OPTIONS,
  toDatetimeLocalValue,
} from "./format";
import { ExtrasSection } from "./ExtrasSection";

export interface TariffFormModalProps {
  open: boolean;
  onClose: () => void;
  mode: "create" | "edit";
  tariff?: Tariff;
}

type FormState = {
  name: string;
  region: Region;
  effective_from: string; // datetime-local value
  effective_to: string; // datetime-local value, "" = none
  booked: boolean;
} & Record<CappedRateField | UncappedRateField, string>;

function emptyForm(): FormState {
  return {
    name: "",
    region: "urban",
    effective_from: toDatetimeLocalValue(new Date().toISOString()),
    effective_to: "",
    booked: false,
    flag_fall: "",
    peak_charge: "0",
    dist_rate_1: "",
    dist_rate_2: "",
    night_rate_1: "",
    night_rate_2: "",
    holiday_rate_1: "0",
    holiday_rate_2: "0",
    waiting_rate_per_min: "",
    dist_km_threshold: "12",
    speed_threshold_kmh: "26",
    maxi_multiplier: "1.5",
    multi_hire_pct: "0.75",
    psl_amount: "1.32",
    surcharge_pct_cap: "5.0",
    cleaning_fee_cap: "124.14",
  };
}

function formFromTariff(tariff: Tariff): FormState {
  return {
    name: tariff.name,
    region: tariff.region,
    effective_from: toDatetimeLocalValue(tariff.effective_from),
    effective_to: toDatetimeLocalValue(tariff.effective_to),
    booked: tariff.booked,
    flag_fall: tariff.flag_fall,
    peak_charge: tariff.peak_charge,
    dist_rate_1: tariff.dist_rate_1,
    dist_rate_2: tariff.dist_rate_2,
    night_rate_1: tariff.night_rate_1,
    night_rate_2: tariff.night_rate_2,
    holiday_rate_1: tariff.holiday_rate_1,
    holiday_rate_2: tariff.holiday_rate_2,
    waiting_rate_per_min: tariff.waiting_rate_per_min,
    dist_km_threshold: tariff.dist_km_threshold,
    speed_threshold_kmh: tariff.speed_threshold_kmh,
    maxi_multiplier: tariff.maxi_multiplier,
    multi_hire_pct: tariff.multi_hire_pct,
    psl_amount: tariff.psl_amount,
    surcharge_pct_cap: tariff.surcharge_pct_cap,
    cleaning_fee_cap: tariff.cleaning_fee_cap,
  };
}

const REQUIRED_RATE_FIELDS: CappedRateField[] = [
  "flag_fall",
  "dist_rate_1",
  "dist_rate_2",
  "night_rate_1",
  "night_rate_2",
  "waiting_rate_per_min",
];

/** Create/edit modal for a tariff. Every rate field is editable; `region` is
 * locked once created (backend: region defines which Fares Order reference
 * a tariff validates against — create a new tariff instead of changing it).
 * When the candidate is a non-booked urban/country tariff, fetches the
 * current Fares Order reference for the selected region and surfaces the
 * regulated cap alongside each of the 9 rate fields it governs, plus
 * inline + banner error surfacing when the backend rejects the save for
 * exceeding it. */
export function TariffFormModal({ open, onClose, mode, tariff }: TariffFormModalProps) {
  const [form, setForm] = useState<FormState>(tariff ? formFromTariff(tariff) : emptyForm());
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Set<string>>(new Set());
  const [selectedPreset, setSelectedPreset] = useState<string>("");

  const createMutation = useCreateTariffMutation();
  const updateMutation = useUpdateTariffMutation();
  const isPending = createMutation.isPending || updateMutation.isPending;

  const isCreate = mode === "create";
  const presetsQuery = useTariffPresetsQuery(open && isCreate);

  const regulated = form.region !== "exempt" && !form.booked;
  const faresOrderQuery = useFaresOrderQuery(regulated ? form.region : "");
  const reference = regulated ? faresOrderQuery.data : null;

  useEffect(() => {
    if (open) {
      setForm(tariff ? formFromTariff(tariff) : emptyForm());
      setError(null);
      setFieldErrors(new Set());
      setSelectedPreset("");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, tariff?.id]);

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  /** Prefills region/booked + every rate field from a named preset's
   * defaults. Name is only auto-filled while it's still blank or still
   * matches a preset label the operator hasn't typed over — nothing here
   * bypasses the normal create flow, the operator can edit any field
   * (including re-picking a different preset) before hitting Save, which
   * still runs through `useCreateTariffMutation` and full Fares Order
   * validation as usual. */
  function applyPreset(preset: TariffPreset) {
    setSelectedPreset(preset.key);
    setForm((f) => {
      const nameIsAutoManaged = f.name.trim() === "" || presetsQuery.data?.some((p) => p.label === f.name);
      return {
        ...f,
        name: nameIsAutoManaged ? preset.label : f.name,
        region: preset.defaults.region,
        booked: preset.defaults.booked,
        ...Object.fromEntries(
          [...FARES_ORDER_CAPPED_FIELDS, ...UNCAPPED_RATE_FIELDS].map((field) => [field, preset.defaults[field]]),
        ),
      };
    });
  }

  /** "Blank tariff" — resets every rate field back to the empty-form
   * defaults, keeping the name only if the operator typed something that
   * isn't just a preset label left behind. */
  function clearPreset() {
    setSelectedPreset("");
    setForm((f) => {
      const keepName = f.name.trim() !== "" && !presetsQuery.data?.some((p) => p.label === f.name);
      return { ...emptyForm(), name: keepName ? f.name : "" };
    });
  }

  const capViolations = useMemo(() => {
    if (!reference) return new Set<string>();
    const violated = new Set<string>();
    for (const f of FARES_ORDER_CAPPED_FIELDS) {
      const candidate = Number(form[f]);
      const cap = Number(reference[f]);
      if (!Number.isNaN(candidate) && !Number.isNaN(cap) && candidate > cap) {
        violated.add(f);
      }
    }
    return violated;
  }, [reference, form]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldErrors(new Set());

    if (!form.name.trim()) {
      setError("Name is required.");
      return;
    }
    const effectiveFromIso = fromDatetimeLocalValue(form.effective_from);
    if (!effectiveFromIso) {
      setError("Effective-from date/time is required.");
      return;
    }
    for (const f of REQUIRED_RATE_FIELDS) {
      if (form[f].trim() === "" || Number.isNaN(Number(form[f]))) {
        setError(`${RATE_FIELD_META[f].label} is required and must be a number.`);
        return;
      }
    }

    const ratePayload = Object.fromEntries(
      [...FARES_ORDER_CAPPED_FIELDS, ...UNCAPPED_RATE_FIELDS].map((f) => [f, form[f]]),
    ) as Record<CappedRateField | UncappedRateField, string>;

    try {
      if (mode === "create") {
        const payload: TariffCreateInput = {
          name: form.name.trim(),
          region: form.region,
          effective_from: effectiveFromIso,
          effective_to: fromDatetimeLocalValue(form.effective_to) ?? null,
          booked: form.booked,
          ...ratePayload,
        };
        await createMutation.mutateAsync(payload);
      } else {
        if (!tariff) return;
        await updateMutation.mutateAsync({
          id: tariff.id,
          input: {
            name: form.name.trim(),
            effective_from: effectiveFromIso,
            effective_to: fromDatetimeLocalValue(form.effective_to) ?? null,
            booked: form.booked,
            ...ratePayload,
          },
        });
      }
      onClose();
    } catch (err) {
      const message = extractErrorMessage(err);
      setError(message);
      const hit = new Set<string>();
      for (const f of FARES_ORDER_CAPPED_FIELDS) {
        if (errorMentionsField(message, f)) hit.add(f);
      }
      setFieldErrors(hit);
    }
  }

  function renderRateField(field: CappedRateField | UncappedRateField, capped: boolean) {
    const meta = RATE_FIELD_META[field];
    const cap = capped && reference ? reference[field as CappedRateField] : null;
    const overCap = capped && capViolations.has(field);
    const serverFlagged = fieldErrors.has(field);
    return (
      <div key={field} className="flex flex-col gap-1">
        <label className="text-xs font-medium text-muted-foreground">{meta.label}</label>
        <Input
          value={form[field]}
          onChange={(e) => update(field, e.target.value)}
          inputMode="decimal"
          className={overCap || serverFlagged ? "border-destructive focus-visible:ring-destructive" : ""}
        />
        {capped && (
          <p className={overCap ? "text-xs text-destructive" : "text-xs text-muted-foreground"}>
            {faresOrderQuery.isLoading
              ? "Checking Fares Order cap…"
              : cap != null
                ? `Fares Order cap: ${formatRateValue(String(cap), meta.unit)}${overCap ? " — exceeds cap" : ""}`
                : reference === null && (form.region === "urban" || form.region === "country") && !form.booked
                  ? "No Fares Order reference seeded for this region yet — cap not enforced client-side."
                  : "Booked/exempt tariffs are not Fares Order regulated."}
          </p>
        )}
      </div>
    );
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={mode === "create" ? "New tariff" : `Edit tariff — ${tariff?.name}`}
      description={
        mode === "create"
          ? "Effective-dated rate card. Region cannot be changed after creation."
          : "Region is locked once a tariff exists — create a new tariff to move regions."
      }
      className="max-w-3xl"
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button type="submit" form="tariff-form" disabled={isPending}>
            {isPending ? "Saving…" : mode === "create" ? "Create tariff" : "Save changes"}
          </Button>
        </>
      }
    >
      <form
        id="tariff-form"
        onSubmit={handleSubmit}
        className="grid max-h-[65vh] grid-cols-1 gap-4 overflow-y-auto pr-1 sm:grid-cols-2"
      >
        {isCreate && (
          <div className="flex flex-col gap-2 rounded-md border border-dashed border-border bg-muted/40 p-3 sm:col-span-2">
            <div className="flex items-center gap-2 text-sm font-medium text-foreground">
              <Sparkles className="h-4 w-4 text-brand-primary" />
              Start from a preset
            </div>
            <div className="flex flex-wrap gap-2">
              <Button
                type="button"
                size="sm"
                variant={selectedPreset === "" ? "primary" : "outline"}
                onClick={clearPreset}
              >
                Blank tariff
              </Button>
              {presetsQuery.isLoading && (
                <span className="self-center text-xs text-muted-foreground">Loading presets…</span>
              )}
              {presetsQuery.data?.map((preset) => (
                <Button
                  key={preset.key}
                  type="button"
                  size="sm"
                  variant={selectedPreset === preset.key ? "primary" : "outline"}
                  title={preset.description}
                  onClick={() => applyPreset(preset)}
                >
                  {preset.label}
                </Button>
              ))}
            </div>
            <p className="text-xs text-muted-foreground">
              {selectedPreset
                ? `${presetsQuery.data?.find((p) => p.key === selectedPreset)?.description ?? ""} Every field below is prefilled from the preset — edit anything before saving.`
                : "Prefills region, booked status, and every rate field below. You can still change any of them before saving, and the usual Fares Order validation still applies on save."}
            </p>
          </div>
        )}

        <div className="flex flex-col gap-1 sm:col-span-2">
          <label className="text-xs font-medium text-muted-foreground">Name</label>
          <Input value={form.name} onChange={(e) => update("name", e.target.value)} required />
        </div>

        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Region</label>
          <Select
            options={REGION_OPTIONS}
            value={form.region}
            onChange={(e) => update("region", e.target.value as Region)}
            disabled={mode === "edit"}
            required
          />
        </div>
        <div className="flex items-center gap-2 pt-5">
          <input
            id="booked"
            type="checkbox"
            checked={form.booked}
            onChange={(e) => update("booked", e.target.checked)}
            className="h-4 w-4 rounded border-input"
          />
          <label htmlFor="booked" className="text-sm text-foreground">
            Booked (pre-arranged, unregulated by the Fares Order)
          </label>
        </div>

        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Effective from</label>
          <Input
            type="datetime-local"
            value={form.effective_from}
            onChange={(e) => update("effective_from", e.target.value)}
            required
          />
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Effective to</label>
          <Input
            type="datetime-local"
            value={form.effective_to}
            onChange={(e) => update("effective_to", e.target.value)}
            placeholder="Open-ended"
          />
        </div>

        {regulated && (
          <div className="sm:col-span-2">
            <Badge variant={faresOrderQuery.isLoading ? "outline" : reference ? "primary" : "outline"}>
              {faresOrderQuery.isLoading
                ? "Loading Fares Order reference…"
                : reference
                  ? `Validated against Fares Order reference: ${reference.name}`
                  : "No Fares Order reference seeded — server falls back to hardcoded 2025 rates"}
            </Badge>
          </div>
        )}

        <div className="sm:col-span-2">
          <h3 className="mb-1 text-sm font-semibold text-foreground">
            Fares Order rates {regulated && <span className="font-normal text-muted-foreground">(capped for rank/hail)</span>}
          </h3>
        </div>
        {FARES_ORDER_CAPPED_FIELDS.map((f) => renderRateField(f, true))}

        <div className="sm:col-span-2">
          <h3 className="mb-1 mt-2 text-sm font-semibold text-foreground">Other rate settings</h3>
        </div>
        {UNCAPPED_RATE_FIELDS.map((f) => renderRateField(f, false))}

        {mode === "edit" && tariff && (
          <div className="sm:col-span-2">
            <ExtrasSection tariffId={tariff.id} />
          </div>
        )}

        {error && (
          <div className="flex items-start gap-2 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive sm:col-span-2">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{error}</span>
          </div>
        )}
      </form>
    </Modal>
  );
}
