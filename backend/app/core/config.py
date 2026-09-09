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

    # --- Lazy background work (workstream B6) ---------------------------------
    # This backend has NO scheduler, task queue, or background worker of any
    # kind, deliberately (see `app.services.live_ops`'s module docstring). Every
    # periodic behaviour is performed lazily on the next read or write that
    # happens to pass through the relevant code path. The three values below are
    # the timings that pattern needs; they are env-overridable and
    # DEPLOYMENT-WIDE, not per-tenant, for exactly the reason spelled out on
    # FATIGUE_SHIFT_DURATION_LIMIT_HOURS above (no persisted per-tenant settings
    # table exists in this codebase yet).

    # How long a `VehiclePositionHistory` row is kept before the next position
    # publish for ANY vehicle on the tenant prunes it (the prune is tenant-wide,
    # not per-vehicle -- see `app.services.live_ops.position_history_retention_hours`).
    #
    # OWNER DECISION OUTSTANDING: 72 hours (3 days) is a TECHNICAL DEFAULT, not
    # a decided data-retention policy. This table holds real driver location
    # data; the retention period that data is legally and contractually allowed
    # to have is a business-owner call that has not been made. This setting
    # exists so making that call is a config change, not a code change.
    POSITION_HISTORY_RETENTION_HOURS: int = 72

    # Wall-clock gap between two consecutive automatic duress-escalation stages.
    # The cascade's FIRST stage (`cancel_window_expired`) is instead gated on the
    # event's own recorded cancel deadline (app.models.duress.CANCEL_WINDOW_SECONDS,
    # 10s); this interval governs every stage after it. 60 seconds means an
    # unattended panic event reaches `present_000_call_script` roughly three
    # minutes after it was raised.
    #
    # OWNER DECISION OUTSTANDING: like the retention window above, this number is
    # a defensible default, not a policy anyone has signed off. It controls how
    # fast an unwatched duress event dials an emergency contact.
    DURESS_AUTO_ESCALATION_INTERVAL_SECONDS: int = 60

    # Master switch for the automatic cascade. When False, a duress event only
    # ever advances via an explicit `POST /v1/duress/{id}/escalate` -- the
    # pre-B6 behaviour, kept as an escape hatch for a deployment that genuinely
    # wants escalation to be a human decision every time.
    DURESS_AUTO_ESCALATION_ENABLED: bool = True

    # --- Live NSW traffic cameras/hazards (app.services.live_traffic) --------
    # Same "no scheduler in this codebase, refresh lazily off the next read"
    # pattern as the block above -- these are the two cadences that pattern
    # needs. Cameras rarely move (an hour-ish is plenty); hazards genuinely
    # churn minute to minute.
    LIVE_TRAFFIC_CAMERAS_REFRESH_MINUTES: int = 60
    LIVE_TRAFFIC_HAZARDS_REFRESH_MINUTES: int = 3

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

    # --- Dashboard base URL (D10: password-reset email link) ---
    # The reset link embedded in the password-reset email points here +
    # "/reset-password?token=...". Not itself a secret.
    DASHBOARD_BASE_URL: str = "http://localhost:5173"

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

    # --- Duress device (CT-DPD-01 hardware panic device) ---
    # Fernet key used by `app.core.crypto` to encrypt each DuressDevice's
    # shared secret at rest (see `app.models.duress_device.DuressDevice`).
    # Must be reversible (unlike password hashing) because the server needs
    # the plaintext secret back to verify the device's HMAC-signed auth
    # requests -- see `app.services.duress_device.authenticate_device`.
    #
    # *** PLACEHOLDER KEY -- generate a real one for production, do NOT use
    # this default outside dev. *** Same convention as TARIFF_SIGNING_PRIVATE_KEY
    # above. Generate a real key with:
    #   uv run python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
    SECRET_ENCRYPTION_KEY: str = "KemTJQFak_SLex3Px5lHbF5Qyx8inhoXWm2y0o09HK8="

    # Lifetime of the short-lived device JWT minted by POST /v1/devices/auth
    # (app.api.v1.duress_device) -- deliberately short since the device
    # re-authenticates on every cellular reconnect rather than holding a
    # long-lived credential in flash.
    DURESS_DEVICE_JWT_EXPIRE_MINUTES: int = 60

    # Deployment-wide default Twilio Caller ID used to dial a duress device's
    # own SIM MSISDN for the "call the cab" operator action (blueprint: duress
    # device integration contract, POST /v1/duress/{id}/call). Distinct from
    # DURESS_ESCALATION_CALL_PHONE above, which is the *destination* dialed
    # for the automated emergency-contact escalation call; this is the *from*
    # number Twilio uses when calling the device instead. Falls back to
    # TWILIO_FROM_NUMBER if unset.
    DURESS_CALL_FROM_NUMBER: str = ""

    @property
    def is_production(self) -> bool:
        return self.ENV == "production"


