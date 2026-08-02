"""Business logic for real PDF receipt generation + email/SMS delivery
(blueprint 5.2.6/8.5).

`Trip.receipt_ref` (see `app/models/trips.py`) has existed since the trips
domain landed, but nothing ever rendered a real document from it — every
receipt was JSON-only. This module fixes that:

- `ensure_receipt_pdf` renders a real PDF (via the pure-Python `fpdf2`
  library — no system dependencies) from a *closed* trip's fare breakdown
  and writes it to local disk under
  `uploads/{tenant_id}/receipts/receipt_{trip_id}.pdf`, mirroring the
  sibling `app.services.compliance` module's exact local-disk-upload
  convention (BACKEND_ROOT-relative paths, `_safe_component` path-traversal
  insurance) rather than inventing a new storage scheme. The filename is
  deterministic per trip, so a second call for the same trip is a cheap
  no-op instead of re-rendering — no new DB column/migration was needed to
  track "already generated" state.
- `send_receipt_email` / `send_receipt_sms` follow the EXACT mock-fallback
  pattern already used for every external-API integration in this codebase
  (see `app.services.payments._stripe_configured` /
  `_cabcharge_configured` / `_ttss_configured`): check `Settings` for a real
  credential, make a real `httpx` call if configured, and return a
  clearly-flagged `{"mock": True, ...}` response otherwise — so both work
  with zero real credentials configured, which is all this dev environment
  ever has.
"""
from __future__ import annotations

import base64
import logging
from pathlib import Path

import httpx
from fpdf import FPDF
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.models.fleet import Vehicle
from app.models.trips import TRIP_STATUS_CLOSED, Trip
from app.models.user import User

logger = logging.getLogger("cab_dispatch.receipts")

# Backend root: app/services/receipts.py -> parents[0]=services, [1]=app,
# [2]=backend project root. Same BACKEND_ROOT/UPLOADS_ROOT convention as
# app.services.compliance — uploads live at "<backend root>/uploads/...".
BACKEND_ROOT = Path(__file__).resolve().parents[2]
UPLOADS_ROOT = BACKEND_ROOT / "uploads"


class ReceiptError(Exception):
    """Base class for receipts-domain errors; the router translates each
    subclass to the appropriate HTTP status."""


class TripNotClosedError(ReceiptError):
    """Raised when a receipt is requested for a trip that isn't closed yet —
    the fare breakdown isn't final until `POST /v1/trips/{id}/close` runs."""


def _safe_component(value: str, *, max_len: int = 80) -> str:
    """Strips anything that could act as a path separator/traversal token —
    identical helper to app.services.compliance._safe_component, duplicated
    here rather than imported so the two domains stay independently
    deployable (neither depends on the other's internals)."""
    cleaned = "".join(c for c in value if c.isalnum() or c in "-_.")
    cleaned = cleaned.strip(".") or "unknown"
    return cleaned[:max_len]


def receipts_dir(*, tenant_id: str) -> Path:
    return UPLOADS_ROOT / _safe_component(tenant_id) / "receipts"


def _receipt_filename(trip_id: str) -> str:
    return f"receipt_{_safe_component(trip_id)}.pdf"


def resolve_absolute_path(relative_path: str) -> Path:
    """Resolves a stored (relative-to-BACKEND_ROOT) path back to an absolute
    path, rejecting anything that would escape BACKEND_ROOT. Same contract as
    app.services.compliance.resolve_absolute_path."""
    absolute_path = (BACKEND_ROOT / relative_path).resolve()
    if BACKEND_ROOT not in absolute_path.parents and absolute_path != BACKEND_ROOT:
        raise ReceiptError("Stored receipt path resolves outside the uploads root")
    return absolute_path


# --- PDF rendering -----------------------------------------------------------


