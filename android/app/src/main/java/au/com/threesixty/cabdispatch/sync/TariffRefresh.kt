package au.com.threesixty.cabdispatch.sync

import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.domain.location.RegionResolver

/**
 * The one place anything outside the dashboard asks for a fresh tariff (finding S5).
 *
 * ### The gap
 * `TariffCache.refresh()` had **exactly one call site** in the whole app — inside
 * `WheelDashboardViewModel`'s `init`, which runs only when the wheel dashboard composes. Nothing
 * refreshed the tariff on reconnect, on a schedule, or at login. A tablet that stayed on the meter
 * screen, or was left parked, or simply never returned to the dashboard, kept billing from whatever
 * tariff happened to be cached — potentially for days after the rates it quotes were superseded.
 * For a regulated meter, quietly charging last month's fares is a compliance failure, not a
 * staleness annoyance.
 *
 * So the refresh is now attached to the three moments when it is both cheap and most likely to
 * matter: connectivity returning ([ConnectivitySyncTrigger]), the periodic sync backstop
 * ([SyncWorker]), and driver login. The dashboard's own call stays exactly as it was.
 *
 * ### Always best-effort
 * Every caller here is a background trigger with no user waiting on it and no UI to report into, so
 * failures are swallowed. That is safe because a failed refresh changes nothing — the previously
 * cached, signature-verified tariff stays in Room and keeps being used. `TariffCache.refresh` still
 * throws for the dashboard, which *does* have somewhere to show a "tariff may be stale" state; this
 * helper is the deliberate opposite for callers that don't.
 */
object TariffRefresh {

    /**
     * Refreshes the active tariff for wherever the tablet currently is.
     *
     * The region comes from the last known GPS fix through [RegionResolver], the same resolution
     * the dashboard uses — so a country-region tablet refreshes the country tariff rather than
     * whatever the default happens to be. With no fix yet (cold start, indoors, permissions not
     * granted) [RegionResolver] falls back to its own urban default, which is the right guess and
     * is corrected by the dashboard's region-change collector the moment a real fix arrives.
     */
    suspend fun refreshBestEffort() {
        val region = RegionResolver.resolve(AppContainer.speedSource.locationFix.value)
        runCatching { AppContainer.tariffCache.refresh(region) }
    }
}
