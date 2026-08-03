package au.com.threesixty.cabdispatch.security

import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Verifies the server's signature over a cached tariff / fares-order payload
 * (spec B6 anti-tamper: "signed tariff payloads (server-signed JWS, verified
 * on device)"). This is the ONE interface in this hardware/security batch
 * that is REAL, not mocked — signature verification is cheap to implement
 * correctly with the platform's `java.security` APIs and is the actual
 * anti-tamper control the spec requires, so there is no excuse to stub it.
 * See android/README.md "Real vs mocked" for the full list.
 *
 * Two implementations exist:
 * - [Ed25519TariffSignatureVerifier] — the one actually wired into
 *   [au.com.threesixty.cabdispatch.data.AppContainer] and used by
 *   [au.com.threesixty.cabdispatch.sync.TariffCache]. The backend signs with Ed25519, not RSA
 *   (`backend/app/services/tariff_signing.py`, confirmed in shared/API_SUMMARY.md's "Tariff
 *   signing" note) — this class is the real counterpart to that.
 * - [RsaTariffSignatureVerifier] — this file's original implementation, written before the
 *   backend's actual signing algorithm was decided (see its own doc for why RS256 was picked at
 *   the time: `java.security` only gained built-in Ed25519 support on API 33+, and this
 *   project's minSdk is 29). Kept defined (not deleted, still exercised by its own unit-testable
 *   logic) but no longer the one anything constructs by default — nothing this backend serves
 *   would ever verify against it, since the signatures are Ed25519 bytes, not RSA ones.
 *
 * [Ed25519TariffSignatureVerifier] closes the minSdk-29 gap the RSA class's doc flagged by using
 * BouncyCastle (`org.bouncycastle:bcprov-jdk18on`, see app/build.gradle.kts) rather than
 * `java.security`'s platform Ed25519 support directly — BC implements `KeyFactory`/`Signature`
 * for `"Ed25519"` identically on every API level this app supports, so no minSdk bump was needed.
 */
interface TariffSignatureVerifier {
    /**
     * [payloadJson] must be the *exact* canonical JSON bytes that were
     * signed server-side (RSA signatures are over the literal byte stream,
     * not a JSON-semantic comparison — whitespace/key-order matters).
     * [signatureBase64] is the signature, standard (non-URL-safe)
     * Base64-encoded. Returns `false` — never throws — on any malformed
     * input, wrong-key, or tampered-payload case: callers must fail closed
     * (treat "not verified" as "do not trust this cached tariff").
     */
    fun verify(payloadJson: String, signatureBase64: String): Boolean

    /**
     * Convenience for a compact JWS string (`base64url(header).base64url(payload).base64url(signature)`,
     * per RFC 7515 §7.1) — verifies the signature over the ASCII bytes of
     * `base64url(header) + "." + base64url(payload)` (the JWS "signing
     * input"), then returns the decoded payload JSON iff the signature is
     * valid. This is the shape the spec's "server-signed JWS" wording
     * implies the backend actually issues; [verify] above is the lower-level
     * primitive this is built on, kept public because a non-JWS transport
     * (e.g. a payload+signature pair in two separate JSON fields) is equally
     * plausible depending on how the backend/tariff-signing agent ships it.
     */
    fun verifyCompactJws(jws: String): String?
}

