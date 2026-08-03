"""Application settings — single source of truth for all environment configuration.

Loaded once as a module-level `settings` singleton. All values may be overridden via
environment variables or a `.env` file (see `.env.example` at the repo root for the
full documented list, including production values).
"""
from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # --- Environment ---
    ENV: str = "development"  # development | staging | production

    # --- Database ---
    # Local dev default: file-based sqlite via aiosqlite, zero setup required.
    # Production: postgres+asyncpg://<user>:<password>@<host>:5433/<db>
    #   (docker-compose.yml maps postgres:16-alpine to host port 5433)
    DATABASE_URL: str = "sqlite+aiosqlite:///./dev.db"

    # --- Redis (OPTIONAL) ---
    # The app MUST boot and operate correctly even if this host is unreachable —
    # every Redis call site falls back to an in-memory implementation.
    # docker-compose.yml maps redis:7-alpine to host port 6380.
    REDIS_URL: str | None = "redis://localhost:6380/0"

    # --- JWT / auth ---
    JWT_SECRET: str = "dev-only-insecure-secret-change-me"
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    REFRESH_TOKEN_EXPIRE_DAYS: int = 14

    # --- CORS ---
    CORS_ORIGINS: list[str] = [
        "http://localhost:5174",
        "http://127.0.0.1:5174",
        # ../.claude/launch.json's "cab-dispatch-dashboard" config runs on 5180 — that combination
        # never actually worked until this was added (discovered testing the dispatch page live).
        "http://localhost:5180",
        "http://127.0.0.1:5180",
    ]

    # --- Stripe ---
    STRIPE_SECRET_KEY: str = "sk_test_placeholder"

    # --- Fatigue monitoring (blueprint 12.3) ---
    # Deployment-wide default/override for the shift-duration fatigue-alert
    # threshold. DEVIATION: the task calls for this to be "configurable via a
    # tenant setting" — this codebase has no persisted per-tenant settings
    # table anywhere (Tenant only carries theme_json/plan/stripe_acct_id;
    # adding one is out of scope for this pass). This env-overridable,
    # deployment-wide value approximates it with a sane default. See
    # `app.services.fatigue.shift_duration_limit_hours` for the exact
    # deviation note and the seam left for a real per-tenant override later.
    FATIGUE_SHIFT_DURATION_LIMIT_HOURS: float = 12.0

    # --- Compliance-expiry tracking (blueprint 7.2.3/7.2.4/10.1) ---
    # Deployment-wide default/override for the "expiring soon" alert
    # threshold, in days, used by app.services.compliance_expiry for driver
    # license/authority and vehicle registration/insurance expiry checks.
    # DEVIATION (flagged per task instructions): the blueprint calls for
    # staged reminders at 30/60/90 days out — this pass raises a single
    # expiring_soon alert kind at one threshold rather than three separate
    # kinds (same single-threshold-simplification precedent as
    # FATIGUE_SHIFT_DURATION_LIMIT_HOURS / app.services.fatigue's flat speed
    # limit). See app.services.compliance_expiry's module docstring for the
    # full deviation note.
    COMPLIANCE_EXPIRY_WARNING_DAYS: int = 30

    # --- CabCharge ---
    # Authorization -> Docket creation -> Settlement batch (blueprint 5.2.5).
    CABCHARGE_API_KEY: str = "cabcharge_test_placeholder"
    CABCHARGE_BASE_URL: str = "https://api.cabcharge.com.au/v2"
    CABCHARGE_MERCHANT_ID: str = "merchant_test_placeholder"

    # --- TTSS (Taxi Transport Subsidy Scheme) ---
    # Eligibility check -> Subsidy calculation -> Claim submission ->
    # Reimbursement (blueprint 5.2.5/11.1).
    TTSS_API_KEY: str = "ttss_test_placeholder"
    TTSS_BASE_URL: str = "https://api.ttss.transport.nsw.gov.au/v1"
    TTSS_PROVIDER_ID: str = "provider_test_placeholder"

    # --- SendGrid (receipt email delivery, blueprint 5.2.6/8.5) ---
    # Same mock-fallback contract as STRIPE_SECRET_KEY above: empty (the
    # default) means "not configured" and app.services.receipts falls back to
    # a clearly-flagged mock response instead of calling out to SendGrid.
    SENDGRID_API_KEY: str = ""
    SENDGRID_FROM_EMAIL: str = "receipts@cabdispatch.example"

    # --- Twilio (receipt SMS delivery, blueprint 5.2.6/8.5) ---
    # All three must be set for app.services.receipts to treat Twilio as
    # configured; any one missing falls back to a mock response.
    TWILIO_ACCOUNT_SID: str = ""
    TWILIO_AUTH_TOKEN: str = ""
    TWILIO_FROM_NUMBER: str = ""

    # --- Duress escalation automated call (blueprint 8.3: "Automated phone
    # call to primary contact (Twilio)") ---
    # DEVIATION (same rationale as FATIGUE_SHIFT_DURATION_LIMIT_HOURS above):
    # there is no persisted per-tenant "primary emergency contact" table in
    # this codebase yet (Tenant only carries theme_json/plan/stripe_acct_id).
    # This env-overridable, deployment-wide default phone number is dialed
    # automatically when a duress event's escalation cascade reaches its
    # final stage (`present_000_call_script` — see
    # `app.services.duress.escalate_event`), UNLESS the caller supplies an
    # explicit per-call override via `POST /v1/duress/{id}/escalate`'s
    # `emergency_contact_phone` field. Empty (the default) means "no contact
    # configured" — the automated call step is then skipped (recorded in
    # `escalation_log_json` with `"skipped": true`) rather than dialing a
    # bogus number. Reuses the same TWILIO_ACCOUNT_SID/TWILIO_AUTH_TOKEN/
    # TWILIO_FROM_NUMBER credentials as the receipt-SMS mock-fallback above.
    DURESS_ESCALATION_CALL_PHONE: str = ""

    # --- Tariff signing (Ed25519 anti-tamper signature over GET /v1/tariffs/active,
    # blueprint B6) ---
    # This is the backend counterpart to the Android app's
    # `TariffSignatureVerifier` (android/.../security/TariffSignatureVerifier.kt) —
    # closing the loop that file's own TODO flags ("reconcile with
    # backend/tariff-signing agent"). NOTE: that Kotlin class currently only
    # implements RSA (SHA256withRSA) verification, not Ed25519 — its own doc
    # comment explains why (minSdk 29 lacks java.security Ed25519 support
    # until API 33). The Android side needs a new Ed25519 verifier (e.g. via
    # BouncyCastle, or java.security once minSdk rises) to consume what this
    # backend now signs; see app/services/tariff_signing.py's module doc for
    # the exact wire format it will need to match.
    #
    # PKCS8 DER, base64-encoded (`Ed25519PrivateKey.private_bytes` with
    # `PrivateFormat.PKCS8` / `NoEncryption`).
    #
    # *** PLACEHOLDER KEY — generate a real one for production, do NOT use
    # this default outside dev. *** Same convention as STRIPE_SECRET_KEY
    # above. Generate a real keypair with:
    #   uv run python -c "
    #   import base64
    #   from cryptography.hazmat.primitives.asymmetric import ed25519
    #   from cryptography.hazmat.primitives import serialization
    #   k = ed25519.Ed25519PrivateKey.generate()
    #   print(base64.b64encode(k.private_bytes(
    #       serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
    #       serialization.NoEncryption())).decode())"
    TARIFF_SIGNING_PRIVATE_KEY: str = (
        "MC4CAQAwBQYDK2VwBCIEIFkwXAY2lpqyo1i90XuRlHd4cBPZBOZBe+m3wPrbdLLL"
    )

    @property
    def is_production(self) -> bool:
        return self.ENV == "production"


settings = Settings()
