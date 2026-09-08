package au.com.threesixty.cabdispatch.domain

import android.content.Context

/**
 * Has a technician ever commissioned this tablet?
 *
 * A tablet is installed by a technician, not by the driver who later uses it, and the two need
 * completely different things from the same set of facts. The technician is standing at the vehicle
 * with time to fix things: they need every check laid out with a tick or a cross and a way to
 * resolve each one — pair it, download the maps, confirm GPS actually sees satellites. The driver
 * turning up at 4am needs the login screen and nothing else, and should only ever be stopped by
 * something that genuinely prevents them working.
 *
 * So the readiness screen has two framings and this flag is what chooses between them:
 * commissioning (first install, full checklist, finished explicitly) and the ordinary guard gate
 * (silent unless a blocking check fails).
 *
 * SharedPreferences rather than a column on the server: it describes what a human did to this
 * physical unit, it must survive with no network, and it is answerable before the tablet has any
 * server identity at all. Cleared by factory reset, alongside the pairing — a tablet handed to
 * another depot must be commissioned again.
 */
class CommissioningStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Epoch millis of the moment setup was finished, or `null` if it never has been. */
    fun completedAt(): Long? =
        prefs.getLong(KEY_COMPLETED_AT, 0L).takeIf { it > 0L }

    fun isCommissioned(): Boolean = completedAt() != null

    fun markCommissioned(atMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_COMPLETED_AT, atMillis).apply()
    }

    /** Sends the tablet back to first-install state. Called from factory reset, and by a
     * technician re-running setup deliberately. */
    fun clear() {
        prefs.edit().remove(KEY_COMPLETED_AT).apply()
    }

    private companion object {
        const val PREFS_NAME = "device_commissioning"
        const val KEY_COMPLETED_AT = "completed_at"
    }
}
