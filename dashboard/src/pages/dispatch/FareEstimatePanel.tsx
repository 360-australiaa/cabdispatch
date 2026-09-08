import { Badge } from "@/components/ui";
import { useTariffSuggestQuery } from "@/hooks/useTariffStudio";

/**
 * Tariff context for a new job's pickup point, via `GET /v1/tariffs/suggest`
 * (`hooks/useTariffStudio.ts`).
 *
 * This is deliberately NOT a fare figure. `suggest_tariff`
 * (`backend/app/services/tariffs.py`) resolves *which signed tariff* would
 * apply to this pickup right now — name, day/night class, and why — and
 * that's all it returns; it does not compute a dollar amount. Money is the
 * meter's job: `FareEngine` on Android and `fare_engine.py` on the backend
 * are the only two places a fare is ever computed, both `Decimal`-exact,
 * golden-vector tested, and NSW round-down/PSL/toll/maxi aware. Reimplementing
 * any of that math here — even from real tariff rates, even for a rough
 * number — is exactly the client/server drift the jurisdiction seam exists
 * to prevent, so this panel never does it. If `/suggest` can't resolve a
 * tariff (404, or no query yet) it shows nothing rather than a guess.
 *
 * The dispatcher still types the fare estimate they intend to quote in the
 * form below (required by `POST /v1/jobs`); this panel exists only to tell
 * them which tariff and time-of-day that quote should be based on.
 */
export function FareEstimatePanel({ lat, lng }: { lat: number | null; lng: number | null }) {
  const suggestQuery = useTariffSuggestQuery(lat != null && lng != null ? { lat, lng } : null);

  if (lat == null || lng == null) {
    return null;
  }

  return (
    <div className="rounded-md border border-border bg-muted/40 p-3 text-sm">
      <p className="mb-1 font-medium text-foreground">Tariff that applies at this pickup point</p>
      {suggestQuery.isLoading && <p className="text-muted-foreground">Checking…</p>}
      {suggestQuery.isError && (
        <p className="text-muted-foreground">
          Couldn't resolve a tariff for this point right now — quote the fare from your own judgement.
        </p>
      )}
      {!suggestQuery.isLoading && !suggestQuery.isError && suggestQuery.data === null && (
        <p className="text-muted-foreground">
          No tariff resolves for this location right now — quote the fare from your own judgement.
        </p>
      )}
      {suggestQuery.data && (
        <p className="flex flex-wrap items-center gap-2 text-foreground">
          <Badge variant="primary">{suggestQuery.data.tariff_name}</Badge>
          <span className="text-muted-foreground">({suggestQuery.data.time_class})</span>
          <span className="text-muted-foreground">— {suggestQuery.data.reason}</span>
        </p>
      )}
      <p className="mt-2 text-xs text-muted-foreground">
        This is not a fare quote — <code>/v1/tariffs/suggest</code> only identifies the applicable
        tariff, not an amount. The actual fare is computed by the meter at trip end and will differ
        from any number entered below: this estimate excludes tolls, waiting time, and the route
        actually driven.
      </p>
    </div>
  );
}
