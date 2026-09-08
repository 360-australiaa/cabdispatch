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
enum class GpsQuality { NO_FIX, POOR, FAIR, GOOD, PERMISSION_DENIED }

object GpsQualityClassifier {
    /** Same tiers [SettingsViewModel] always used: <=10m GOOD, <=30m FAIR, else POOR. No fix
     * (`accuracyM == null`) is [GpsQuality.NO_FIX] regardless of permission — permission is checked
     * first and short-circuits to [GpsQuality.PERMISSION_DENIED] since a denied permission means
     * there was never a chance to read a fix at all. */
    fun classify(permissionGranted: Boolean, accuracyM: Float?): GpsQuality {
        if (!permissionGranted) return GpsQuality.PERMISSION_DENIED
        if (accuracyM == null) return GpsQuality.NO_FIX
        return when {
            accuracyM <= 10f -> GpsQuality.GOOD
            accuracyM <= 30f -> GpsQuality.FAIR
            else -> GpsQuality.POOR
        }
    }

    /** Whether [quality] is good enough to call GPS genuinely "OK" for a glance-zone status dot —
     * [GpsQuality.GOOD] or [GpsQuality.FAIR] both read as a usable fix; [GpsQuality.POOR] is real
     * signal but bad enough to flag, matching a real competitor meter's own GPS-quality dot. */
    fun isOk(quality: GpsQuality): Boolean = quality == GpsQuality.GOOD || quality == GpsQuality.FAIR
}
