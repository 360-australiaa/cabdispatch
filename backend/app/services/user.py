"""Business logic for the users domain (staff + driver onboarding/CRUD)."""
from __future__ import annotations

import os
import secrets
import string
import uuid
from pathlib import Path

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import hash_password
from app.models.audit_log import AuditLog
from app.models.compliance import ComplianceDocument
from app.models.driver_engagement import TripRating, WalletTransaction
from app.models.psl_ledger import PSLLedgerEntry, PSLTopUp
from app.models.tariffs import TariffChangeLog
from app.models.user import User

# Backend root: app/services/user.py -> parents[0]=services, [1]=app,
# [2]=backend project root. Uploads live at "<backend root>/uploads/...".
# Mirrors app.services.compliance's BACKEND_ROOT/UPLOADS_ROOT exactly
# (re-derived here rather than imported, matching this codebase's existing
# per-domain-duplication convention for small storage/alphabet constants —
# see e.g. app.services.fleet._PAIRING_CODE_ALPHABET vs this module's own
# _DRIVER_CODE_ALPHABET below).
BACKEND_ROOT = Path(__file__).resolve().parents[2]
UPLOADS_ROOT = BACKEND_ROOT / "uploads"

# Driver-code alphabet excludes visually-ambiguous characters (0/O, 1/I) —
# same reasoning as app/services/fleet.py's _PAIRING_CODE_ALPHABET, since a
# driver keys this in by hand on a meter/kiosk (see POST /v1/auth/driver-login
# in app/api/v1/auth.py).
_DRIVER_CODE_ALPHABET = "".join(c for c in string.ascii_uppercase + string.digits if c not in "01OI")
DRIVER_CODE_LENGTH = 5
_DRIVER_CODE_GENERATION_ATTEMPTS = 20


class UserError(Exception):
    pass


class UserNotFoundError(UserError):
    pass


class DuplicateEmailError(UserError):
    pass


class DuplicateDriverCodeError(UserError):
    pass


class DriverCodeGenerationError(UserError):
    """Raised if a unique driver_code couldn't be found after several random
    attempts — practically unreachable at this alphabet/length (30^5 ≈ 24M
    combinations) short of a near-exhausted codespace."""


class InvalidPhotoUploadError(UserError):
    pass


class UserHasDependentRecordsError(UserError):
    """Raised by `assert_user_deletable` when the user is still referenced by
    a NOT-NULL foreign key this codebase treats as audit/financial evidence
    (see that function's docstring for the full "cascade vs refuse" design
    rationale). `str(exc)` is already a complete, human-readable sentence
    safe to surface verbatim as an HTTP `detail` — see
    `app/api/v1/users.py::_user_error_to_http`."""


async def assert_email_available(
    session: AsyncSession, *, email: str, exclude_user_id: str | None = None
) -> None:
    """Email is globally unique across the whole platform (User.email has a
    unique constraint spanning all tenants), so this check is deliberately NOT
    tenant-scoped."""
    stmt = select(func.count()).select_from(User).where(User.email == email)
    if exclude_user_id is not None:
        stmt = stmt.where(User.id != exclude_user_id)
    count = (await session.execute(stmt)).scalar_one()
    if count > 0:
        raise DuplicateEmailError(email)


async def get_user_or_404(session: AsyncSession, *, tenant_id: str, user_id: str) -> User:
    result = await session.execute(
        select(User).where(User.id == user_id, User.tenant_id == tenant_id)
    )
    user = result.scalar_one_or_none()
    if user is None:
        raise UserNotFoundError(user_id)
    return user


