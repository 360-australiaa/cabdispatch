"""Application settings — single source of truth for all environment configuration.

Loaded once as a module-level `settings` singleton. All values may be overridden via
environment variables or a `.env` file (see `.env.example` at the repo root for the
full documented list, including production values).
"""
from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # --- Environment ---
    ENV: str = "development"  # development | staging | production

    # --- Database ---
    # Local dev default: file-based sqlite via aiosqlite, zero setup required.
    # Production: postgres+asyncpg://<user>:<password>@<host>:5433/<db>
    #   (docker-compose.yml maps postgres:16-alpine to host port 5433)
    DATABASE_URL: str = "sqlite+aiosqlite:///./dev.db"

    # --- Redis (OPTIONAL) ---
    # The app MUST boot and operate correctly even if this host is unreachable —
    # every Redis call site falls back to an in-memory implementation.
    # docker-compose.yml maps redis:7-alpine to host port 6380.
    REDIS_URL: str | None = "redis://localhost:6380/0"

    # --- JWT / auth ---
    JWT_SECRET: str = "dev-only-insecure-secret-change-me"
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    REFRESH_TOKEN_EXPIRE_DAYS: int = 14

    # --- CORS ---
    CORS_ORIGINS: list[str] = [
        "http://localhost:5174",
        "http://127.0.0.1:5174",
    ]

    # --- Stripe ---
    STRIPE_SECRET_KEY: str = "sk_test_placeholder"

    @property
    def is_production(self) -> bool:
        return self.ENV == "production"


settings = Settings()
