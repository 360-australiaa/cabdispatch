"""Pydantic v2 schemas for VehicleAssignment (plan WP-24 -- driver roster:
which drivers are authorised to drive which vehicles). See
app.models.vehicle_assignment for the full design rationale.
"""
from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict


class VehicleAssignmentCreate(BaseModel):
    """Client-supplied fields only. `authorised_by_user_id` (the caller) and
    `authorised_at` (the server clock) are set server-side at creation time,
    never accepted from the client -- same convention as
    app.models.fleet.DevicePairingCode.used_at being service-set."""

    vehicle_id: str
    driver_id: str


class RosterAuthoriseRequest(BaseModel):
    """Body for POST /v1/fleet/vehicles/{vehicle_id}/roster (WP-24 endpoint
    half). vehicle_id comes from the path, not the body -- only driver_id
    (required) and an optional freeform reason are client-supplied here.
    There is no authorised_reason column on the model (the plan only asked
    for a reason on revoke) -- this field is accepted for API-shape symmetry
    with the revoke side but is currently ignored by the service, not
    persisted anywhere. See fleet_service.authorise_driver."""

    driver_id: str
    reason: str | None = None


class RosterRevokeRequest(BaseModel):
    """Optional body for DELETE /v1/fleet/vehicles/{vehicle_id}/roster/{driver_id}."""

    reason: str | None = None


class VehicleAssignmentRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    vehicle_id: str
    driver_id: str
    authorised_by_user_id: str
    authorised_at: datetime
    revoked_at: datetime | None
    revoked_reason: str | None
    created_at: datetime
    updated_at: datetime