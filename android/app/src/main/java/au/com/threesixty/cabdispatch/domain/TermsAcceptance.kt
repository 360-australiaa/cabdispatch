package au.com.threesixty.cabdispatch.domain

import android.content.Context

/**
 * Tracks whether the driver has accepted the boot-time Terms & Conditions / Privacy Policy
 * disclaimer (2026-08-10 meter-polish pass, matching a real competitor taxi-meter's boot-time
 * disclaimer screen) — see [au.com.threesixty.cabdispatch.ui.screens.terms.TermsDisclaimerScreen].
 *
 * **Gating choice, documented per this task's own "your call, document the choice" instruction:**
 * gated per **app version** ([versionCode]), not per install. A plain "shown once per install"
 * flag would never re-surface the disclaimer even if a later release genuinely changes the terms
 * (new payment processor, new data-sharing clause, etc.) — re-prompting on every version bump is
 * the safer default for a compliance-adjacent screen, at the cost of one extra tap after every
 * app update. [android.content.SharedPreferences], same real, already-established pattern
 * [SharedPreferencesDriverAuthRepository] uses elsewhere in this codebase for small persisted
 * flags that do not need Room's query/relational surface — a plain key-value flag doesn't
 * justify a new Room entity/DAO/migration for one boolean-shaped fact.
 */
object TermsAcceptance {
    private const val PREFS_NAME = "terms_disclaimer"
    private const val KEY_ACCEPTED_VERSION_CODE = "accepted_version_code"

    /** True once [markAccepted] has been called for this exact [currentVersionCode] (or a later
     * one) — a stale acceptance recorded against an older version code does NOT count, per this
     * class's own "re-prompt on every version bump" doc above. */
    fun isAccepted(context: Context, currentVersionCode: Int): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val acceptedVersionCode = prefs.getInt(KEY_ACCEPTED_VERSION_CODE, -1)
        return acceptedVersionCode >= currentVersionCode
    }

    fun markAccepted(context: Context, currentVersionCode: Int) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACCEPTED_VERSION_CODE, currentVersionCode)
            .apply()
    }
}
