"""AuditLog model — the platform-wide, append-only tamper-evidence trail.

One row per recorded mutation anywhere in the system: who (`actor_user_id`)
did what (`action`) to which record (`entity_type` + `entity_id`), with an
optional before/after snapshot, at what time (`at`).

APPEND-ONLY BY DESIGN: there is deliberately no update/delete path anywhere
for this table (see app/api/v1/audit_log.py and app/services/audit_log.py) —
an audit trail that can be silently edited or removed after the fact is not
tamper evidence, it's just another mutable table. Every row, once written,
is permanent.

DEVIATION (flagged): `actor_user_id` is nullable, which the domain brief's
column list didn't explicitly call out either way. This mirrors the existing
`User.tenant_id` nullable precedent (see app/models/user.py) — some audited
actions are system/automated (a scheduled job, a webhook handler, a fare
recalculation with no human in the loop) and have no real user to attribute.
FK is kept (not denormalized away) so a real actor can always be resolved
back to a `User` row when one exists.

Also deviates from the standard `TimestampMixin` convention used elsewhere in
this codebase: this table has no `updated_at` (rows are never updated) and
uses the domain-specified `at` column as its single, purpose-built
timestamp — the exact same pattern already established by the sibling
`app.models.tariffs.TariffChangeLog` append-only audit table.

TAMPER-EVIDENCE HASH CHAIN (`hash` / `previous_hash`): append-only-by-convention
(no update/delete route) proves nothing on its own — a row edited directly at
the DB layer would be invisible to anyone reading through the API. `hash` is
the SHA-256 hex digest of a canonical serialization of this row's own fields
PLUS `previous_hash`; `previous_hash` is the `hash` of the immediately
preceding row for the SAME tenant (the chain is per-tenant, not global), or a
fixed 64-zero genesis value for a tenant's first row. Both are computed once,
at write time, in `app.services.audit_log.record_audit` — never recomputed or
edited afterwards. Any in-place edit to a row (this column set included)
changes what its `hash` recomputes to, which breaks the `previous_hash` link
the next row recorded — `GET /v1/audit-log/verify` walks the chain and
recomputes every row's hash to detect exactly that. See
`app.services.audit_log.compute_audit_hash` / `verify_chain`.
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import JSON, DateTime, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin


class AuditLog(Base, TenantScopedMixin):
    """Append-only — see module docstring. No update/delete anywhere in this
    domain's service or API layer, on purpose."""

    __tablename__ = "audit_log"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    # Nullable: system/automated actions have no human actor. See module docstring.
    actor_user_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("users.id"), nullable=True, index=True
    )

    # Free-text-ish verb, e.g. "create" / "update" / "delete" / a more specific
    # domain action like "escalate" / "remit" — deliberately not an enum so
    # every domain can log its own vocabulary without this table needing changes.
    action: Mapped[str] = mapped_column(String(100), nullable=False, index=True)

    # Polymorphic reference to the audited row — e.g. entity_type="trip",
    # entity_id=<trips.id>. No FK (the target table varies per row), so
    # referential integrity for entity_id is NOT enforced at the DB layer;
    # correctness relies on callers (record_audit callers) passing real ids.
    entity_type: Mapped[str] = mapped_column(String(100), nullable=False, index=True)
    entity_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)

    before_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    after_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)

    # The business timestamp of the audited event. server_default is a DB-level
    # safety net; app.services.audit_log.record_audit always sets this
    # explicitly in Python so the value is available immediately on the
    # returned object without needing a round-trip refresh.
    at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False, index=True
    )

    # Hash chain — see module docstring. server_default is the same
    # belt-and-suspenders as `at` above (never relied on in practice):
    # app.services.audit_log.record_audit always sets both explicitly.
    hash: Mapped[str] = mapped_column(String(64), nullable=False, server_default="0" * 64)
    previous_hash: Mapped[str] = mapped_column(String(64), nullable=False, server_default="0" * 64)