async def assert_user_deletable(session: AsyncSession, *, user_id: str) -> None:
    """Raises `UserHasDependentRecordsError` if `user_id` is still referenced
    by a row this codebase treats as audit/financial EVIDENCE, rather than
    letting the delete reach the database and blow up as a raw, opaque
    IntegrityError.

    DESIGN (same real-production-bug root cause as the fleet vehicle/device
    delete fix — see app.services.fleet's module docstring, and
    app.models.fleet's ondelete= comments for the mirror-image "cascade
    derived data" half of this same design pass): postgres enforces every
    NOT NULL foreign key to `users.id`; sqlite silently didn't until this
    same pass turned on `PRAGMA foreign_keys=ON` for it (see
    app.core.database). Every table below carries a real, currently-enforced
    NOT NULL FK to `users.id` (audited directly from app/models/*.py — see
    each entry's own comment), so deleting a user referenced by any of them
    ALREADY fails at the database layer today; this function only turns that
    into a clean, specific, actionable HTTP error instead of an opaque
    500/503 the caller can't act on.

    Every one of these is refused (not cascaded/nulled) ON PURPOSE: unlike
    the fleet domain's derived/ephemeral heartbeat telemetry, each of these
    rows is evidence THAT SOMETHING HAPPENED — a document was uploaded, a
    levy was charged or collected, a wallet was credited/debited, a
    passenger rated a trip, a fare was changed — independent of whether the
    referenced person is later deleted. Silently destroying or anonymizing
    that evidence just to let an admin's delete-row click succeed is exactly
    the wrong trade for a legally fare-regulated taxi system; refusing with a
    clear reason and requiring an explicit decision (reassign the records,
    or don't delete the account) is the safer default. Contrast
    `WalletTransaction.created_by_user_id`, which IS nullable and DOES
    cascade to NULL on delete (see that column's own comment) — it names the
    staff member who *posted* a line, not the line's actual subject, so
    losing that attribution on delete isn't destroying evidence of the
    financial event itself.

    NOT checked here because there is no real FK to police (see each
    model's own DEVIATION note — `Trip.driver_id` / `Shift.driver_id` are
    plain unconstrained `String` columns, a pre-existing, separately-flagged
    gap, not something this pass introduces or can enforce retroactively):
    a deleted driver's historical trips/shifts keep their driver_id as-is,
    now pointing at a since-deleted user — unaffected by this function
    either way.
    """
    blockers: list[str] = []

    async def _count(model, column) -> int:
        stmt = select(func.count()).select_from(model).where(column == user_id)
        return (await session.execute(stmt)).scalar_one()

    # Compliance vault: NOT NULL FK, who uploaded a vehicle's compliance
    # document (app/models/compliance.py::ComplianceDocument.uploaded_by).
    n = await _count(ComplianceDocument, ComplianceDocument.uploaded_by)
    if n:
        blockers.append(f"{n} compliance document(s) uploaded by this user")

    # PSL (Passenger Service Levy) ledger + top-ups: NOT NULL FK, the driver
    # the levy accrual/collection is FOR (app/models/psl_ledger.py).
    n = await _count(PSLLedgerEntry, PSLLedgerEntry.driver_id)
    if n:
        blockers.append(f"{n} PSL ledger entr{'y' if n == 1 else 'ies'} for this driver")
    n = await _count(PSLTopUp, PSLTopUp.driver_id)
    if n:
        blockers.append(f"{n} PSL top-up payment(s) recorded for this driver")

    # Driver-engagement: NOT NULL FKs that name the record's actual subject
    # (app/models/driver_engagement.py) — driver_id on both, not the
    # nullable created_by_user_id on WalletTransaction (see that column's
    # own comment: it cascades to NULL, it does not block).
    n = await _count(WalletTransaction, WalletTransaction.driver_id)
    if n:
        blockers.append(f"{n} wallet transaction(s) for this driver")
    n = await _count(TripRating, TripRating.driver_id)
    if n:
        blockers.append(f"{n} trip rating(s) for this driver")

    # Tariff change log: NOT NULL FK, who made a fare-rate change
    # (app/models/tariffs.py::TariffChangeLog.actor_user_id) — this is
    # fare-regulation evidence (which staff member changed which rate, and
    # when), so it blocks even for a staff/admin account, not just drivers.
    n = await _count(TariffChangeLog, TariffChangeLog.actor_user_id)
    if n:
        blockers.append(f"{n} tariff change log entr{'y' if n == 1 else 'ies'} recorded by this user")

    # Platform-wide tamper-evidence audit trail
    # (app/models/audit_log.py::AuditLog.actor_user_id). This column IS
    # nullable at the schema level (some actions are system/automated, see
    # that model's own DEVIATION note) — but it is deliberately NOT set to
    # ondelete="SET NULL" here, unlike the genuinely-analogous-looking
    # WalletTransaction.created_by_user_id above: AuditLog rows are
    # cryptographically hash-chained (`hash`/`previous_hash`, see that
    # model's own docstring) over their full column set INCLUDING
    # actor_user_id — an ON DELETE SET NULL cascade would silently mutate a
    # row's content post-write, which is exactly the tamper `GET
    # /v1/audit-log/verify` exists to detect. So this one blocks too.
    n = await _count(AuditLog, AuditLog.actor_user_id)
    if n:
        blockers.append(f"{n} audit log entr{'y' if n == 1 else 'ies'} recorded by this user as actor")

    if blockers:
        raise UserHasDependentRecordsError(
            "Cannot delete this user: " + "; ".join(blockers) + ". "
            "Reassign or remove those records first."
        )


