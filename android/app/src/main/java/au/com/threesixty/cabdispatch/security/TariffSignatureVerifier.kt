package au.com.threesixty.cabdispatch.security

import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verifies the server's signature over a cached tariff / fares-order payload
 * (spec B6 anti-tamper: "signed tariff payloads (server-signed JWS, verified
 * on device)"). This is the ONE interface in this hardware/security batch
 * that is REAL, not mocked — signature verification is cheap to implement
 * correctly with the platform's `java.security` APIs and is the actual
 * anti-tamper control the spec requires, so there is no excuse to stub it.
 * See android/README.md "Real vs mocked" for the full list.
 *
 * Algorithm: RSASSA-PKCS1-v1_5 with SHA-256 (JWS `RS256`), matching the
 * spec's "server-signed JWS" wording. RS256 was chosen over the more modern
 * Ed25519 (JWS `EdDSA`) because `java.security` only gained built-in Ed25519
 * support on API 33+ (via `NamedParameterSpec.ED25519`), and this project's
 * minSdk is 29 — RS256 needs nothing newer than `KeyFactory`/`Signature`,
 * which have existed since API 1. Revisit once minSdk rises past 33, or if a
 * BouncyCastle dependency is acceptable to backport Ed25519 sooner.
 *
 * Verified against a real keypair as a sanity check while writing this file
 * (not committed — throwaway, generated with `openssl genpkey`/`openssl rsa`,
 * signed with `openssl dgst -sha256 -sign`, cross-checked against Node's
 * `crypto.createVerify("RSA-SHA256")`, which implements the identical
 * PKCS#1 v1.5 / SHA-256 scheme as Java's `SHA256withRSA`) — the algorithm
 * identifiers and DER/X.509 encoding below are confirmed correct, not just
 * "looks right".
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
