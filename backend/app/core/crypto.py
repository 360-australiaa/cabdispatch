"""Reversible at-rest encryption for secrets the server must later read back
in plaintext (unlike passwords/PINs, which use one-way `app.core.security.
hash_password` and are never decrypted). The one consumer today is
`DuressDevice.secret_encrypted` (`app.models.duress_device`) -- the server
must recompute an HMAC using the same shared secret a duress device's
firmware holds, which is impossible if the secret were only stored as a
one-way hash.

Fernet (AES-128-CBC + HMAC-SHA256, from the `cryptography` package already a
project dependency via `app.services.tariff_signing`'s Ed25519 usage) is used
rather than hand-rolled AES so key handling and the MAC-then-encrypt
construction are library-verified.

*** PLACEHOLDER KEY IN `Settings.SECRET_ENCRYPTION_KEY` -- generate a real one
for production, do NOT use the default outside dev. *** Same
placeholder-key convention as `TARIFF_SIGNING_PRIVATE_KEY` /
`STRIPE_SECRET_KEY` in `app.core.config`. Generate a real key with:
    uv run python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
"""
from __future__ import annotations

from functools import lru_cache

from cryptography.fernet import Fernet, InvalidToken

from app.core.config import settings


class SecretDecryptionError(Exception):
    """Raised when a stored ciphertext can't be decrypted with the
    deployment's current `SECRET_ENCRYPTION_KEY` (wrong/rotated key, or
    corrupted data)."""


@lru_cache(maxsize=1)
def _fernet() -> Fernet:
    # Cached: Settings is a process-wide singleton and the key never changes
    # mid-process, so there is no point re-parsing it on every call.
    return Fernet(settings.SECRET_ENCRYPTION_KEY.encode("utf-8"))


def encrypt_secret(plaintext: str) -> str:
    """Returns a Fernet token (str, safe to store in a String column) for
    `plaintext`. Callers should never persist `plaintext` itself."""
    return _fernet().encrypt(plaintext.encode("utf-8")).decode("utf-8")


def decrypt_secret(token: str) -> str:
    """Inverse of `encrypt_secret`. Raises `SecretDecryptionError` (never the
    raw `cryptography` exception) on a bad/rotated key or corrupted token, so
    callers can catch one exception type regardless of the underlying cause."""
    try:
        return _fernet().decrypt(token.encode("utf-8")).decode("utf-8")
    except (InvalidToken, ValueError) as exc:
        raise SecretDecryptionError("Could not decrypt stored secret") from exc
