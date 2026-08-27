"""Ed25519 tariff-payload signing — the backend counterpart to the Android
app's `TariffSignatureVerifier`
(android/.../security/TariffSignatureVerifier.kt), closing the loop that
file's own TODO flags ("reconcile with backend/tariff-signing agent").

NOTE for whoever wires up the Android side: `TariffSignatureVerifier.kt`
currently only ships an RSA (SHA256withRSA) implementation
(`RsaTariffSignatureVerifier`) — its class doc explains why Ed25519 wasn't
used there originally (minSdk 29 predates `java.security`'s Ed25519 support,
added in API 33). This backend signs with Ed25519 as directed, so the
Android side will need a *new* Ed25519 verifier (BouncyCastle, or
`java.security`'s `NamedParameterSpec.ED25519` once minSdk allows it) that
consumes the exact wire format documented below — the existing RSA verifier
cannot verify these signatures.

--- Key management -----------------------------------------------------------

One platform-wide Ed25519 keypair (not per-tenant — tariffs across all
tenants are signed with the same key; devices only need one public key to
trust). The private key lives in `settings.TARIFF_SIGNING_PRIVATE_KEY`
(PKCS8 DER, base64) — see app.core.config for the placeholder-key warning.
The public key is served unauthenticated from
`GET /v1/tariffs/signing-public-key` (X.509 SubjectPublicKeyInfo DER,
base64) — public keys aren't secret, and devices need to be able to fetch it
before/without being otherwise authenticated.

--- Canonical serialization ("what actually gets signed") --------------------

A compact (no whitespace) JSON object, UTF-8 encoded, with keys in a FIXED
declaration order (not alphabetical) exactly as built by
`canonical_tariff_payload` below:

    id, tenant_id, region, effective_from, effective_to, booked,
    <then the 15 rate fields in RATE_FIELDS order>

Rules, so an independent (e.g. Kotlin) implementation reproduces the exact
same bytes:

- Field order is fixed as above — never alphabetized, never reordered.
- `id` / `tenant_id` / `region`: plain JSON strings. `tenant_id` is `null`
  only for the (currently unreachable via `/active`, tenant-scoped) global
  Fares Order reference row.
- `effective_from` / `effective_to`: ISO 8601 on a timezone-aware UTC
  datetime, with a `Z` suffix (NOT `+00:00`) -- e.g. `"2025-11-03T00:00:00Z"`.
  This matches Pydantic v2's actual JSON encoding of these fields on
  `GET /v1/tariffs/active`'s response exactly (verify this against a live
  response if in doubt -- do not trust this doc comment over the real wire
  format, that mismatch is exactly the bug this rule now exists to prevent).
  `effective_to` is JSON `null` when unset.
- `booked`: JSON `true`/`false`.
- Every rate field (Decimal in the DB) is serialized as a **decimal string**
  (never a JSON number/float — floats round-trip lossily), quantized to
  exactly 4 decimal places, e.g. `"5.0000"`, `"2.5600"` — this matches the
  `Numeric(10, 4)` column precision (`app.models.tariffs._RATE`) and removes
  any ambiguity from how many trailing zeros a given Decimal happens to
  carry in memory.
- `json.dumps(..., separators=(",", ":"), ensure_ascii=True)` — i.e. no
  spaces after `:`/`,`, and any non-ASCII escaped (none expected in practice
  since these are numeric/enum/UUID fields, but fixing the rule removes an
  ambiguity).

Example (abbreviated) signed payload:

    {"id":"...","tenant_id":"...","region":"urban","effective_from":"2025-11-03T00:00:00Z","effective_to":null,"booked":false,"flag_fall":"5.0000","peak_charge":"2.5600",...}

The signature is `base64(Ed25519.sign(utf8_bytes(payload)))`, standard
(non-URL-safe) Base64 — matching the existing RSA verifier's Base64
convention in TariffSignatureVerifier.kt for consistency, even though a new
verifier class is needed to actually consume it.
"""
from __future__ import annotations

import base64
import json
from decimal import ROUND_HALF_UP, Decimal
from functools import lru_cache
from typing import TYPE_CHECKING, Any

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ed25519

from app.core.config import settings

if TYPE_CHECKING:
    from app.models.tariffs import Tariff

# Every rate field on the Tariff model/fare_engine dataclass — same set and
# order as app.services.tariffs._FARE_ENGINE_FIELDS (kept as an independent
# tuple here rather than importing that private name, since this module's
# ordering is a signed wire-format contract and must not silently change if
# that internal list is ever reordered for unrelated reasons).
RATE_FIELDS: tuple[str, ...] = (
    "flag_fall",
    "peak_charge",
    "dist_rate_1",
    "dist_rate_2",
    "night_rate_1",
    "night_rate_2",
    "holiday_rate_1",
    "holiday_rate_2",
    "waiting_rate_per_min",
    "dist_km_threshold",
    "speed_threshold_kmh",
    "maxi_multiplier",
    "multi_hire_pct",
    "psl_amount",
    "surcharge_pct_cap",
)

