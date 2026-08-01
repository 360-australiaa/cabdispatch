"""Pydantic v2 schemas for the auth endpoints (app/api/v1/auth.py).

Auth is not one of the 12 domain slices — it's foundation glue added at
integration time so seeded users (see scripts/seed.py) can actually obtain a
bearer token to call the rest of the API / log into the dashboard.
"""
from __future__ import annotations

from pydantic import BaseModel, ConfigDict


class LoginRequest(BaseModel):
    email: str
    password: str


class UserRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str | None
    role: str
    name: str
    email: str
    status: str


class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    user: UserRead


class RefreshRequest(BaseModel):
    refresh_token: str


class RefreshResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