class RsaTariffSignatureVerifier(
    publicKeyBase64: String = TENANT_TARIFF_PUBLIC_KEY_B64,
) : TariffSignatureVerifier {

    private val publicKey: PublicKey = decodePublicKey(publicKeyBase64)

    override fun verify(payloadJson: String, signatureBase64: String): Boolean = try {
        val signatureBytes = Base64.decode(signatureBase64, Base64.DEFAULT)
        verifyBytes(payloadJson.toByteArray(Charsets.UTF_8), signatureBytes)
    } catch (e: Exception) {
        // Any parsing/format/crypto failure is treated as "not verified" —
        // fail closed, never fail open on a malformed signature.
        false
    }

    override fun verifyCompactJws(jws: String): String? = try {
        val parts = jws.split(".")
        require(parts.size == 3) { "not a 3-part compact JWS" }
        val (headerB64Url, payloadB64Url, signatureB64Url) = parts

        val signingInput = "$headerB64Url.$payloadB64Url".toByteArray(Charsets.US_ASCII)
        val signatureBytes = base64UrlDecode(signatureB64Url)

        if (verifyBytes(signingInput, signatureBytes)) {
            String(base64UrlDecode(payloadB64Url), Charsets.UTF_8)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun verifyBytes(signedBytes: ByteArray, signatureBytes: ByteArray): Boolean {
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update(signedBytes)
        return signature.verify(signatureBytes)
    }

    private fun base64UrlDecode(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decodePublicKey(base64: String): PublicKey {
        val keyBytes = Base64.decode(base64, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    companion object {
        /**
         * *** PLACEHOLDER KEY — REPLACE BEFORE SHIPPING ***
         *
         * X.509 SubjectPublicKeyInfo, Base64-encoded, for a throwaway
         * 2048-bit RSA keypair generated purely so this class exercises real
         * verification logic against real DER bytes (so it isn't untestable
         * dead code) — see the class doc's "verified against a real keypair"
         * note. It does NOT correspond to any backend signing key.
         *
         * TODO: reconcile with backend/tariff-signing agent — swap this for
         * the tenant's actual production public key, and strongly consider
         * moving it out of source into a signed remote-config value instead
         * of a compiled-in constant, since a hardcoded key can't be rotated
         * without an app release.
         */
        const val TENANT_TARIFF_PUBLIC_KEY_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwb3qHsGzhe+EGwF6zL8r" +
                "nVTwLLWFVqp49spttaijRFKxhhxz3KKIkSfCMP69Ki/oOBtXhhYkXE+OU6r5Llxa" +
                "qCRxhi+mCeXmgdB85OlFqEFnCC3Kcg/P7WzTOrvKhrAZlMrSof9O/QLuiqhwcsCS" +
                "FhEhQAhTLgI/gkxW3um0gf7lK6mEIrmurt2ryShxRQWpmeoyVoaeyYehP2yPeZ7h" +
                "ojmgUTcnR/A/H+EK1H2vzgUBtiNfh2GH8HyQs5vzztSPr2AN3eLVDACDOXyGKvfV" +
                "x3Vuxpf3jpyaRqq0WWnpwgaEQSVPierbKyOgRe+qYaMOV1H+jP5dUiJZWAOgA8t" +
                "H/wIDAQAB"
    }
}

/**
 * Real Ed25519 verifier — the one [au.com.threesixty.cabdispatch.data.AppContainer] actually
 * constructs, and the counterpart to `backend/app/services/tariff_signing.py`'s
 * `verify_tariff_signature`. [publicKeyBase64] must be an X.509 SubjectPublicKeyInfo DER,
 * base64-encoded (exactly what `GET /v1/tariffs/signing-public-key` serves — see
 * [au.com.threesixty.cabdispatch.data.remote.TariffSigningPublicKeyDto] — and exactly what
 * `app.services.tariff_signing.get_signing_public_key_b64` produces server-side).
 *
 * Uses BouncyCastle (`org.bouncycastle:bcprov-jdk18on`) as an explicit [java.security.Provider]
 * passed to [KeyFactory.getInstance]/[Signature.getInstance] — deliberately NOT
 * `Security.addProvider(BouncyCastleProvider())` globally, which would risk shadowing/reordering
 * the platform's own crypto providers app-wide for every other `java.security` call in this
 * process, a much bigger blast radius than this one verifier needs. A fresh
 * [BouncyCastleProvider] instance per verifier is cheap (no native/JNI init, pure-JVM
 * implementation) and keeps the change fully local to tariff-signature verification.
 *
 * Same fail-closed contract as [RsaTariffSignatureVerifier]: [verify]/[verifyCompactJws] never
 * throw, always return `false`/`null` on any malformed input, wrong-key, or tampered-payload
 * case.
 */
class Ed25519TariffSignatureVerifier(publicKeyBase64: String) : TariffSignatureVerifier {

    private val provider = BouncyCastleProvider()
    private val publicKey: PublicKey = decodePublicKey(publicKeyBase64)

    override fun verify(payloadJson: String, signatureBase64: String): Boolean = try {
        val signatureBytes = Base64.decode(signatureBase64, Base64.DEFAULT)
        verifyBytes(payloadJson.toByteArray(Charsets.UTF_8), signatureBytes)
    } catch (e: Exception) {
        false
    }

    override fun verifyCompactJws(jws: String): String? = try {
        val parts = jws.split(".")
        require(parts.size == 3) { "not a 3-part compact JWS" }
        val (headerB64Url, payloadB64Url, signatureB64Url) = parts

        val signingInput = "$headerB64Url.$payloadB64Url".toByteArray(Charsets.US_ASCII)
        val signatureBytes = base64UrlDecode(signatureB64Url)

        if (verifyBytes(signingInput, signatureBytes)) {
            String(base64UrlDecode(payloadB64Url), Charsets.UTF_8)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun verifyBytes(signedBytes: ByteArray, signatureBytes: ByteArray): Boolean {
        // Ed25519 (unlike RSA/ECDSA) has no separate digest step to name — the single
        // algorithm identifier "Ed25519" covers hashing + signing together (RFC 8032), so this
        // is intentionally not e.g. "SHA512withEd25519".
        val signature = Signature.getInstance("Ed25519", provider)
        signature.initVerify(publicKey)
        signature.update(signedBytes)
        return signature.verify(signatureBytes)
    }

    private fun base64UrlDecode(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decodePublicKey(base64: String): PublicKey {
        val keyBytes = Base64.decode(base64, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("Ed25519", provider).generatePublic(spec)
    }
}
