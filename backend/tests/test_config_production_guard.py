"""Unit tests for the production-secrets startup guard in app.core.config.

`Settings.is_production` was defined but never referenced anywhere until this
guard was added -- nothing previously stopped the app booting in production
with the default, publicly-known JWT_SECRET still active. These tests
instantiate `Settings` directly (never mutating the module-level `settings`
singleton, which the whole rest of the test session relies on staying at its
ENV=test values -- see conftest.py) and call the guard function against
those instances.
"""
from __future__ import annotations

import pytest

from app.core.config import (
    DEFAULT_JWT_SECRET,
    InsecureProductionConfigError,
    Settings,
    assert_production_secrets_safe,
)


def test_guard_raises_in_production_with_default_jwt_secret():
    insecure = Settings(ENV="production", JWT_SECRET=DEFAULT_JWT_SECRET)

    with pytest.raises(InsecureProductionConfigError, match="JWT_SECRET"):
        assert_production_secrets_safe(insecure)


def test_guard_allows_production_with_a_real_secret():
    secure = Settings(ENV="production", JWT_SECRET="a-real-random-secret-not-the-dev-default")

    assert_production_secrets_safe(secure)  # must not raise


def test_guard_allows_non_production_env_even_with_default_secret():
    """The default ENV ("development") plus the default JWT_SECRET is
    exactly today's out-of-the-box dev/test setup -- the guard must leave it
    alone."""
    dev_default = Settings(JWT_SECRET=DEFAULT_JWT_SECRET)

    assert dev_default.is_production is False
    assert_production_secrets_safe(dev_default)  # must not raise


def test_module_level_settings_singleton_is_unaffected():
    """conftest.py sets ENV=test and a real JWT_SECRET before any app import,
    so the real module-level singleton must never trip this guard."""
    from app.core.config import settings

    assert settings.is_production is False
    assert_production_secrets_safe(settings)  # must not raise