async def assert_driver_code_available(
    session: AsyncSession, *, tenant_id: str, driver_code: str, exclude_user_id: str | None = None
) -> None:
    """driver_code is unique PER TENANT (`uq_users_tenant_driver_code` —
    see app/models/user.py::User), not platform-wide (X2, 2026-09-08 — fixes
    a real tenancy bug: two operators could not both have a driver "101").
    `POST /v1/auth/driver-login` already resolves the driver within a tenant
    (`tenant_slug` + `driver_code` — see app/api/v1/auth.py::driver_login),
    so scoping this check to `tenant_id` matches what the DB constraint and
    the login lookup both actually enforce."""
    stmt = select(func.count()).select_from(User).where(
        User.tenant_id == tenant_id, User.driver_code == driver_code
    )
    if exclude_user_id is not None:
        stmt = stmt.where(User.id != exclude_user_id)
    count = (await session.execute(stmt)).scalar_one()
    if count > 0:
        raise DuplicateDriverCodeError(driver_code)


# Meter-PIN alphabet/length -- digits only (keyed in by hand on a
# meter/kiosk keypad, same rationale as _DRIVER_CODE_ALPHABET above), matching
# the "6-digit PIN" convention POST /v1/auth/driver-login's own docstring
# describes (app/api/v1/auth.py::driver_login). No ambiguous-character
# exclusion needed here (unlike the driver-code alphabet): digits alone have
# no visually-confusable pairs the way 0/O or 1/I do across digits+letters.
_DRIVER_PIN_ALPHABET = string.digits
DRIVER_PIN_LENGTH = 6


def generate_driver_pin() -> str:
    """Mints a random plaintext meter PIN. Unlike `generate_unique_driver_code`
    this is never checked for uniqueness against other drivers' PINs -- a PIN
    is a CREDENTIAL (like a password), not a lookup key, so two drivers
    sharing a PIN is no more a problem than two people sharing a password;
    only the driver_code + tenant pair needs to be unique, and PIN-login
    already requires both to match the same row (see
    app/api/v1/auth.py::driver_login). `secrets.choice` gives this
    unguessability without a uniqueness check."""
    return "".join(secrets.choice(_DRIVER_PIN_ALPHABET) for _ in range(DRIVER_PIN_LENGTH))


async def reset_driver_pin(user: User) -> str:
    """Generates a new plaintext meter PIN, hashes it into `user.pin_hash`
    with the SAME `hash_password` (bcrypt) call every account's
    password/PIN in this system is hashed with (see
    app/api/v1/users.py::create_user and app/core/security.hash_password --
    there is no separate "PIN format" anywhere in this codebase; a PIN IS a
    password, just entered on a driver-login screen instead of an
    email+password one), and returns the PLAINTEXT value.

    Does NOT commit and does NOT write to session -- `user` is a live ORM
    instance already attached to the caller's session; mutating its
    `pin_hash` attribute is enough for the caller's own `session.commit()`
    (alongside its `record_audit()` call, same caller-commits convention
    `app.services.audit_log.record_audit` documents) to persist it. The
    returned plaintext PIN is never stored or logged anywhere by this
    function -- it is the caller's job to hand it back to the requester
    exactly once and then let it go out of scope."""
    new_pin = generate_driver_pin()
    user.pin_hash = hash_password(new_pin)
    return new_pin


