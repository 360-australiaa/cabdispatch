"""DuressSnapshot model -- one row per captured camera still-frame during an
active duress incident.

Deliberately a still-frame gallery, NOT continuous video: the tablet's front
(cabin-facing) camera captures a JPEG every few seconds ONLY while a
DuressEvent is open (see docs/DURESS_DEVICE_INTEGRATION.md's governing
principle -- camera/mic capability is event-scoped only, never a standby
"watch the cab" mode). This mirrors the same tradeoff already made for GPS
(a live relay, not a stored video stream) and audio (one file per event, not
a continuous recording pushed frame-by-frame) -- a still-frame gallery is
"live enough" for incident review while needing zero new streaming
infrastructure (no WebRTC/TURN), reusing the exact same local-disk-upload
convention already proven for duress audio.

Kept as its OWN table (not a single snapshot_ref column on DuressEvent,
unlike audio_ref) because an incident is expected to accumulate MANY frames
over its lifetime -- the Duress Desk needs to browse/scrub the sequence, not
just see the latest one.

DEVIATION (same precedent as DuressEvent/DuressDevice's own module
docstrings): event_id is a plain indexed String(36) with no ForeignKey to
duress_events.id, for the same "this domain must not assume
app/models/__init__.py's import order" reason documented there.
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin


class DuressSnapshot(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "duress_snapshots"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    event_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)

    # BACKEND_ROOT-relative on-disk path (see
    # app.services.duress.save_duress_snapshot) -- never absolute, so it stays
    # portable across machines/deployments, same convention as
    # DuressEvent.audio_ref.
    relative_path: Mapped[str] = mapped_column(String(255), nullable=False)

    # Wall-clock time the tablet captured the frame (device-reported, falls
    # back to server receipt time if the device didn't send one) -- distinct
    # from TimestampMixin's created_at, which is when the ROW was written and
    # would drift under any upload retry/latency.
    captured_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)