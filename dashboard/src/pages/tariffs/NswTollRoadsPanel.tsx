import { useState } from "react";
import { AlertTriangle, History, Info, Plus } from "lucide-react";
import {
  Badge,
  Button,
  Card,
  CardContent,
  Input,
  Modal,
  Select,
  Spinner,
  Table,
  Tooltip,
  type TableColumn,
} from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { isPlatformOwner } from "@/lib/platformAdmin";
import {
  useCreateTollRoadPriceRevisionMutation,
  useTollGantriesQuery,
  useTollRoadDetailQuery,
  useTollRoadsQuery,
  type TollGantry,
  type TollPoint,
  type TollRoad,
  type TollRoadPriceRevision,
  type TollRoadPriceRevisionInput,
} from "@/hooks/useTollRoads";
import { TollGantryMap } from "./TollGantryMap";
import { extractErrorMessage, formatDateTime, formatMoney } from "./format";

/** "NSW Toll Roads" tab of Tariff Studio — read-mostly view of the real
 * per-road, per-gantry toll registry (`/v1/toll-roads`,
 * `backend/app/models/toll.py`) that replaced the old flat-circle toll
 * geofences (still shown separately, under "Toll Zones", for tenant-defined
 * ad hoc circles unrelated to this NSW registry).
 *
 * Unlike Toll Zones, there is no create/delete here — these 13 real roads
 * (+ gantry data for an extra one, see the M12 stub row) are seeded
 * reference data (`scripts/seed_toll_roads.py`), not dashboard-authored.
 * The only write surface is adding a new dated price revision (a quarterly
 * reindexation) — platform-owner-only, same rule Toll Zones/Rate Cards use —
 * which NEVER deletes or overwrites the prior revision. */
export function NswTollRoadsPanel() {
  const { user } = useAuth();
  const canWrite = isPlatformOwner(user);

  const roadsQuery = useTollRoadsQuery();
  const gantriesQuery = useTollGantriesQuery();
  const [detailRoad, setDetailRoad] = useState<TollRoad | null>(null);
  const [revisionRoad, setRevisionRoad] = useState<TollRoad | null>(null);

  const roads = roadsQuery.data ?? [];

  const columns: TableColumn<TollRoad>[] = [
    {
      key: "name",
      header: "Road",
      render: (row) => (
        <div>
          <div className="font-medium">{row.name}</div>
          <div className="text-xs text-muted-foreground">{row.operator ?? "Operator not captured"}</div>
        </div>
      ),
    },
    {
      key: "pricing_model",
      header: "Pricing model",
      render: (row) => (
        <div>
          <Badge variant="outline">{PRICING_MODEL_LABELS[row.pricing_model] ?? row.pricing_model}</Badge>
          {row.pricing_model === "per_point" && (
            <div className="mt-1 text-xs text-muted-foreground">
              {CHARGING_POLICY_LABELS[row.charging_policy] ?? row.charging_policy}
            </div>
          )}
        </div>
      ),
    },
    {
      key: "direction",
      header: "Direction",
      render: (row) => (row.directional ? (DIRECTION_LABELS[row.directional] ?? row.directional) : "—"),
    },
    {
      key: "price",
      header: "Current price (Class A / taxi)",
      render: (row) => <CurrentPriceCell road={row} />,
    },
    {
      key: "gantry_count",
      header: "Gantries",
      render: (row) =>
        row.gantry_count > 0 ? (
          row.gantry_count
        ) : (
          <span className="text-xs text-muted-foreground">0 — not GPS-detectable</span>
        ),
    },
    {
      key: "confidence",
      header: "Confidence",
      render: (row) => <ConfidenceBadge confidence={row.current_price?.confidence} />,
    },
    {
      key: "actions",
      header: "",
      className: "text-right",
      render: (row) => (
        <div className="flex justify-end gap-1">
          <Button variant="ghost" size="icon" title="Gantries + price history" onClick={() => setDetailRoad(row)}>
            <Info className="h-4 w-4" />
          </Button>
          {canWrite && (
            <Button variant="ghost" size="icon" title="Add price revision" onClick={() => setRevisionRoad(row)}>
              <Plus className="h-4 w-4" />
            </Button>
          )}
        </div>
      ),
    },
  ];

  return (
    <div>
      <p className="mb-4 text-sm text-muted-foreground">
        The real NSW toll registry — charged once per road (not once per gantry), direction-aware, and versioned by
        effective date so a quarterly price update never erases the price a past trip actually paid. Class B (heavy
        vehicle) pricing is retained in the API but not shown here — a taxi always pays Class A.
      </p>

      {roadsQuery.isError && (
        <p className="mb-3 text-sm text-destructive">
          Failed to load toll roads. Check the backend connection and try again.
        </p>
      )}

      <Card>
        <CardContent className="pt-4">
          <Table
            columns={columns}
            data={roads}
            rowKey={(row) => row.id}
            isLoading={roadsQuery.isLoading}
            emptyState="No toll roads loaded — run scripts/seed_toll_roads.py."
          />
        </CardContent>
      </Card>

      <div className="mt-6">
        <h3 className="mb-1 text-sm font-semibold">Toll points on the map</h3>
        <p className="mb-3 text-sm text-muted-foreground">
          Every physical gantry in the registry, at its real coordinates. These are what the meter matches a GPS fix
          against (within 150&nbsp;m) to decide a toll was crossed — so if a point here sits somewhere the road does
          not, that is a toll the meter will get wrong. Hover a point for its name and coordinates.
        </p>
        <TollGantryMap
          gantries={gantriesQuery.data ?? []}
          roads={roads}
          isLoading={gantriesQuery.isLoading}
          isError={gantriesQuery.isError}
        />
      </div>

      <TollRoadDetailModal roadId={detailRoad?.id ?? null} onClose={() => setDetailRoad(null)} />
      <PriceRevisionModal road={revisionRoad} onClose={() => setRevisionRoad(null)} />
    </div>
  );
}

