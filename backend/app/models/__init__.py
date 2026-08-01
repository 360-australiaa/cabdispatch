"""Domain model registry.

Every model is imported here so that `Base.metadata` (used by Alembic and by
the test-suite's `create_all`) is aware of it. This is the integration step
that wires all 14 domains' models (plus the tenant/user foundation) together.
"""
from app.core.database import Base
from app.models.audit_log import AuditLog
from app.models.billing import Subscription
from app.models.compliance import ComplianceDocument
from app.models.duress import DuressEvent
from app.models.fleet import Device, DevicePairingCode, Vehicle
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
