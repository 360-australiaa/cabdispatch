"""Pydantic v2 schemas for the Shifts domain."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field


class ShiftStart(BaseModel):
    """Body for `POST /v1/shifts/start` — opens a new shift."""

    driver_id: str
    vehicle_id: str
    start_at: datetime | None = Field(
        default=None, description="Defaults to server time (UTC now) if omitted."
    )
    inspection_json: dict | None = Field(
        default=None, description="Pre-shift vehicle inspection checklist, freeform."
    )
    odometer_start: int | None = Field(
        default=None,
        ge=0,
        description=(
            "Odometer reading at shift open, if captured by the client. Not "
            "required -- a shift opened via a handover instead gets this set "
            "automatically from the outgoing shift's odometer_end (see "
            "app.services.shift_handover.perform_handover)."
        ),
    )


class ShiftEnd(BaseModel):
    """Body for `POST /v1/shifts/{id}/end` — reconciliation figures accepted from
    the client. `trips_count`/`km_total`/`cash_total`/`card_total` are NOT accepted
    here — they are recomputed server-side from the shift's trips."""

    end_at: datetime | None = Field(
        default=None, description="Defaults to server time (UTC now) if omitted."
    )
    psl_owed: Decimal = Field(default=Decimal(0), ge=0)
    reconciled: bool = Field(
        default=True,
        description="Whether the driver's counted cash matches the recomputed cash_total.",
    )
    odometer_end: int | None = Field(
        default=None, ge=0, description="Odometer reading at shift close, if captured."
    )
    end_inspection_json: dict | None = Field(
        default=None, description="End-of-shift vehicle inspection checklist, freeform."
    )


class ShiftCreate(BaseModel):
    """Generic create — accepted for CRUD completeness/admin backfill, but the
    normal opening path for a shift is `POST /v1/shifts/start` above."""

    driver_id: str
    vehicle_id: str
    start_at: datetime
    end_at: datetime | None = None
    inspection_json: dict | None = None
    end_inspection_json: dict | None = None
    odometer_start: int | None = None
    odometer_end: int | None = None
    trips_count: int = 0
    km_total: Decimal = Decimal(0)
    cash_total: Decimal = Decimal(0)
    card_total: Decimal = Decimal(0)
    psl_owed: Decimal = Decimal(0)
    reconciled: bool = False


class ShiftUpdate(BaseModel):
    """Partial update — every field optional. Intended for admin corrections
    (e.g. fixing a mis-keyed inspection checklist or reconciliation figure)."""

    driver_id: str | None = None
    vehicle_id: str | None = None
    start_at: datetime | None = None
    end_at: datetime | None = None
    inspection_json: dict | None = None
    end_inspection_json: dict | None = None
    odometer_start: int | None = None
    odometer_end: int | None = None
    trips_count: int | None = None
    km_total: Decimal | None = None
    cash_total: Decimal | None = None
    card_total: Decimal | None = None
    psl_owed: Decimal | None = None
    reconciled: bool | None = None


class ShiftRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    driver_id: str
    vehicle_id: str
    start_at: datetime
    end_at: datetime | None
    inspection_json: dict | None
    end_inspection_json: dict | None = None
    odometer_start: int | None = None
    odometer_end: int | None = None
    trips_count: int
    km_total: Decimal
    cash_total: Decimal
    card_total: Decimal
    psl_owed: Decimal
    reconciled: bool
    # Zone-plotting fields (see app.models.shift.Shift's DEVIATION note) --
    # managed exclusively via POST /v1/zones/{id}/plot and
    # POST /v1/zones/unplot (app.services.zones), not settable via
    # ShiftCreate/ShiftUpdate above.
    plotted_zone_id: str | None = None
    plotted_at: datetime | None = None
    # Break-tracking fields (see app.models.shift.Shift DEVIATION note) --
    # managed exclusively via POST /v1/shifts/{id}/break/start and
    # POST /v1/shifts/{id}/break/end, not settable via ShiftCreate/ShiftUpdate.
    break_started_at: datetime | None = None
    break_taken: bool = False
    created_at: datetime
    updated_at: datetime


class ShiftListResponse(BaseModel):
    items: list[ShiftRead]
    total: int
    limit: int
    offset: int


class ShiftReport(BaseModel):
    """`GET /v1/shifts/{id}/report` payload. Also rendered to real PDF/CSV
    bytes by app.services.shift.render_report_pdf / render_report_csv, served
    by GET /v1/shifts/{id}/report.pdf and /report.csv.
    """

    shift_id: str
    tenant_id: str
    driver_id: str
    vehicle_id: str
    start_at: datetime
    end_at: datetime | None
    duration_minutes: float | None
    trips_count: int
    km_total: Decimal
    cash_total: Decimal
    card_total: Decimal
    total_takings: Decimal
    psl_owed: Decimal
    reconciled: bool
    inspection_json: dict | None
    generated_at: datetime
