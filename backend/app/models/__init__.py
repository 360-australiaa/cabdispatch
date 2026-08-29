"""Domain model registry.

Every model is imported here so that `Base.metadata` (used by Alembic and by
the test-suite's `create_all`) is aware of it. This is the integration step
that wires all domains' models (plus the tenant/user foundation) together.

`FatigueAlert` (blueprint 12.3, MDM-lite/fatigue-monitoring pass) was added
on top of this already-integrated tree, not built in isolation -- it imports
`app.models.shift.Shift` directly (no defensive try/except import) since
every sibling domain's models are already guaranteed to be on `Base.metadata`
by the time this module runs.
"""
from app.core.database import Base
from app.models.audit_log import AuditLog
from app.models.billing import Subscription
from app.models.compliance import ComplianceDocument
from app.models.device_assignment import DeviceAssignment
from app.models.duress import DuressEvent
from app.models.duress_device import DuressDevice
from app.models.duress_snapshot import DuressSnapshot
from app.models.fatigue_alert import FatigueAlert
from app.models.fleet import Device, DevicePairingCode, DeviceVersionHistory, Vehicle
from app.models.geofence import Geofence
from app.models.jobs import DriverAvailability, Job, JobOffer
from app.models.messages import Message
from app.models.mfa_recovery_code import MfaRecoveryCode
from app.models.payment import Payment
from app.models.psl_ledger import PSLLedgerEntry, PSLTopUp
from app.models.shift import Shift
from app.models.shift_handover import ShiftHandover
from app.models.tariffs import Extra, Tariff, TariffChangeLog
from app.models.tenant import Tenant
from app.models.tenant_settings import TenantSettings
from app.models.trips import Trip
from app.models.user import User
from app.models.user_invite import UserInvite
from app.models.vehicle_assignment import VehicleAssignment
from app.models.zones import Zone

# live_ops owns no table of its own (see app/services/live_ops.py) -- nothing
# to import here for that domain.

__all__ = [
    "AuditLog",
    "Base",
    "ComplianceDocument",
    "Device",
    "DeviceAssignment",
    "DevicePairingCode",
    "DeviceVersionHistory",
    "DriverAvailability",
    "DuressDevice",
    "DuressEvent",
    "DuressSnapshot",
    "Extra",
    "FatigueAlert",
    "Geofence",
    "Job",
    "JobOffer",
    "Message",
    "MfaRecoveryCode",
    "PSLLedgerEntry",
    "PSLTopUp",
    "Payment",
    "Shift",
    "ShiftHandover",
    "Subscription",
    "Tariff",
    "TariffChangeLog",
    "Tenant",
    "TenantSettings",
    "Trip",
    "User",
    "UserInvite",
    "Vehicle",
    "VehicleAssignment",
    "Zone",
]