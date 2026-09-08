package au.com.threesixty.cabdispatch.domain

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hashing for the offline driver-PIN cache ([SharedPreferencesDriverAuthRepository]).
 *
 * ### Why this class exists (security finding X2)
 * The cache used to store `SHA-256(driverId:pin)` — no salt, no iteration count. A driver PIN is
 * 4–6 numeric digits, so the entire space is at most a million candidates; an unsalted single-round
 * SHA-256 lets anyone who reads the prefs file off a rooted or lost tablet recover the live PIN in
 * milliseconds, and that PIN is the credential for a regulated meter. The old code's own TODO
 * conceded the point ("adequate only because it's compared against a value stored in this app's
 * private SharedPreferences") — a private prefs file is not a security boundary against the
 * physical-device threat model this app actually has (tablets get lost, stolen and rooted).
 *
 * PBKDF2-HMAC-SHA256 at [ITERATIONS] rounds turns "exhaust a million PINs instantly" into hours of
 * work per device, and the per-device random salt ([saltFor]) means that work cannot be shared
 * across a fleet or precomputed into a rainbow table.
 *
 * ### Record format
 * [hash] returns a **self-describing** record:
 *
 *     pbkdf2_sha256$<iterations>$<base64 salt>$<base64 derived key>
 *
 * Encoding the algorithm, cost and salt alongside the digest (the same shape Django, passlib and
 * every other mature password store uses) is what makes a future cost increase or algorithm change
 * a non-event: [verify] reads the parameters out of the record it is checking rather than assuming
 * today's constants, so an entry written at 120k rounds keeps verifying after this file raises
 * [ITERATIONS], and gets rewritten at the new cost on the owner's next successful online login.
 *
 * ### Legacy records
 * A record with no `$` is a pre-X2 unsalted `SHA-256(driverId:pin)` hex digest. [verify] still
 * accepts those — see [isLegacy] and its call site — because refusing them outright would lock
 * every already-provisioned driver out of offline login until they next had connectivity, which is
 * exactly the situation offline login exists for. They are migrated lazily instead: a successful
 * **online** login rewrites the entry through [hash], so a legacy record survives only until the
 * driver's next login with a working network. There is deliberately no offline migration path —
 * re-hashing on a cached-PIN login would let an attacker who already has the weak digest upgrade it
 * to a strong one, which helps nobody.
 */
object OfflinePinHasher {

    /**
     * OWASP's floor for PBKDF2-HMAC-SHA256 is 210k as of 2023; the A2 workstream brief specifies
     * ≥120k, which is what this ships. The binding constraint is the tablet: this runs on the UI
     * path of a login the driver is waiting on, on low-end Android hardware, so the cost is chosen
     * to stay comfortably inside a few hundred milliseconds rather than to hit the OWASP number.
     * Raising it later is safe and needs no migration — see the "Record format" note above.
     */
    const val ITERATIONS = 120_000

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val PREFIX = "pbkdf2_sha256"

    private val secureRandom = SecureRandom()

    /** A fresh cryptographically-random salt. Callers persist this once per device — see
     * [SharedPreferencesDriverAuthRepository]'s salt handling. */
    fun newSalt(): ByteArray = ByteArray(SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }

    /** Encodes a salt for storage in SharedPreferences. */
    fun encodeSalt(salt: ByteArray): String = Base64.getEncoder().encodeToString(salt)

    /** Inverse of [encodeSalt]. Returns null on anything that isn't valid Base64, so a corrupted
     * prefs entry regenerates a salt rather than crashing the login screen. */
    fun decodeSalt(encoded: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()

    /**
     * Produces the storable record for [driverId] + [pin] under [salt].
     *
     * [driverId] is folded into the hashed material exactly as the legacy scheme did (`driverId:pin`),
     * so two drivers who happen to share a PIN on the same tablet still get different digests even
     * before the salt is applied.
     */
    fun hash(driverId: String, pin: String, salt: ByteArray): String {
        val derived = derive(driverId, pin, salt, ITERATIONS)
        return listOf(
            PREFIX,
            ITERATIONS.toString(),
            Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(derived),
        ).joinToString("$")
    }

    /**
     * Constant-time check of [pin] against a stored [record], which may be either a current
     * PBKDF2 record or a legacy unsalted SHA-256 hex digest (see the class doc).
     *
     * Returns false — never throws — for a malformed record: a corrupted cache entry must fail the
     * login honestly, not crash the app on the one screen the driver cannot get past.
     */
    fun verify(driverId: String, pin: String, record: String): Boolean {
        if (isLegacy(record)) return constantTimeEquals(record, legacySha256(driverId, pin))

        val parts = record.split("$")
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations <= 0) return false
        val salt = decodeSalt(parts[2]) ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false

        val actual = derive(driverId, pin, salt, iterations)
        return constantTimeEquals(expected, actual)
    }

    /** True for a pre-X2 record. See the class doc's "Legacy records" note for why these are still
     * accepted and how they are retired. */
    fun isLegacy(record: String): Boolean = !record.contains("$")

    private fun derive(driverId: String, pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec("$driverId:$pin".toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            // PBEKeySpec copies the password into its own char[]; clearing it drops the plaintext
            // PIN out of the heap as soon as the derivation is done rather than leaving it for
            // whenever GC happens to run.
            spec.clearPassword()
        }
    }

    /** The pre-X2 scheme, retained ONLY to verify (never to write) legacy records. */
    private fun legacySha256(driverId: String, pin: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$driverId:$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Length-independent, early-exit-free comparison — a PIN check is a secret comparison, and the
     * obvious `==` leaks how many leading bytes matched through timing. */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        constantTimeEquals(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
