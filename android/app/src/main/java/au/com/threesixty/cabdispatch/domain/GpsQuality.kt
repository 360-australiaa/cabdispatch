package au.com.threesixty.cabdispatch.domain

/**
 * Coarse "how much can we trust this location fix" tiering — originally a private inline concept
 * on [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel] (S6/Settings'
 * diagnostics card), extracted here (2026-09-02, Home-dashboard redesign pass) so the dashboard's
 * status strip can share the exact same accuracy thresholds instead of the dashboard's own
 * `gpsOk` boolean staying a weaker "permission granted + provider enabled" proxy that says nothing
 * about whether the fix you'd actually get is any good. Behavior-preserving for Settings: the
 * enum name and every threshold below are unchanged from the values [SettingsViewModel] already
 * shipped, just given one home both screens read from instead of two copies free to drift.
 */
enum class GpsQuality { NO_FIX, POOR, FAIR, GOOD, STALE, PERMISSION_DENIED }

object GpsQualityClassifier {
    /**
     * Same accuracy tiers [SettingsViewModel] always used: <=10m GOOD, <=30m FAIR, else POOR. No
     * fix (`accuracyM == null`) is [GpsQuality.NO_FIX] regardless of permission — permission is
     * checked first and short-circuits to [GpsQuality.PERMISSION_DENIED] since a denied permission
     * means there was never a chance to read a fix at all.
     *
     * [fixAgeMs] — how long ago the fix being classified was actually received, if known — is
     * checked BEFORE the accuracy tiers and wins over all of them (G2, GPS blackout program,
     * 2026-09-12): a fix reporting ±5m accuracy from eight seconds ago describes where the vehicle
     * WAS, not where it is, and a status dot that still reads GOOD inside a tunnel is exactly the
     * gap this fixes — see [au.com.threesixty.cabdispatch.domain.LocationFix.MAX_FIX_AGE_MS]'s own
     * doc for why this is the SAME threshold [au.com.threesixty.cabdispatch.domain.FareEngineImpl
     * .tick] bills against, not a second, independently-tuned number. `null` (the caller has no
     * reliable way to measure age — see both call sites' own comments) skips this check entirely
     * and falls through to the accuracy tiers exactly as before this field existed.
     */
    fun classify(permissionGranted: Boolean, accuracyM: Float?, fixAgeMs: Long? = null): GpsQuality {
        if (!permissionGranted) return GpsQuality.PERMISSION_DENIED
        if (accuracyM == null) return GpsQuality.NO_FIX
        if (fixAgeMs != null && fixAgeMs > au.com.threesixty.cabdispatch.domain.LocationFix.MAX_FIX_AGE_MS) {
            return GpsQuality.STALE
        }
        return when {
            accuracyM <= 10f -> GpsQuality.GOOD
            accuracyM <= 30f -> GpsQuality.FAIR
            else -> GpsQuality.POOR
        }
    }

    /** Whether [quality] is good enough to call GPS genuinely "OK" for a glance-zone status dot —
     * [GpsQuality.GOOD] or [GpsQuality.FAIR] both read as a usable fix; [GpsQuality.POOR] is real
     * signal but bad enough to flag, matching a real competitor meter's own GPS-quality dot.
     * [GpsQuality.STALE] is never OK — a fix this old is not a usable fix, whatever its accuracy
     * used to be. */
    fun isOk(quality: GpsQuality): Boolean = quality == GpsQuality.GOOD || quality == GpsQuality.FAIR
}