_RATE_QUANT = Decimal("0.0001")  # matches Numeric(10, 4) column precision


def _fmt_rate(value: Decimal) -> str:
    return str(Decimal(value).quantize(_RATE_QUANT, rounding=ROUND_HALF_UP))


def _iso_utc(dt) -> str:
    """ISO-8601 formatting that exactly matches the actual GET /v1/tariffs/active
    wire response -- Pydantic v2's default JSON encoding for a UTC-aware datetime
    emits a Z suffix (e.g. "2025-11-03T00:00:00Z"), NOT Python stdlib
    datetime.isoformat()'s +00:00 (e.g. "2025-11-03T00:00:00+00:00").

    REAL BUG this fixes (found live, 2026-08-27, via a real Android device against
    the deployed server): canonical_tariff_payload previously called .isoformat()
    directly, so the server SIGNED the +00:00 string while the actual response
    shipped the Z string. Any spec-compliant independent verifier (rebuilding the
    canonical payload from the wire JSON, exactly as it must) would reconstruct the
    Z string, which never matches what was signed -- every tariff signature failed
    to verify, for every device, always, since this signing feature was added. This
    was not caught by tests because tests/test_tariffs.py's own
    _reconstruct_canonical_payload helper had the identical bug (re-parsed the wire
    string via datetime.fromisoformat(...).isoformat(), which round-trips back to
    +00:00) -- fixed in the same pass to use the raw wire string directly, matching
    what a genuinely independent client does.

    Only the exact +00:00 suffix is rewritten to Z (a plain string operation, not a
    reparse-and-reformat) -- any other offset is left untouched, matching how
    Pydantic only special-cases exact UTC.
    """
    iso = dt.isoformat()
    if iso.endswith("+00:00"):
        iso = iso[:-6] + "Z"
    return iso


def canonical_tariff_payload(tariff: Tariff) -> bytes:
    """The exact UTF-8 byte string that gets Ed25519-signed / must be
    verified against. See this module's docstring for the field-by-field
    format rules — this function is the reference implementation of that
    contract."""
    fields: list[tuple[str, Any]] = [
        ("id", tariff.id),
        ("tenant_id", tariff.tenant_id),
        ("region", tariff.region),
        ("effective_from", _iso_utc(tariff.effective_from)),
        ("effective_to", _iso_utc(tariff.effective_to) if tariff.effective_to else None),
        ("booked", tariff.booked),
    ]
    fields.extend((name, _fmt_rate(getattr(tariff, name))) for name in RATE_FIELDS)
    return json.dumps(dict(fields), separators=(",", ":"), ensure_ascii=True).encode("utf-8")


@lru_cache(maxsize=1)
def _signing_key() -> ed25519.Ed25519PrivateKey:
    """Loads the platform Ed25519 signing key from settings. Cached — the key
    doesn't change during a process's lifetime, and re-parsing PKCS8 DER on
    every sign call would be wasted work on the `/active` hot path."""
    der = base64.b64decode(settings.TARIFF_SIGNING_PRIVATE_KEY)
    key = serialization.load_der_private_key(der, password=None)
    if not isinstance(key, ed25519.Ed25519PrivateKey):
        raise TypeError(
            "TARIFF_SIGNING_PRIVATE_KEY does not decode to an Ed25519 private key "
            f"(got {type(key).__name__}) — expected PKCS8 DER, base64-encoded"
        )
    return key


def get_signing_public_key_b64() -> str:
    """X.509 SubjectPublicKeyInfo DER, base64-encoded — the exact format
    `GET /v1/tariffs/signing-public-key` serves, and what an Android
    `KeyFactory.getInstance("Ed25519")` / `X509EncodedKeySpec` verifier would
    decode, matching the existing RSA verifier's key-encoding convention."""
    der = _signing_key().public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return base64.b64encode(der).decode("ascii")


def sign_tariff(tariff: Tariff) -> str:
    """Ed25519-signs `canonical_tariff_payload(tariff)`, returns standard
    (non-URL-safe) Base64. This is the `signature` field value returned by
    `GET /v1/tariffs/active`."""
    signature = _signing_key().sign(canonical_tariff_payload(tariff))
    return base64.b64encode(signature).decode("ascii")


def verify_tariff_signature(tariff: Tariff, signature_b64: str) -> bool:
    """Verifies a base64 signature against `tariff` using the *public* half
    of the same keypair — i.e. exactly what an external verifier (Android,
    or this module's own tests) does, not a shortcut that trusts the private
    key. Never raises; returns False on any malformed/invalid input."""
    try:
        public_key = ed25519.Ed25519PublicKey.from_public_bytes(
            _signing_key().public_key().public_bytes(
                encoding=serialization.Encoding.Raw,
                format=serialization.PublicFormat.Raw,
            )
        )
        public_key.verify(base64.b64decode(signature_b64), canonical_tariff_payload(tariff))
        return True
    except (InvalidSignature, ValueError, TypeError):
        return False
