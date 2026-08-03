"""Named tariff presets (blueprint 5.2.3/9.1: Airport Rank, Special Event,
Shared Ride, Wheelchair Accessible (WAV)) — a small library of sensible
starting defaults for THIS backend's real `app.models.tariffs.Tariff` fields
(region/booked + every fare_engine rate field), not the blueprint's unrelated
8-named-tariff-type JSON shape. An operator creating a new Tariff can start
from a preset via `POST /v1/tariffs/from-preset` (see `app/api/v1/tariffs.py`)
instead of filling in every rate field from scratch, then override any field
before saving.

These are starting points only, not new regulated rates: `airport_rank` and
`wheelchair_accessible` default to the current NSW Fares Order urban rank/hail
rates unchanged (booked=False, so `app.services.tariffs.validate_tariff_or_422`
still rate-cap-validates them same as any hand-entered rank/hail tariff);
`special_event` and `shared_ride` default to booked=True (unregulated private
hire) with adjusted rates, matching how an operator would set up an
unregulated tariff by hand today.
"""
from __future__ import annotations

from decimal import Decimal
from typing import Any, Literal

from app.services import fare_engine as fe

PresetKey = Literal["airport_rank", "special_event", "shared_ride", "wheelchair_accessible"]

PRESET_KEYS: tuple[PresetKey, ...] = (
    "airport_rank",
    "special_event",
    "shared_ride",
    "wheelchair_accessible",
)


def _urban_defaults(**overrides: Any) -> dict[str, Any]:
    """Base defaults = the current NSW Fares Order urban rank/hail rates
    (`fare_engine.URBAN_TARIFF`), the same reference `app.services.tariffs`
    already validates urban rank/hail tariffs against — so any preset that
    doesn't override a rate field stays within cap by construction."""
    base: dict[str, Any] = {
        "region": "urban",
        "booked": False,
        "flag_fall": fe.URBAN_TARIFF.flag_fall,
        "peak_charge": fe.URBAN_TARIFF.peak_charge,
        "dist_rate_1": fe.URBAN_TARIFF.dist_rate_1,
        "dist_rate_2": fe.URBAN_TARIFF.dist_rate_2,
        "night_rate_1": fe.URBAN_TARIFF.night_rate_1,
        "night_rate_2": fe.URBAN_TARIFF.night_rate_2,
        "holiday_rate_1": fe.URBAN_TARIFF.holiday_rate_1,
        "holiday_rate_2": fe.URBAN_TARIFF.holiday_rate_2,
        "waiting_rate_per_min": fe.URBAN_TARIFF.waiting_rate_per_min,
        "dist_km_threshold": fe.URBAN_TARIFF.dist_km_threshold,
        "speed_threshold_kmh": fe.URBAN_TARIFF.speed_threshold_kmh,
        "maxi_multiplier": fe.URBAN_TARIFF.maxi_multiplier,
        "multi_hire_pct": fe.URBAN_TARIFF.multi_hire_pct,
        "psl_amount": fe.URBAN_TARIFF.psl_amount,
        "surcharge_pct_cap": fe.URBAN_TARIFF.surcharge_pct_cap,
    }
    base.update(overrides)
    return base


TARIFF_PRESETS: dict[PresetKey, dict[str, Any]] = {
    "airport_rank": {
        "label": "Airport Rank",
        "description": (
            "Standard rank/hail pickup at an airport taxi rank. Uses the current "
            "NSW Fares Order urban rank/hail rates unchanged (booked=False, so it "
            "stays subject to Fares Order rate-cap validation on save) — airports "
            "have no separately regulated rate; this preset exists so an operator "
            "setting up an airport-specific tariff row (e.g. to attach an "
            "airport-only rank-fee Extra) doesn't have to re-type every rate field."
        ),
        "defaults": _urban_defaults(),
    },
    "special_event": {
        "label": "Special Event",
        "description": (
            "Pre-booked demand-surge tariff for one-off events (concerts, sport "
            "finals, New Year's Eve). booked=True (unregulated private hire, skips "
            "Fares Order validation) with a higher flag_fall, distance rates, and "
            "maxi_multiplier reflecting typical event surge pricing — tune before "
            "saving."
        ),
        "defaults": _urban_defaults(
            booked=True,
            flag_fall=Decimal("8.00"),
            dist_rate_1=Decimal("3.50"),
            dist_rate_2=Decimal("3.20"),
            maxi_multiplier=Decimal("2.0"),
        ),
    },
    "shared_ride": {
        "label": "Shared Ride",
        "description": (
            "Multiple-hiring / shared-ride tariff: booked=True (unregulated) with "
            "a reduced multi_hire_pct — each additional hirer owes a smaller share "
            "of the metered fare than the standard 75% (see "
            "app.services.fare_engine.FareEngine.multi_hire_amount_owed)."
        ),
        "defaults": _urban_defaults(booked=True, multi_hire_pct=Decimal("0.55")),
    },
    "wheelchair_accessible": {
        "label": "Wheelchair Accessible (WAV)",
        "description": (
            "Wheelchair-accessible vehicle tariff: rank/hail rates unchanged from "
            "the Fares Order (booked=False) but maxi_multiplier=1.0 (no maxi "
            "surcharge) — a WAV is an accessibility vehicle, not a maxi-taxi, so "
            "the 1.5x multi-passenger surcharge should not apply by default. "
            "Matches `app.models.fleet.VEHICLE_CLASS_WAT` vehicles, which "
            "`app.services.tariffs.suggest_tariff` looks for a tariff named like "
            "this to recommend."
        ),
        "defaults": _urban_defaults(maxi_multiplier=Decimal("1.0")),
    },
}


def list_presets() -> list[dict[str, Any]]:
    """All presets, in stable declaration order, each as
    `{"key": ..., "label": ..., "description": ..., "defaults": {...}}` —
    the exact shape `TariffPresetRead` (app.schemas.tariffs) validates."""
    return [{"key": key, **TARIFF_PRESETS[key]} for key in PRESET_KEYS]


def get_preset(key: str) -> dict[str, Any] | None:
    """Looks up one preset by key; None if `key` isn't a known preset."""
    return TARIFF_PRESETS.get(key)  # type: ignore[arg-type]


__all__ = ["PRESET_KEYS", "TARIFF_PRESETS", "PresetKey", "get_preset", "list_presets"]
