package au.com.threesixty.cabdispatch.domain

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Opens the app's durable credential stores as `EncryptedSharedPreferences`, migrating off the
 * plaintext files they used to live in (security finding X3).
 *
 * ### What was wrong
 * Four secrets sat in ordinary `MODE_PRIVATE` SharedPreferences XML: the bearer token, the refresh
 * token, the **device secret** (the credential that lets a parked, driver-less tablet receive fleet
 * commands — kiosk lock, force-update, locate), and the offline PIN hash. `MODE_PRIVATE` is an
 * access-control flag enforced by the Linux uid sandbox; it is not encryption, and it protects
 * nothing at all once the device is rooted, unlocked with `adb` on a debug build, or simply backed
 * up. These tablets live in cars, get lost, and run a debug build in the field. Every one of those
 * four values is now stored under a hardware-backed AES key from the Android Keystore.
 *
 * ### The migration ([open])
 * Each store keeps its original prefs name for the encrypted file and reads the old plaintext file
 * once. If the plaintext file has anything in it, its entries are copied across and the plaintext
 * file is then **deleted** — not just abandoned. Leaving it behind would mean the tokens this
 * change is meant to protect stay readable on disk forever on every tablet that has already run,
 * which would make the whole exercise cosmetic.
 *
 * The migration is idempotent and self-retiring: once the plaintext file is gone,
 * [android.content.Context.getSharedPreferences] on the old name returns an empty set and the copy
 * step is a no-op, so this costs one empty map read per store per process start thereafter.
 *
 * ### When the Keystore is unusable
 * A corrupted or reset Android Keystore makes the encrypted file permanently undecryptable — a real
 * field failure mode (an OS update, a device-owner reset, a restored backup). [open] handles it by
 * discarding the unreadable file and its master key and starting a fresh encrypted store, which
 * costs the driver one PIN re-login and re-pairs nothing silently. The deliberate non-option here
 * is falling back to plaintext prefs: that would turn a rare recoverable failure into a silent,
 * permanent downgrade of exactly the property this class exists to provide, on a device nobody is
 * watching.
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"

    /**
     * The two keys `androidx.security.crypto` stores its own Tink keyset under, inside the very
     * prefs file it encrypts.
     *
     * They are the reason this class shipped a crash-on-launch (2026-09-08, found on the test
     * tablet). Both migration steps below read a prefs file *by name* as plaintext. On a device
     * that had already run an encrypted build, the file under that name IS the encrypted store,
     * and `all` hands back these two reserved entries as ordinary strings. They were copied to the
     * legacy slot and then written back into the encrypted store, where
     * `EncryptedSharedPreferences` rejects them outright:
     *
     *     SecurityException: __androidx_security_crypto_encrypted_prefs_key_keyset__ is a
     *     reserved key for the encryption keyset.
     *
     * That throws from `Application.onCreate`, so the process dies before any UI exists — and
     * because the stash guard treats a non-empty legacy file as "already stashed", every later
     * launch took the same path. The app was bricked permanently on upgrade, on every tablet that
     * already had an encrypted store, which is all of them.
     *
     * Skipping these keys is not merely a guard: a keyset is never app data. It belongs to the
     * store it came from and is meaningless in another one, so there is nothing to preserve.
     */
    internal val RESERVED_KEYSET_KEYS = setOf(
        "__androidx_security_crypto_encrypted_prefs_key_keyset__",
        "__androidx_security_crypto_encrypted_prefs_value_keyset__",
    )

    /**
     * True when [entries] came from a file that is already an `EncryptedSharedPreferences` store
     * rather than a genuine plaintext one. Detected by its keyset, which only that store writes.
     */
    internal fun looksEncrypted(entries: Map<String, Any?>) =
        entries.keys.any { it in RESERVED_KEYSET_KEYS }

    /**
     * The encrypted store for [name], with a one-time migration from the plaintext file of the
     * same name. See the class doc.
     */
    fun open(context: Context, name: String): SharedPreferences {
        val appContext = context.applicationContext
        val encrypted = openEncrypted(appContext, name)
        migratePlaintext(appContext, name, encrypted)
        return encrypted
    }

    private fun openEncrypted(context: Context, name: String): SharedPreferences =
        try {
            build(context, name)
        } catch (e: Exception) {
            // See "When the Keystore is unusable" above. Nothing here is recoverable by retrying
            // as-is, so drop the undecryptable state and rebuild once.
            Log.w(TAG, "Encrypted prefs '$name' unreadable; discarding and recreating", e)
            context.deleteSharedPreferences(name)
            build(context, name)
        }

    private fun build(context: Context, name: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Copies any surviving plaintext entries into [encrypted] and deletes the plaintext file.
     *
     * The plaintext file is read under its *legacy* name ([legacyName]) rather than [name], because
     * [openEncrypted] has already claimed [name] for the encrypted store — reading the same name
     * again would just hand back the encrypted store and copy it onto itself.
     */
    private fun migratePlaintext(context: Context, name: String, encrypted: SharedPreferences) {
        val legacy = legacyName(name)
        val plain = context.getSharedPreferences(legacy, Context.MODE_PRIVATE)
        val entries = plain.all
        if (entries.isEmpty()) {
            // Nothing to carry over. Still delete the (possibly zero-length) file so the store
            // stops existing at all rather than lingering as an empty XML.
            context.deleteSharedPreferences(legacy)
            return
        }

        val editor = encrypted.edit()
        for ((key, value) in entries) {
            // Never copy a keyset back into an encrypted store -- it throws, from onCreate.
            // See RESERVED_KEYSET_KEYS. Also self-heals a device already bricked by the old code:
            // the poisoned legacy file is now skipped past and then deleted below.
            if (key in RESERVED_KEYSET_KEYS) continue
            // Only the types these four stores actually write. An unknown type is dropped with a
            // log rather than crashing the app on launch — and dropping it is safe: every value
            // here is re-derivable by logging in or re-pairing.
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                else -> Log.w(TAG, "Skipping '$key' in '$legacy': unsupported type ${value?.javaClass}")
            }
        }
        editor.apply()

        // Only now that the values are safely in the encrypted store.
        context.deleteSharedPreferences(legacy)
        Log.i(TAG, "Migrated ${entries.size} entries from plaintext '$legacy' to encrypted '$name'")
    }

    /**
     * The plaintext file each store used before X3.
     *
     * These are the literal names that shipped, so they cannot change: `auth_tokens`
     * ([TokenStore]), `device_pairing` ([DevicePairingStore]) and `driver_auth_cache`
     * ([SharedPreferencesDriverAuthRepository]). The encrypted store reuses the same logical name,
     * so the legacy file is distinguished by a suffix instead.
     */
    private fun legacyName(name: String) = "${name}_plain_legacy"

    /**
     * Renames the shipped plaintext file to the [legacyName] slot, if it is still sitting under the
     * original name. Called once per store before the encrypted store claims that name.
     *
     * This exists because `EncryptedSharedPreferences` and plain prefs cannot share a filename: the
     * encrypted store must own `auth_tokens` going forward (so nothing else in the app needs to
     * learn a new name), which means the existing plaintext `auth_tokens.xml` has to move out of
     * the way before it can be read and retired.
     */
    fun stashLegacyPlaintext(context: Context, name: String) {
        val appContext = context.applicationContext
        val legacy = legacyName(name)
        if (appContext.getSharedPreferences(legacy, Context.MODE_PRIVATE).all.isNotEmpty()) {
            // A previous run already stashed it and was interrupted before the copy completed.
            return
        }
        val original = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        val entries = original.all
        if (entries.isEmpty()) return
        // The file under this name is already the encrypted store (this device has run an
        // encrypted build before), so there is no plaintext here to rescue. Stashing it would copy
        // the keyset into the legacy slot and brick the next launch -- see RESERVED_KEYSET_KEYS.
        if (looksEncrypted(entries)) return

        val editor = appContext.getSharedPreferences(legacy, Context.MODE_PRIVATE).edit()
        for ((key, value) in entries) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                else -> Unit
            }
        }
        editor.commit()
        appContext.deleteSharedPreferences(name)
    }
}

/** Opens the offline driver-PIN cache. See [SecurePrefs]. */
internal object AuthCachePrefs {
    const val NAME = "driver_auth_cache"

    fun open(context: Context): SharedPreferences {
        SecurePrefs.stashLegacyPlaintext(context, NAME)
        return SecurePrefs.open(context, NAME)
    }
}