async def _lookup_driver_name(session: AsyncSession, *, tenant_id: str, driver_id: str) -> str:
    """Best-effort cross-domain lookup of the driver's display name, mirroring
    the resolve-by-id-scoped-to-tenant pattern app.services.trips.resolve_tariff
    already uses for tariff_id. driver_id is an unconstrained ref (see
    app/models/trips.py's module docstring) so a missing/foreign row is
    expected, not an error — falls back to a short id-derived label."""
    result = await session.execute(select(User).where(User.id == driver_id, User.tenant_id == tenant_id))
    driver = result.scalar_one_or_none()
    return driver.name if driver is not None else f"Driver {driver_id[:8]}"


async def _lookup_vehicle_label(session: AsyncSession, *, tenant_id: str, vehicle_id: str) -> str:
    result = await session.execute(select(Vehicle).where(Vehicle.id == vehicle_id, Vehicle.tenant_id == tenant_id))
    vehicle = result.scalar_one_or_none()
    return vehicle.rego if vehicle is not None else f"Vehicle {vehicle_id[:8]}"


def _fmt(amount) -> str:
    return f"${amount:.2f}"


def _render_pdf_bytes(*, trip: Trip, driver_name: str, vehicle_label: str) -> bytes:
    """Pure rendering step (no I/O) — kept separate from ensure_receipt_pdf so
    it's trivially unit-testable. Layout per blueprint 5.2.6: operator
    branding placeholder, receipt number, driver/vehicle, pickup/dropoff
    (addresses if the trip carries them — it currently only carries lat/lng,
    see app/models/trips.py, so those are shown instead of a fabricated
    address), fare breakdown, payment method."""
    pdf = FPDF(format="A4")
    pdf.set_auto_page_break(auto=True, margin=15)
    pdf.add_page()

    # --- Operator branding placeholder ---
    pdf.set_font("Helvetica", "B", 18)
    pdf.cell(0, 10, "CAB DISPATCH", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 8)
    pdf.set_text_color(120, 120, 120)
    pdf.cell(
        0, 5,
        "Operator logo / trading name / ABN placeholder - populated per-tenant at print time",
        new_x="LMARGIN", new_y="NEXT",
    )
    pdf.set_text_color(0, 0, 0)
    pdf.ln(3)

    # --- Receipt header ---
    pdf.set_font("Helvetica", "B", 13)
    pdf.cell(0, 8, "TAX INVOICE / RECEIPT", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 10)
    pdf.cell(0, 6, f"Receipt No: {trip.receipt_ref or trip.id}", new_x="LMARGIN", new_y="NEXT")
    pdf.cell(0, 6, f"Trip ID: {trip.id}", new_x="LMARGIN", new_y="NEXT")
    end_at = trip.end_at.strftime("%Y-%m-%d %H:%M UTC") if trip.end_at else "-"
    pdf.cell(0, 6, f"Date: {end_at}", new_x="LMARGIN", new_y="NEXT")
    pdf.ln(3)

    # --- Driver / vehicle ---
    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Driver & Vehicle", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 10)
    pdf.cell(0, 6, f"Driver: {driver_name}", new_x="LMARGIN", new_y="NEXT")
    pdf.cell(0, 6, f"Vehicle: {vehicle_label}", new_x="LMARGIN", new_y="NEXT")
    pdf.ln(3)

    # --- Journey ---
    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Journey", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 10)
    pickup = getattr(trip, "pickup_address", None) or f"{trip.start_lat:.5f}, {trip.start_lng:.5f}"
    if getattr(trip, "dropoff_address", None):
        dropoff = trip.dropoff_address
    elif trip.end_lat is not None and trip.end_lng is not None:
        dropoff = f"{trip.end_lat:.5f}, {trip.end_lng:.5f}"
    else:
        dropoff = "-"
    pdf.cell(0, 6, f"Pickup: {pickup}", new_x="LMARGIN", new_y="NEXT")
    pdf.cell(0, 6, f"Drop-off: {dropoff}", new_x="LMARGIN", new_y="NEXT")
    pdf.cell(0, 6, f"Distance: {trip.distance_m / 1000:.2f} km", new_x="LMARGIN", new_y="NEXT")
    pdf.ln(3)

    # --- Fare breakdown ---
    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Fare Breakdown", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 10)
    line_items = [
        ("Flag fall", trip.flag_fall),
        ("Distance charge", trip.dist_amount),
        ("Waiting charge", trip.wait_amount),
        ("Peak surcharge", trip.peak_amount),
        ("Tolls", trip.tolls),
        ("Passenger Service Levy (PSL)", trip.psl),
        ("Extras", trip.extras),
    ]
    for label, amount in line_items:
        pdf.cell(130, 6, label)
        pdf.cell(0, 6, _fmt(amount), new_x="LMARGIN", new_y="NEXT", align="R")

    pdf.set_font("Helvetica", "", 10)
    pdf.cell(130, 6, "Subtotal")
    pdf.cell(0, 6, _fmt(trip.subtotal), new_x="LMARGIN", new_y="NEXT", align="R")
    pdf.cell(130, 6, "Non-cash surcharge (capped at 5%)")
    pdf.cell(0, 6, _fmt(trip.surcharge), new_x="LMARGIN", new_y="NEXT", align="R")

    pdf.set_font("Helvetica", "B", 12)
    pdf.cell(130, 8, "TOTAL")
    pdf.cell(0, 8, _fmt(trip.total), new_x="LMARGIN", new_y="NEXT", align="R")
    pdf.set_font("Helvetica", "", 9)
    pdf.cell(0, 5, f"Includes GST: {_fmt(trip.gst_component)}", new_x="LMARGIN", new_y="NEXT")
    pdf.ln(3)

    # --- Payment method ---
    pdf.set_font("Helvetica", "B", 11)
    pdf.cell(0, 7, "Payment", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 10)
    pdf.cell(0, 6, f"Method: {trip.payment_method}", new_x="LMARGIN", new_y="NEXT")

    return bytes(pdf.output())