const PRICING_MODEL_LABELS: Record<string, string> = {
  flat: "Flat",
  per_point: "Per toll point",
  // Retired by the 2026-09-07 correction pass (see useTollRoads.ts) — kept so
  // a row seeded by an older build still reads as something, and reads as stale.
  zone_flat: "Zone flat (retired — re-seed required)",
  distance: "Distance-based (capped)",
  distance_with_flagfall: "Distance + flagfall",
  time_of_day: "Time of day",
  unpriced: "Unpriced",
};

/** Shown only where it changes what a trip pays — i.e. on a `per_point` road,
 * where the same pricing model bills two different ways. `once_per_road` on
 * anything else is the ordinary case and needs no badge. */
const CHARGING_POLICY_LABELS: Record<string, string> = {
  cumulative_per_point: "Charged per point traversed",
  once_per_road: "Charged once per trip",
  distance_metered: "Metered by distance",
};

const DIRECTION_LABELS: Record<string, string> = {
  both: "Both ways",
  one_way: "One-way",
  northbound_only: "Northbound only",
  southbound_only: "Southbound only",
};

function ConfidenceBadge({ confidence }: { confidence?: string }) {
  if (!confidence) return <span className="text-muted-foreground">—</span>;
  if (confidence === "verified") return <Badge variant="success">Verified</Badge>;
  if (confidence === "needs_verification") return <Badge variant="accent">Needs verification</Badge>;
  return <Badge variant="destructive">Not captured</Badge>;
}

function CurrentPriceCell({ road }: { road: TollRoad }) {
  const price = road.current_price;
  if (!price) return <span className="text-muted-foreground">Not priced</span>;

  if (road.pricing_model === "zone_flat") {
    return (
      <span className="flex items-center gap-1 text-sm">
        <AlertTriangle className="h-3.5 w-3.5 text-amber-500" />
        Stale pricing model — re-run scripts/seed_toll_roads.py to load the real per-point prices
      </span>
    );
  }
  // A per_point road's own min/max is a descriptive range across its points and
  // is never what a trip is charged, so showing it here as "the price" would be
  // misleading. Show the real points instead (all of them for a cumulative road,
  // since a trip pays for each one it traverses).
  if (road.pricing_model === "per_point") {
    if (road.toll_points.length === 0) {
      return <span className="text-muted-foreground">No toll points loaded — re-seed required</span>;
    }
    return (
      <div className="text-sm">
        {road.toll_points.map((point) => (
          <div key={point.id} className="flex justify-between gap-3">
            <span className="text-muted-foreground">{point.name}</span>
            <span>{point.current_price?.price_class_a ? formatMoney(point.current_price.price_class_a) : "—"}</span>
          </div>
        ))}
      </div>
    );
  }
  if (road.pricing_model === "distance" || road.pricing_model === "distance_with_flagfall") {
    // Prefer the operator's OWN published $/km rate. `derived_corridor_km` is
    // only a fallback for a distance road whose real rate hasn't been captured
    // — using it as the headline figure is exactly the defect the 2026-09-07
    // correction pass fixed on Westlink M7 (a derived ~$0.44/km shown in place
    // of Linkt's published $0.5252/km).
    return (
      <span className="text-sm">
        {price.flagfall_class_a && <>{formatMoney(price.flagfall_class_a)} + </>}
        {price.rate_per_km_class_a ? (
          <>${Number(price.rate_per_km_class_a).toFixed(4)}/km</>
        ) : road.derived_corridor_km ? (
          <span className="text-muted-foreground">
            rate not published — derived over ~{Number(road.derived_corridor_km).toFixed(1)} km
          </span>
        ) : (
          <span className="text-muted-foreground">rate not captured</span>
        )}
        {price.cap_class_a && <> — capped {formatMoney(price.cap_class_a)}</>}
        {price.network_cap_class_a && road.network_group && (
          <div className="text-xs text-muted-foreground">
            {road.network_group} network cap {formatMoney(price.network_cap_class_a)} across all stages in one trip
          </div>
        )}
      </span>
    );
  }
  if (road.pricing_model === "time_of_day" && price.time_of_day_rates_class_a) {
    return (
      <span className="text-sm">
        {price.time_of_day_rates_class_a.map((r) => `${r.band} ${formatMoney(r.price)}`).join(" / ")}
      </span>
    );
  }
  if (road.pricing_model === "flat") {
    return <span>{formatMoney(price.price_class_a_max)}</span>;
  }
  return <span className="text-muted-foreground">Not captured</span>;
}

