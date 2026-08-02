"""Domain model registry.

Every model is imported here so that `Base.metadata` (used by Alembic and by
the test-suite's `create_all`) is aware of it. This is the integration step
that wires all domains' models (plus the tenant/user foundation) together.

`FatigueAlert` (blueprint 12.3, MDM-lite/fatigue-monitoring pass) was added
on top of this already-integrated tree, not built in isolation — it imports
`app.models.shift.Shift` directly (no defensive try/except import) since
every sibling domain's models are already guaranteed to be on `Base.metadata`
by the time this module runs.
"""
from app.core.database import Base
from app.models.audit_log import AuditLog
from app.models.billing import Subscription
from app.models.compliance import ComplianceDocument
from app.models.duress import DuressEvent
from app.models.fatigue_alert import FatigueAlert
from app.models.fleet import Device, DevicePairingCode, Vehicle
from app.models.geofence import Geofence
from app.models.jobs import DriverAvailability, Job, JobOffer
from app.models.messages import Message
from app.models.payment import Payment
from app.models.psl_ledger import PSLLedgerEntry, PSLTopUp
from app.models.shift import Shift
from app.models.tariffs import Extra, Tariff, TariffChangeLog
from app.models.tenant import Tenant
from app.models.trips import Trip
from app.models.user import User

# live_ops owns no table of its own (see app/services/live_ops.py) — nothing
# to import here for that domain.

__all__ = [
    "AuditLog",
    "Base",
    "ComplianceDocument",
    "Device",
    "DevicePairingCode",
    "DriverAvailability",
    "DuressEvent",
    "Extra",
    "FatigueAlert",
    "Geofence",
    "Job",
    "JobOffer",
    "Message",
    "PSLLedgerEntry",
    "PSLTopUp",
    "Payment",
    "Shift",
    "Subscription",
    "Tariff",
    "TariffChangeLog",
    "Tenant",
    "Trip",
    "User",
    "Vehicle",
]