async def ensure_receipt_pdf(session: AsyncSession, *, tenant_id: str, trip: Trip) -> tuple[Path, str, bool]:
    """Renders the PDF if it doesn't already exist on disk for this trip
    (idempotent — deterministic filename per trip_id), writing it under
    `uploads/{tenant_id}/receipts/`. Returns
    (absolute_path, relative_path, generated_now).

    Raises TripNotClosedError if the trip isn't closed — the fare breakdown
    columns this renders from are only final after `close_trip` runs.
    """
    if trip.status != TRIP_STATUS_CLOSED:
        raise TripNotClosedError(f"Trip {trip.id} is not closed — cannot generate a receipt yet")

    target_dir = receipts_dir(tenant_id=tenant_id)
    target_dir.mkdir(parents=True, exist_ok=True)
    absolute_path = target_dir / _receipt_filename(trip.id)
    relative_path = absolute_path.relative_to(BACKEND_ROOT).as_posix()

    if absolute_path.is_file() and absolute_path.stat().st_size > 0:
        return absolute_path, relative_path, False

    driver_name = await _lookup_driver_name(session, tenant_id=tenant_id, driver_id=trip.driver_id)
    vehicle_label = await _lookup_vehicle_label(session, tenant_id=tenant_id, vehicle_id=trip.vehicle_id)
    pdf_bytes = _render_pdf_bytes(trip=trip, driver_name=driver_name, vehicle_label=vehicle_label)

    if not pdf_bytes:
        raise ReceiptError("PDF renderer produced empty output")

    absolute_path.write_bytes(pdf_bytes)
    return absolute_path, relative_path, True


# --- SendGrid email delivery with mock fallback -------------------------------


def _sendgrid_configured() -> bool:
    return bool(settings.SENDGRID_API_KEY)