const TOLL_POINT_COLUMNS: TableColumn<TollPoint>[] = [
  { key: "name", header: "Toll point", render: (point) => point.name },
  {
    key: "price",
    header: "Class A price",
    render: (point) =>
      point.current_price?.price_class_a ? formatMoney(point.current_price.price_class_a) : "—",
  },
  {
    key: "gantries",
    header: "Gantries",
    className: "text-muted-foreground",
    render: (point) =>
      point.gantry_count > 0
        ? `${point.gantry_count} gantr${point.gantry_count === 1 ? "y" : "ies"}`
        : "no gantries — not GPS-detectable",
  },
  {
    key: "confidence",
    header: "Confidence",
    render: (point) => <ConfidenceBadge confidence={point.current_price?.confidence} />,
  },
];

const GANTRY_COLUMNS: TableColumn<TollGantry>[] = [
  { key: "location", header: "Location", render: (g) => g.location },
  { key: "ramp", header: "Ramp", className: "text-muted-foreground", render: (g) => g.ramp ?? "—" },
  {
    key: "direction",
    header: "Direction",
    className: "text-muted-foreground",
    render: (g) => g.direction ?? "—",
  },
  {
    key: "coordinates",
    header: "Coordinates",
    className: "font-mono text-muted-foreground",
    // The coordinate IS the detection point -- see TollGantryMap's doc.
    // Shown to 5dp (~1m), well inside the meter's 150m match radius.
    render: (g) => `${g.latitude.toFixed(5)}, ${g.longitude.toFixed(5)}`,
  },
];

const PRICE_HISTORY_COLUMNS: TableColumn<TollRoadPriceRevision>[] = [
  { key: "effective", header: "Effective", render: (rev) => formatDateTime(rev.effective_date) },
  {
    key: "price",
    header: "Class A",
    render: (rev) => (rev.price_class_a_max ? formatMoney(rev.price_class_a_max) : "—"),
  },
  {
    key: "confidence",
    header: "Confidence",
    render: (rev) => (
      <span className="inline-flex items-center gap-1">
        <ConfidenceBadge confidence={rev.confidence} />
        {/* Provenance, not decoration: this is what an operator
            opens when a passenger disputes a toll. */}
        {rev.source_url && (
          <Tooltip content={rev.retrieved_at ? `Confirmed ${rev.retrieved_at}` : "Source"}>
            <a
              href={rev.source_url}
              target="_blank"
              rel="noreferrer noopener"
              className="underline decoration-dotted underline-offset-2"
            >
              source
            </a>
          </Tooltip>
        )}
      </span>
    ),
  },
];

