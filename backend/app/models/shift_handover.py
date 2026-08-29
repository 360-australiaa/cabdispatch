"""ShiftHandover model -- the direct mechanism answering "two drivers, 12
hours each, same vehicle, in one 24-hour period" (architecture plan D-2,
Part 5 Q3 walkthrough; work package WP-32).

A ShiftHandover row is written once, in the SAME transaction that closes the
outgoing Shift and opens the incoming Shift (see
`app.services.shift.perform_handover`) -- it's a record of the handover
event, not itself the mechanism that prevents double-booking. That guarantee
still comes entirely from D-1's two partial unique indexes on `Shift`
(`uq_shifts_one_open_per_vehicle` / `uq_shifts_one_open_per_driver`, see
`app.models.shift`); this table exists only because the handover event
itself -- who handed over to whom, when, and the vehicle-condition snapshot
at the changeover -- is data worth keeping that neither `Shift` row alone
records.

FK CHOICE (`outgoing_shift_id` / `incoming_shift_id`): `app.models.shift`'s
own module docstring documents a deliberate no-FK-across-domains convention
for `Shift.driver_id`/`Shift.vehicle_id`, because those reference *sibling*
domains (`users`, `vehicles`) that may not always be present together in a
given slice of the tree. That reasoning does NOT apply here -- `ShiftHandover`
and `Shift` are the SAME domain, defined in sibling modules of this same
package, always present or absent together. There is nothing preventing a
real `ForeignKey("shifts.id")` for both columns, so unlike `Shift`'s own
cross-domain refs, these ARE real foreign keys: it costs nothing here and
buys referential integrity (a handover can never point at a shift that
doesn't exist) plus lets `ondelete` behavior be reasoned about later if
needed. Both are additionally plain indexed columns (not unique) since
either shift could in principle appear in more than one dispute/audit
context, though in practice `perform_handover` only ever writes one row per
handover event.

`fuel_level` is stored as an `Integer` 0-100 PERCENTAGE (not a String enum
like "full"/"3/4"/"1/2") -- a plain numeric range is simpler to validate
(0 <= x <= 100, see `app.schemas.shift_handover.ShiftHandoverRequest`),
trivially sortable/aggregatable later (e.g. "average fuel level at
handover" reporting), and avoids inventing a five-value enum this domain
would then have to keep in sync between the model, the schema, and any
future UI. Nullable -- a dispatcher-performed handover (rather than a
driver-to-driver one) may not always have this captured.

STAGE 3 UPDATE (WP-33): `Shift.odometer_start` now exists (see
`app.models.shift`'s own DEVIATION note). `app.services.shift_handover.
perform_handover` sets `incoming_shift.odometer_start = odometer_end` from
this same handover row at the point the incoming shift is constructed, so
the incoming shift starts from the exact odometer reading the outgoing one
ended on. (This note originally flagged that wiring as still-to-do when
this module was first written in WP-32; it is done now.)
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin


class ShiftHandover(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "shift_handovers"

    id: Mapped[str] = mapped_column(
        String(36), primary_key=True, default=lambda: str(uuid.uuid4())
    )

    # --- linkage (real FKs -- see module docstring for why this differs from
    # Shift's own cross-domain-refs convention) -----------------------------
    outgoing_shift_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("shifts.id"), nullable=False, index=True
    )
    incoming_shift_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("shifts.id"), nullable=False, index=True
    )

    handed_over_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # Who performed the handover -- normally the outgoing driver themselves,
    # or a dispatcher/admin/owner standing in for them (same caller-eligibility
    # rule as app.services.shift.start_shift's gate (h), see
    # app.services.shift.perform_handover). A real FK to `users` -- User is a
    # shared-foundation table, same as Shift.tenant_id's own FK to `tenants`.
    handed_over_by_user_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("users.id"), nullable=False, index=True
    )

    # --- vehicle-condition snapshot at the changeover (all optional except
    # what genuinely must be captured -- none of these block a handover from
    # completing if omitted; see app.schemas.shift_handover) ---------------
    odometer_end: Mapped[int | None] = mapped_column(Integer, nullable=True)
    # 0-100 percentage -- see module docstring for why an Integer, not a
    # String enum like "full"/"3/4"/"1/2"/"1/4"/"empty".
    fuel_level: Mapped[int | None] = mapped_column(Integer, nullable=True)
    cleanliness_notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    damage_notes: Mapped[str | None] = mapped_column(Text, nullable=True)