def send_receipt_email(*, to_email: str, trip: Trip, pdf_bytes: bytes, pdf_filename: str) -> dict:
    """Returns a dict describing either a real SendGrid send or, if SendGrid
    isn't configured (no SENDGRID_API_KEY) or the call fails for any reason
    (auth error, no network in dev/CI, etc.), a clearly-labeled mock response
    — same shape/spirit as app.services.payments.create_tap_to_pay_intent's
    mock fallback, so this endpoint is testable without live SendGrid
    credentials."""
    if _sendgrid_configured():
        try:
            attachment_b64 = base64.b64encode(pdf_bytes).decode("ascii")
            with httpx.Client(timeout=10.0) as http_client:
                resp = http_client.post(
                    "https://api.sendgrid.com/v3/mail/send",
                    headers={"Authorization": f"Bearer {settings.SENDGRID_API_KEY}"},
                    json={
                        "personalizations": [{"to": [{"email": to_email}]}],
                        "from": {"email": settings.SENDGRID_FROM_EMAIL},
                        "subject": f"Your Cab Dispatch receipt {trip.receipt_ref or trip.id}",
                        "content": [
                            {
                                "type": "text/plain",
                                "value": (
                                    f"Thanks for riding with us. Your receipt "
                                    f"({trip.receipt_ref or trip.id}) for {_fmt(trip.total)} AUD "
                                    "is attached."
                                ),
                            }
                        ],
                        "attachments": [
                            {
                                "content": attachment_b64,
                                "filename": pdf_filename,
                                "type": "application/pdf",
                                "disposition": "attachment",
                            }
                        ],
                    },
                )
                resp.raise_for_status()
            return {"mock": False, "to_email": to_email, "sendgrid_status_code": resp.status_code}
        except httpx.HTTPError as exc:
            logger.warning("SendGrid mail send failed (%s) — returning mock email response.", exc)

    return {"mock": True, "would_send_to": to_email}


# --- Twilio SMS delivery with mock fallback -----------------------------------


def _twilio_configured() -> bool:
    return bool(settings.TWILIO_ACCOUNT_SID and settings.TWILIO_AUTH_TOKEN and settings.TWILIO_FROM_NUMBER)


def _receipt_sms_body(trip: Trip) -> str:
    """SMS can't carry a PDF attachment, and this dev environment has nowhere
    to publicly host the receipt for a link — per the task brief, the message
    intentionally omits a URL rather than inventing a fake hosting scheme."""
    return (
        f"Cab Dispatch receipt {trip.receipt_ref or trip.id}: {_fmt(trip.total)} AUD "
        f"paid by {trip.payment_method}. A link isn't available in this environment; "
        "the PDF can be emailed instead."
    )


def send_receipt_sms(*, to_phone: str, trip: Trip) -> dict:
    """Same mock-fallback pattern as send_receipt_email, via Twilio's REST
    API. Requires all three of TWILIO_ACCOUNT_SID/TWILIO_AUTH_TOKEN/
    TWILIO_FROM_NUMBER to be configured; any missing falls back to mock."""
    message = _receipt_sms_body(trip)

    if _twilio_configured():
        try:
            with httpx.Client(timeout=10.0) as http_client:
                resp = http_client.post(
                    f"https://api.twilio.com/2010-04-01/Accounts/{settings.TWILIO_ACCOUNT_SID}/Messages.json",
                    auth=(settings.TWILIO_ACCOUNT_SID, settings.TWILIO_AUTH_TOKEN),
                    data={"From": settings.TWILIO_FROM_NUMBER, "To": to_phone, "Body": message},
                )
                resp.raise_for_status()
                data = resp.json()
            return {"mock": False, "to_phone": to_phone, "twilio_sid": data.get("sid")}
        except (httpx.HTTPError, ValueError) as exc:
            logger.warning("Twilio SMS send failed (%s) — returning mock SMS response.", exc)

    logger.info("Mock SMS to %s: %s", to_phone, message)
    return {"mock": True, "would_send_to": to_phone, "message": message}
