"""Pydantic v2 schemas for the Shift Handover domain (WP-32, plan D-2)."""
from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class ShiftHandoverRequest(BaseModel):
    """Body for `POST /v1/shifts/{outgoing_shift_id}/handover`.

    `incoming_driver_id` + `incoming_driver_pin` re-authenticate the
    INCOMING driver (the person taking over the vehicle) -- this is
    deliberately a fresh credential check, not just an id, because a
    handover changes who is legally responsible for the vehicle from this
    moment on; the caller performing the handover (the outgoing driver, or
    a dispatcher/admin/owner -- see app.services.shift_handover.perform_handover)
    does not need to re-supply their own credentials since they are already
    authenticated via the request's bearer token, same as start_shift.

    Every vehicle-condition field is optional except what genuinely must be
    captured to identify who is taking over (`incoming_driver_id` +
    `incoming_driver_pin`) -- odometer/fuel/cleanliness/damage are a
    best-effort snapshot at the changeover, not a hard gate on completing
    the handover.
    """

    incoming_driver_id: str
    incoming_driver_pin: str = Field(
        description="The incoming driver's PIN (same credential as POST /v1/auth/driver-login), "
        "re-verified here as proof the incoming driver themselves is present at the handover."
    )
    odometer_end: int | None = Field(
        default=None, ge=0, description="Odometer reading at the changeover, if captured."
    )
    fuel_level: int | None = Field(
        default=None,
        ge=0,
        le=100,
        description="Fuel level at the changeover, as a 0-100 percentage.",
    )
    cleanliness_notes: str | None = Field(default=None)
    damage_notes: str | None = Field(default=None)
    handed_over_at: datetime | None = Field(
        default=None, description="Defaults to server time (UTC now) if omitted."
    )


class ShiftHandoverRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    outgoing_shift_id: str
    incoming_shift_id: str
    handed_over_at: datetime
    handed_over_by_user_id: str
    odometer_end: int | None
    fuel_level: int | None
    cleanliness_notes: str | None
    damage_notes: str | None
    created_at: datetime
    updated_at: datetime