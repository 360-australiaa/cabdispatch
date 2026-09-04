"""Driver-engagement models -- the real backing tables for the four
driver-tablet dashboard tiles that previously had no data anywhere in the
system: Wallet Balance, Driver Rating, Announcements and Incentive Progress.

Design rule shared by all four (and enforced by app.services.driver_engagement):
**every driver-facing number is DERIVED, never stored.**

* A driver's wallet balance is `SUM(wallet_transactions.amount_aud)` over
  their rows -- there is no mutable "balance" column that could drift from
  the ledger.
* A driver's rating is `AVG(trip_ratings.stars)` + `COUNT(*)` over their
  rated trips -- no running average column.
* Incentive progress is a live `COUNT(*)` of the driver's *closed* trips
  whose `end_at` falls inside the incentive window, against `target_trips`.

Conventions match app/models/vouchers.py: String(36) uuid PKs,
TenantScopedMixin + TimestampMixin, Numeric(10, 2) for every money column
(never float), plain-string enums (sqlite/postgres portable). driver_id
columns carry a real ForeignKey("users.id") -- like Voucher.redeemed_by_trip_id
this domain is wired in against the fully-integrated tree, so there is no
table-creation-order risk (contrast app.models.trips.Trip.driver_id, which
predates integration and stays unconstrained).
"""
from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin

_MONEY = Numeric(10, 2)

# --- wallet transaction kinds (plain strings, sqlite/postgres portable) -----
# trip_earning is reserved for system-generated rows (a future "credit the
# driver on trip close" pass); operators may only post the other three via
# POST /v1/wallet/transactions -- see app.api.v1.wallet.OPERATOR_WALLET_KINDS.
WALLET_KIND_TRIP_EARNING = "trip_earning"
WALLET_KIND_TOP_UP = "top_up"
WALLET_KIND_ADJUSTMENT = "adjustment"
WALLET_KIND_PAYOUT = "payout"
WALLET_KINDS = (
    WALLET_KIND_TRIP_EARNING,
    WALLET_KIND_TOP_UP,
    WALLET_KIND_ADJUSTMENT,
    WALLET_KIND_PAYOUT,
)

# --- announcement kinds ------------------------------------------------------
ANNOUNCEMENT_KIND_INFO = "info"
ANNOUNCEMENT_KIND_MAINTENANCE = "maintenance"
ANNOUNCEMENT_KIND_SURGE = "surge"
ANNOUNCEMENT_KIND_FEATURE = "feature"
ANNOUNCEMENT_KINDS = (
    ANNOUNCEMENT_KIND_INFO,
    ANNOUNCEMENT_KIND_MAINTENANCE,
    ANNOUNCEMENT_KIND_SURGE,
    ANNOUNCEMENT_KIND_FEATURE,
)

RATING_MIN_STARS = 1
RATING_MAX_STARS = 5


class WalletTransaction(Base, TenantScopedMixin, TimestampMixin):
    """One signed ledger line on a driver's wallet. Positive = credit to the
    driver (earning, top-up), negative = debit (payout to the driver's bank,
    a negative adjustment). The balance is the SUM -- see module docstring."""

    __tablename__ = "wallet_transactions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    driver_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("users.id"), nullable=False, index=True
    )
    amount_aud: Mapped[Decimal] = mapped_column(_MONEY, nullable=False)
    kind: Mapped[str] = mapped_column(String(20), nullable=False)
    # Free-form pointer to whatever produced this line (a trip id for a
    # trip_earning, a bank reference for a payout, ...). Nullable.
    reference: Mapped[str | None] = mapped_column(String(100), nullable=True)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Who posted the line. Nullable so system-generated rows (and a future
    # trip-close credit) can leave it empty; operator-posted rows via
    # POST /v1/wallet/transactions always set it.
    created_by_user_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("users.id"), nullable=True
    )


class TripRating(Base, TenantScopedMixin, TimestampMixin):
    """The passenger's 1-5 star rating of the driver for one closed trip,
    captured on the driver tablet at the end of Close & Pay. One row per trip
    (unique trip_id); the driver's headline rating is the AVG over rows."""

    __tablename__ = "trip_ratings"
    __table_args__ = (
        UniqueConstraint("trip_id", name="uq_trip_ratings_trip_id"),
        CheckConstraint(
            f"stars >= {RATING_MIN_STARS} AND stars <= {RATING_MAX_STARS}",
            name="ck_trip_ratings_stars_range",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    trip_id: Mapped[str] = mapped_column(String(36), ForeignKey("trips.id"), nullable=False)
    driver_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("users.id"), nullable=False, index=True
    )
    stars: Mapped[int] = mapped_column(Integer, nullable=False)
    comment: Mapped[str | None] = mapped_column(Text, nullable=True)


class Announcement(Base, TenantScopedMixin, TimestampMixin):
    """Operator-authored notice shown on every driver tablet in the tenant
    while `active` AND `starts_at <= now < ends_at` (ends_at NULL = open
    ended). See app.services.driver_engagement.list_live_announcements."""

    __tablename__ = "announcements"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    body: Mapped[str] = mapped_column(Text, nullable=False)
    kind: Mapped[str] = mapped_column(String(20), nullable=False, default=ANNOUNCEMENT_KIND_INFO)
    starts_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    ends_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)


class Incentive(Base, TenantScopedMixin, TimestampMixin):
    """"Complete N trips between A and B, earn $X" campaign. Progress is
    never stored on this row -- see app.services.driver_engagement
    .count_completed_trips_in_window."""

    __tablename__ = "incentives"
    __table_args__ = (
        CheckConstraint("target_trips > 0", name="ck_incentives_target_trips_positive"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    target_trips: Mapped[int] = mapped_column(Integer, nullable=False)
    reward_aud: Mapped[Decimal] = mapped_column(_MONEY, nullable=False)
    starts_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    ends_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