function TollRoadDetailModal({ roadId, onClose }: { roadId: string | null; onClose: () => void }) {
  const detailQuery = useTollRoadDetailQuery(roadId);
  const detail = detailQuery.data;

  return (
    <Modal
      open={roadId != null}
      onClose={onClose}
      title={detail ? `${detail.name} — gantries & price history` : "Toll road detail"}
      className="max-w-3xl"
      footer={
        <Button variant="outline" onClick={onClose}>
          Close
        </Button>
      }
    >
      {detailQuery.isLoading || !detail ? (
        <p className="text-sm text-muted-foreground">
          <Spinner size="sm" label="Loading toll road detail" />
        </p>
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          {detail.source_note && (
            <div className="flex items-start gap-2 rounded-md bg-amber-500/10 px-3 py-2 text-sm text-amber-700 dark:text-amber-400 md:col-span-2">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
              <span>{detail.source_note}</span>
            </div>
          )}
          {detail.description && (
            <p className="text-sm text-muted-foreground md:col-span-2">{detail.description}</p>
          )}
          {detail.toll_points.length > 0 && (
            <div className="md:col-span-2">
              <h4 className="mb-2 text-xs font-semibold uppercase text-muted-foreground">
                Toll points ({detail.toll_points.length}) —{" "}
                {CHARGING_POLICY_LABELS[detail.charging_policy] ?? detail.charging_policy}
              </h4>
              <Table
                columns={TOLL_POINT_COLUMNS}
                data={detail.toll_points}
                rowKey={(point) => point.id}
                label={`Toll points on ${detail.name}`}
              />
            </div>
          )}
          <div>
            <h4 className="mb-2 text-xs font-semibold uppercase text-muted-foreground">
              Gantries ({detail.gantries.length})
            </h4>
            {detail.gantries.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No gantry coordinates for this road in this dataset — priced but not GPS-auto-detectable yet.
              </p>
            ) : (
              <div className="max-h-64 overflow-y-auto text-xs">
                <Table
                  columns={GANTRY_COLUMNS}
                  data={detail.gantries}
                  rowKey={(g) => g.id}
                  stickyHeader
                  label={`Gantries on ${detail.name}`}
                />
              </div>
            )}
          </div>
          <div>
            <h4 className="mb-2 flex items-center gap-1 text-xs font-semibold uppercase text-muted-foreground">
              <History className="h-3.5 w-3.5" /> Price history
            </h4>
            <div className="max-h-64 overflow-y-auto text-xs">
              <Table
                columns={PRICE_HISTORY_COLUMNS}
                data={detail.price_history}
                rowKey={(rev) => rev.id}
                stickyHeader
                label={`Price history for ${detail.name}`}
              />
            </div>
          </div>
        </div>
      )}
    </Modal>
  );
}

function PriceRevisionModal({ road, onClose }: { road: TollRoad | null; onClose: () => void }) {
  const [effectiveDate, setEffectiveDate] = useState("");
  const [priceA, setPriceA] = useState("");
  const [indexation, setIndexation] = useState("quarterly");
  const [confidence, setConfidence] = useState("verified");
  const [error, setError] = useState<string | null>(null);

  const mutation = useCreateTollRoadPriceRevisionMutation();

  function reset() {
    setEffectiveDate("");
    setPriceA("");
    setIndexation("quarterly");
    setConfidence("verified");
    setError(null);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!road) return;
    setError(null);
    if (!effectiveDate) {
      setError("Effective date is required.");
      return;
    }
    const input: TollRoadPriceRevisionInput = {
      price_class_a_min: priceA || null,
      price_class_a_max: priceA || null,
      effective_date: effectiveDate,
      indexation,
      confidence,
    };
    try {
      await mutation.mutateAsync({ roadId: road.id, input });
      reset();
      onClose();
    } catch (err) {
      setError(extractErrorMessage(err));
    }
  }

  return (
    <Modal
      open={road != null}
      onClose={() => {
        reset();
        onClose();
      }}
      title={road ? `Add price revision — ${road.name}` : "Add price revision"}
      description="Inserts a NEW dated price. The existing price history is never overwritten or deleted — this is fare evidence."
      footer={
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={mutation.isPending}>
            Cancel
          </Button>
          <Button type="submit" form="toll-price-revision-form" disabled={mutation.isPending}>
            {mutation.isPending ? "Saving…" : "Add revision"}
          </Button>
        </>
      }
    >
      <form id="toll-price-revision-form" onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">Effective date</label>
          <Input type="date" value={effectiveDate} onChange={(e) => setEffectiveDate(e.target.value)} required />
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-muted-foreground">
            Class A price (AUD, flat/time-of-day roads)
          </label>
          <Input inputMode="decimal" value={priceA} onChange={(e) => setPriceA(e.target.value)} placeholder="e.g. 4.55" />
        </div>
        <div className="grid grid-cols-2 gap-4">
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Indexation</label>
            <Select
              value={indexation}
              onChange={(e) => setIndexation(e.target.value)}
              options={[
                { value: "quarterly", label: "Quarterly" },
                { value: "annual_1_january", label: "Annual (1 Jan)" },
                { value: "annual_or_scheduled", label: "Annual / scheduled" },
              ]}
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs font-medium text-muted-foreground">Confidence</label>
            <Select
              value={confidence}
              onChange={(e) => setConfidence(e.target.value)}
              options={[
                { value: "verified", label: "Verified" },
                { value: "needs_verification", label: "Needs verification" },
                { value: "not_captured", label: "Not captured" },
              ]}
            />
          </div>
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