async def generate_unique_driver_code(session: AsyncSession, *, tenant_id: str) -> str:
    """Mints a random driver_code and confirms it's unused WITHIN this tenant
    (see assert_driver_code_available's doc for why this is tenant-scoped, not
    global). Used by POST /v1/users when creating a role="driver" user without
    an explicit driver_code (see app/api/v1/users.py)."""
    for _ in range(_DRIVER_CODE_GENERATION_ATTEMPTS):
        code = "".join(secrets.choice(_DRIVER_CODE_ALPHABET) for _ in range(DRIVER_CODE_LENGTH))
        count = (
            await session.execute(
                select(func.count())
                .select_from(User)
                .where(User.tenant_id == tenant_id, User.driver_code == code)
            )
        ).scalar_one()
        if count == 0:
            return code
    raise DriverCodeGenerationError("Could not generate a unique driver_code")


# --- photo storage (mirrors app.services.compliance's local-disk convention,
# see that module for the reference implementation this was copied from) ----


def _safe_component(value: str, *, max_len: int = 80) -> str:
    """Strips anything that could act as a path separator/traversal token
    from a value about to become part of an on-disk path — identical to
    app.services.compliance._safe_component."""
    cleaned = "".join(c for c in value if c.isalnum() or c in "-_.")
    cleaned = cleaned.strip(".") or "unknown"
    return cleaned[:max_len]


def user_photo_dir(*, tenant_id: str | None, user_id: str) -> Path:
    # tenant_id is nullable on User (platform-tenant staff — see
    # app.models.user's module docstring); "platform" is a stable fallback
    # component for that case, never used for any real tenant_id value since
    # _safe_component would already have accepted the real one.
    return UPLOADS_ROOT / _safe_component(tenant_id or "platform") / "users" / _safe_component(user_id)


async def save_user_photo(
    *,
    tenant_id: str | None,
    user_id: str,
    original_filename: str,
    content: bytes,
) -> str:
    """Writes `content` under `uploads/{tenant_id}/users/{user_id}/`, creating
    the directory tree if missing, and returns the *relative* (to
    `BACKEND_ROOT`) path to persist on `User.photo_url` — never the absolute
    path, so this stays portable across machines/deployments, same as
    app.services.compliance.save_upload.
    """
    if not content:
        raise InvalidPhotoUploadError("Uploaded file is empty")

    target_dir = user_photo_dir(tenant_id=tenant_id, user_id=user_id)
    target_dir.mkdir(parents=True, exist_ok=True)

    safe_name = _safe_component(os.path.basename(original_filename), max_len=200) or "upload"
    stored_name = f"{uuid.uuid4().hex}_{safe_name}"
    absolute_path = target_dir / stored_name
    absolute_path.write_bytes(content)

    return absolute_path.relative_to(BACKEND_ROOT).as_posix()


def resolve_photo_path(file_path: str) -> Path:
    """Resolves a stored (relative) `User.photo_url` back to an absolute path
    for streaming. Rejects anything that would escape `BACKEND_ROOT` — same
    guard as app.services.compliance.resolve_absolute_path."""
    absolute_path = (BACKEND_ROOT / file_path).resolve()
    if BACKEND_ROOT not in absolute_path.parents and absolute_path != BACKEND_ROOT:
        raise InvalidPhotoUploadError("Stored photo_url resolves outside the uploads root")
    return absolute_path


def delete_photo_best_effort(file_path: str) -> None:
    """Best-effort on-disk delete — a missing file must not block anything.
    Not currently wired to any endpoint (no photo-delete endpoint exists in
    this pass), kept for symmetry with app.services.compliance and for a
    future DELETE /v1/users/{id}/photo to use."""
    try:
        absolute_path = resolve_photo_path(file_path)
        absolute_path.unlink(missing_ok=True)
    except (InvalidPhotoUploadError, OSError):
        pass