# The literal default from JWT_SECRET above. Kept as a named constant because
# callers and tests import it; the guard itself reads every default off the
# model via `_field_default` so there is exactly one definition of each.
DEFAULT_JWT_SECRET = "dev-only-insecure-secret-change-me"


def _field_default(name: str) -> str:
    """The committed source default for a Settings field.

    Read off the model rather than re-declaring the literal: TARIFF_SIGNING_
    PRIVATE_KEY and SECRET_ENCRYPTION_KEY are *functional* private keys that
    happen to live in this file, and copying either one into a second place
    would mean a rotation of the placeholder silently leaves a stale copy
    behind that the guard then compares against -- i.e. the guard would stop
    guarding. One definition, one comparison.
    """
    return str(Settings.model_fields[name].default)


class InsecureProductionConfigError(RuntimeError):
    """Raised at startup when ENV=production but a required secret is still
    at its insecure development default -- see `assert_production_secrets_safe`."""


# Every secret whose committed default is publicly readable in this file, and
# what a production deploy actually loses by keeping it. The guard iterates
# this so adding a fourth secret is a one-line change here, not a new branch.
#
#   (field name, human "generate a real one" hint, what the default costs you)
#
# JWT_SECRET was the only entry until a backend audit pointed out the obvious:
# the other two defaults are real, working keys, published in this repo, and
# nothing stopped production booting on them. The docstring on the old guard
# even conceded it was "deliberately narrow". Narrow is how you get a fleet
# signing its tariffs with a key an attacker can copy-paste.
_PRODUCTION_SECRET_CHECKS: tuple[tuple[str, str, str], ...] = (
    (
        "JWT_SECRET",
        "`openssl rand -hex 32`",
        ("every access and refresh token this server issues could be forged by "
        "anyone who can read this repository -- full authentication bypass"),
    ),
    (
        "TARIFF_SIGNING_PRIVATE_KEY",
        ('python -c "'
        "import base64;"
        "from cryptography.hazmat.primitives.asymmetric import ed25519;"
        "from cryptography.hazmat.primitives import serialization;"
        "k=ed25519.Ed25519PrivateKey.generate();"
        "print(base64.b64encode(k.private_bytes("
        "serialization.Encoding.DER,serialization.PrivateFormat.PKCS8,"
         'serialization.NoEncryption())).decode())"'),
        ("every tariff would be signed with a publicly-known Ed25519 key, so "
        "the tablet's TariffSignatureVerifier would happily accept a forged "
        "fare table -- the entire anti-tamper chain becomes decorative"),
    ),
    (
        "SECRET_ENCRYPTION_KEY",
        ('python -c "from cryptography.fernet import Fernet; '
         'print(Fernet.generate_key().decode())"'),
        ("every duress-device shared secret at rest would be encrypted with a "
        "publicly-known Fernet key, i.e. stored in effectively plaintext -- "
        "anyone with a copy of the database could impersonate a panic device"),
    ),
)


def assert_production_secrets_safe(settings_obj: Settings) -> None:
    """Startup guard: refuses to let the app boot in production while any
    secret is still at its committed, publicly-known development default.

    Covers all three of the secrets this file ships a working default for
    (`_PRODUCTION_SECRET_CHECKS`). This is not a general secret-scanning pass
    -- it is specifically "did the operator forget to override a key that is
    printed in our own source". Call it as early as possible (import time of
    `app.main`, before the FastAPI app is constructed) so a misconfigured
    production deployment fails loudly before serving any request, rather
    than silently running with a secret anyone can read out of this file.

    Raises on the FIRST offending secret rather than collecting all three:
    the operator has to go fix .env.production and redeploy either way, and
    a single unambiguous message is easier to act on than a list.
    """
    if not settings_obj.is_production:
        return

    for field_name, generate_hint, consequence in _PRODUCTION_SECRET_CHECKS:
        if getattr(settings_obj, field_name) != _field_default(field_name):
            continue
        raise InsecureProductionConfigError(
            f"Refusing to start: ENV=production but {field_name} is still the "
            f"insecure development default committed to this repository. "
            f"Consequence if this booted: {consequence}. "
            f"Set the {field_name} environment variable to a real, random "
            f"value before starting the app in production "
            f"(generate one with: {generate_hint})."
        )


settings = Settings()
assert_production_secrets_safe(settings)
