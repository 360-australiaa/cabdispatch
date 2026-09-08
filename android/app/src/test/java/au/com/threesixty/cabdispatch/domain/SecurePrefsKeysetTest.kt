package au.com.threesixty.cabdispatch.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the crash-on-launch found on the test tablet (2026-09-08).
 *
 * `SecurePrefs` migrates each store from a plaintext file to an encrypted one. Both steps read a
 * prefs file by name and copy every entry out of it. On a device that had already run an encrypted
 * build, the file under that name is the encrypted store, whose `all` includes the two reserved
 * keys `androidx.security.crypto` keeps its Tink keyset under. Copying one back into an encrypted
 * store throws `SecurityException` from `Application.onCreate`, killing the process before any UI
 * exists — and the stash guard then treated the poisoned legacy file as "already stashed", so every
 * subsequent launch crashed identically. Permanent brick, on upgrade, on every tablet that already
 * had an encrypted store.
 *
 * These cover the pure decision only. The `EncryptedSharedPreferences` round trip itself needs a
 * real Android Keystore and is not honestly testable here — stated rather than faked.
 */
class SecurePrefsKeysetTest {

    @Test
    fun `a file holding androidx's keyset is recognised as an encrypted store`() {
        val entries = mapOf<String, Any?>(
            "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "AQID",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "BAUG",
            "some_encrypted_blob" to "ciphertext",
        )
        assertTrue(SecurePrefs.looksEncrypted(entries))
    }

    @Test
    fun `the value keyset alone is enough to recognise it`() {
        val entries = mapOf<String, Any?>(
            "__androidx_security_crypto_encrypted_prefs_value_keyset__" to "BAUG",
        )
        assertTrue(SecurePrefs.looksEncrypted(entries))
    }

    @Test
    fun `a genuine plaintext store is not mistaken for an encrypted one`() {
        // The real shipped shape: TokenStore's plaintext auth_tokens file.
        val entries = mapOf<String, Any?>(
            "access_token" to "header.payload.signature",
            "refresh_token" to "refresh-value",
            "driver_id" to "d-1",
        )
        assertFalse(SecurePrefs.looksEncrypted(entries))
    }

    @Test
    fun `an empty file is not an encrypted store`() {
        assertFalse(SecurePrefs.looksEncrypted(emptyMap()))
    }

    @Test
    fun `both reserved keys are covered, and nothing else is`() {
        // Pins the set: a third entry here would mean silently dropping real driver data.
        assertTrue(
            SecurePrefs.RESERVED_KEYSET_KEYS == setOf(
                "__androidx_security_crypto_encrypted_prefs_key_keyset__",
                "__androidx_security_crypto_encrypted_prefs_value_keyset__",
            ),
        )
    }

    @Test
    fun `a key that merely resembles the reserved prefix is still migrated`() {
        // Real driver data must never be dropped by an over-broad prefix match.
        val entries = mapOf<String, Any?>("__androidx_security_crypto_something_else__" to "keep me")
        assertFalse(SecurePrefs.looksEncrypted(entries))
    }
}
