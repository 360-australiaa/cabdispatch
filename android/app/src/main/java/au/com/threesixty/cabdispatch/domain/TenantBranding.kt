package au.com.threesixty.cabdispatch.domain

/**
 * The operator/jurisdiction facts this app puts on screen about *whose* meter it is — read from a
 * field instead of being retyped as a literal at each use.
 *
 * ### What this replaces, and what it does NOT yet do
 * Before this (A7, 2026-09-08) the splash screen carried `"The Captain Taxis · NSW Taxi Meter"` and
 * the TSP authorisation number `TSP-448041` as inline string literals, two navigation-free taps
 * from a driver's eyes and completely invisible to the tenant system. A white-label deployment for
 * a second operator — which `docs/TCT-METER-01-spec.md` §B4.10 already lists as shipped scope
 * ("tenant theming (logo, palette, receipt template); Lilly Cabs preset") — would have shown that
 * operator's drivers another company's name and another company's regulatory authorisation number.
 * The authorisation number is the more serious of the two: it is a real TfNSW-issued TSP
 * authorisation, and displaying it on a tablet operated by a different authorised provider is a
 * false compliance claim, not a cosmetic branding slip.
 *
 * **This is a seam, not a solution.** The values below are still this repo's own defaults; nothing
 * fetches them yet. What has changed is that they are now read from ONE named field with a
 * documented owner, so the fetch is a one-file change instead of a string hunt. Honesty rule: this
 * class must never be described as "tenant-aware" until [source] says something other than
 * [Source.CompiledDefault].
 *
 * ### What these fields should become (Wave 3, X1/A6 — the ask this pass is handing forward)
 * The jurisdiction seam (X1 = backend `FareRegion` + Android `JurisdictionConfig`) is where these
 * belong, and they split across the two records it introduces:
 *
 * | Field | Should be sourced from | Notes |
 * |---|---|---|
 * | [operatorName] | the cached **tenant** record — `Tenant.name` | Already exists server-side; there is simply no client-side tenant cache yet (this app holds no `TenantDto` at all today — checked). B10/X2's tenant self-serve work is what makes it editable. |
 * | [meterDescription] | the cached **jurisdiction** record | "NSW Taxi Meter" is a jurisdiction label, not a tenant one: two Sydney operators share it, and one operator running cars in two states does not. Belongs next to `FareRegion`/`JurisdictionConfig`, NOT on the tenant. |
 * | [authorisationNumber] | the cached **tenant** record, jurisdiction-typed | The number is issued to the operator BY a jurisdiction, so it is a tenant field whose *label* ("TSP authorisation", the NSW term) comes from the jurisdiction. Nullable there and here: an operator in a jurisdiction with no such scheme has none, and the UI must then show nothing rather than an empty label or a placeholder. |
 *
 * The concrete backend gap to close alongside that: `GET /v1/tenants/{id}` returns no
 * authorisation-number field today, so X2/B10 has to add one before [Source.Tenant] can ever be
 * reported here.
 */
object TenantBranding {

    /** Where the values below actually came from — so no screen and no report can claim more than
     * this app has really established. See [source]. */
    enum class Source {
        /** Compiled into this build. True today, for every tenant. */
        CompiledDefault,

        /** Read from a cached tenant/jurisdiction record fetched from the server. Nothing returns
         * this yet; it exists so the day one does, the difference is visible rather than silent. */
        Tenant,
    }

    /** Always [Source.CompiledDefault] today — see this object's doc. */
    val source: Source = Source.CompiledDefault

    /** The trading name of the operator this build is deployed for. Should become `Tenant.name`. */
    const val operatorName: String = "The Captain Taxis"

    /** What kind of meter this is, in the terms of the jurisdiction it is authorised in. Should
     * become a jurisdiction-record field. */
    const val meterDescription: String = "NSW Taxi Meter"

    /**
     * The operator's regulatory authorisation number, as issued by its jurisdiction — TfNSW's TSP
     * authorisation, here. `null` for an operator whose jurisdiction issues no such number, in
     * which case every caller must omit it entirely rather than render a blank or a dash: a missing
     * authorisation number and an unknown one look identical on screen and mean very different
     * things.
     */
    val authorisationNumber: String? = "TSP-448041"

    /** `"The Captain Taxis · NSW Taxi Meter"` — the splash subtitle, composed rather than retyped. */
    val splashSubtitle: String get() = "$operatorName · $meterDescription"
}
