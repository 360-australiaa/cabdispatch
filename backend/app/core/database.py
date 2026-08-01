"""Async SQLAlchemy engine, session factory, declarative Base, and shared mixins.

Every domain model in this project (across all 12 domains) imports `Base` from here
and inherits `TimestampMixin` / `TenantScopedMixin` as needed. Primary-key
convention (apply exactly, everywhere — sqlite/postgres portable):

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

Do NOT use native UUID columns or autoincrement integers.
"""
from __future__ import annotations

from collections.abc import AsyncGenerator
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, func
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from app.core.config import settings

# echo=False even in dev — flip locally if you need SQL logging, don't commit it on.
engine = create_async_engine(settings.DATABASE_URL, echo=False, future=True)

AsyncSessionLocal = async_sessionmaker(
    bind=engine,
    class_=AsyncSession,
    expire_on_commit=False,
    autoflush=False,
)


class Base(DeclarativeBase):
    """Shared declarative base for every ORM model in the project."""


class TimestampMixin:
    """created_at / updated_at, server-defaulted so they're correct regardless of
    which application code path inserts/updates the row."""

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
        nullable=False,
    )


class TenantScopedMixin:
    """Adds the tenant_id FK every domain row must carry for row-level
    multi-tenancy. Domain routers MUST filter every query by this column using
    the tenant_id resolved via `app.core.security.get_current_tenant_id` — this
    is the sole multi-tenancy enforcement mechanism in the system, there is no
    separate DB-level RLS."""

    tenant_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("tenants.id"), nullable=False, index=True
    )


async def get_session() -> AsyncGenerator[AsyncSession, None]:
    """FastAPI dependency — yields an AsyncSession, closes it after the request."""
    async with AsyncSessionLocal() as session:
        yield session
