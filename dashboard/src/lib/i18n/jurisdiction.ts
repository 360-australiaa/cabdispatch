/**
 * Jurisdiction capability seam — gates the NSW-only surfaces the audit
 * flagged (§4, WS-F.4): the entire `/psl` route (Passenger Service Levy is a
 * NSW-specific charge), the "NSW Toll Roads" tab in Tariff Studio, and the
 * NSW PtP export card in Compliance Vault.
 *
 * HONESTY NOTE, read before "fixing" this: the real answer is
 * `tenant.jurisdiction` (or equivalent) on the backend `Tenant` row, set by
 * workstream X1 (`B9` backend half — see the program plan, §4 D6 header).
 * X1 has not merged as of this branch (`git log backend/app/models/tenant.py`
 * shows no `timezone`/`currency`/jurisdiction column; confirmed against
 * `wave3/x1-jurisdiction`, which is itself still at the Wave-2 merge point
 * and has not started that work). Faking a jurisdiction default here would
 * be exactly the "ship a plausible default" the task brief forbids, so this
 * returns capability = true for every tenant today — the same behaviour the
 * product already has — and is written as the one place that has to change
 * once the tenant record can actually answer the question. Nothing at any
 * of the three call sites needs to change; they ask this function, not the
 * tenant record, for the answer.
 */

/** Minimal shape this needs from a tenant record — matches
 * `hooks/useWhite-labelSettings.ts`'s `TenantRead` today, and is written
 * loosely so it also accepts whatever `TenantRead` gains once X1 lands a
 * jurisdiction field, without an import cycle back into that hook. */
export interface JurisdictionAwareTenant {
  id?: string;
  jurisdiction?: string | null;
}

export interface JurisdictionCapabilities {
  /** Passenger Service Levy — NSW only. Gates the `/psl` nav item + route. */
  psl: boolean;
  /** NSW e-Toll gantry pricing tab in Tariff Studio. */
  nswTollRoads: boolean;
  /** NSW Point to Point Transport Commissioner CSV export. */
  nswPtpExport: boolean;
}

/**
 * TODO(X1): once `Tenant.jurisdiction` exists, this becomes
 * `tenant?.jurisdiction == null || tenant.jurisdiction === "NSW"` for each
 * flag (NSW tenants keep all three enabled; every other jurisdiction gets
 * `false` until its own equivalents are built) rather than the unconditional
 * `true` below.
 */
export function getJurisdictionCapabilities(
  _tenant: JurisdictionAwareTenant | null | undefined,
): JurisdictionCapabilities {
  return { psl: true, nswTollRoads: true, nswPtpExport: true };
}
