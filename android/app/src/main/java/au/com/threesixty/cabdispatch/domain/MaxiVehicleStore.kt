package au.com.threesixty.cabdispatch.domain

import android.content.Context

/**
 * Persists a driver's **local self-declaration** that the currently-bound vehicle has 5+ seats
 * excluding the driver ("is a maxi taxi") — Point to Point Transport (Fares) Order 2026 UI-wiring
 * pass.
 *
 * There is no real backend field for this: `VehicleDto` (`data/remote/ApiService.kt`) is
 * `id`+`rego` only, confirmed by the prior fare-engine-compliance and dashboard-redesign passes'
 * own audits. Rather than fabricate a fleet-registry-backed flag that doesn't exist, this is
 * deliberately labelled and stored as exactly what it is: a per-device, driver-entered
 * declaration, settable from the Settings → Fare schedule screen or directly from the Home
 * dashboard's Start Meter card. It is honest about its own limits — see the "backend
 * requirements" note in `MAXI_TAXI_UI_2026.md` for what a real `VehicleDto.isMaxi`/
 * `seatingCapacity` field would unlock instead.
 *
 * Mirrors [DevicePairingStore]'s own `getSharedPreferences(..., MODE_PRIVATE)` pattern — the
 * existing precedent for small durable per-device state in this app, not a new persistence
 * mechanism. Deliberately its own small class (not folded into [DevicePairingStore]) since this is
 * vehicle-characteristic state, not device-pairing state, and survives a device re-pair/factory
 * reset independently (a driver switching a paired tablet between two different physical vehicles
 * — one maxi, one not — should not have this silently reset by an unrelated pairing action).
 */
class MaxiVehicleStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("maxi_vehicle_declaration", Context.MODE_PRIVATE)

    /** `false` (not a maxi taxi) until a driver explicitly declares otherwise — never defaults to
     * `true`, so a driver who never touches this control gets the ordinary rate, exactly as
     * before this feature existed. */
    fun isMaxiVehicle(): Boolean = prefs.getBoolean(KEY_IS_MAXI, false)

    fun setMaxiVehicle(value: Boolean) {
        prefs.edit().putBoolean(KEY_IS_MAXI, value).apply()
    }

    private companion object {
        const val KEY_IS_MAXI = "is_maxi_vehicle"
    }
}
