"""Tenant model — one row per taxi network / operator (TSP) on the platform."""
from __future__ import annotations

import uuid

from sqlalchemy import JSON, String
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TimestampMixin


class Tenant(Base, TimestampMixin):
    __tablename__ = "tenants"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    abn: Mapped[str | None] = mapped_column(String(20), nullable=True)
    tsp_number: Mapped[str | None] = mapped_column(String(50), nullable=True)
    bsp_number: Mapped[str | None] = mapped_column(String(50), nullable=True)
    theme_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    plan: Mapped[str] = mapped_column(String(50), nullable=False, default="standard")
    stripe_acct_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
