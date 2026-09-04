package au.com.threesixty.cabdispatch.domain

import android.content.SharedPreferences

/**
 * Minimal in-memory [SharedPreferences]/[SharedPreferences.Editor] stand-in for [SessionStoreTest]
 * — this project has no Robolectric dependency (see [KioskLockControllerTest]'s doc for this
 * codebase's usual way of avoiding a live Android framework object in a plain-JVM test, by testing
 * a decomposed pure function instead). `SharedPreferences` is a plain interface though, with no
 * framework method bodies to hit "Stub!" against, so a real [SessionStore] can be driven end-to-end
 * against a fake implementation of it instead — a genuine save/restore round trip, not just the
 * decision logic in isolation.
 *
 * Only the handful of members [SessionStore] actually calls
 * ([SharedPreferences.getString]/[SharedPreferences.edit],
 * [SharedPreferences.Editor.putString]/[SharedPreferences.Editor.clear]/[SharedPreferences.Editor.apply])
 * do anything real; everything else on these two interfaces throws
 * [UnsupportedOperationException] so a test that starts relying on one of them fails loudly
 * instead of silently no-opping.
 */
class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        throw UnsupportedOperationException()

    override fun getInt(key: String?, defValue: Int): Int = throw UnsupportedOperationException()
    override fun getLong(key: String?, defValue: Long): Long = throw UnsupportedOperationException()
    override fun getFloat(key: String?, defValue: Float): Float = throw UnsupportedOperationException()
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        throw UnsupportedOperationException()

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val pendingRemovals = mutableSetOf<String>()
        private var pendingClear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            throw UnsupportedOperationException()

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            throw UnsupportedOperationException()

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            throw UnsupportedOperationException()

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            throw UnsupportedOperationException()

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            throw UnsupportedOperationException()

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) pendingRemovals += key
        }

        override fun clear(): SharedPreferences.Editor = apply { pendingClear = true }

        override fun commit(): Boolean {
            applyPending()
            return true
        }

        override fun apply() {
            applyPending()
        }

        private fun applyPending() {
            if (pendingClear) values.clear()
            pendingRemovals.forEach { values.remove(it) }
            values.putAll(pending)
        }
    }
}
