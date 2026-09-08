"""Unit tests for the production-secrets startup guard in app.core.config.

`Settings.is_production` was defined but never referenced anywhere until this
guard was added -- nothing previously stopped the app booting in production
with the default, publicly-known JWT_SECRET still active. The guard then
stayed narrow (JWT_SECRET only) while two *functional* private keys shipped
as committed source defaults next to it, so a production deploy that forgot
either one booted happily: every tariff signed with a key anyone can read out
of this repo, every duress-device secret encrypted with the same. It now
covers all three.

The tail of this file covers the same class of defect one layer out:
`scripts/seed.py` running against a production database because nothing
checked ENV.

These tests instantiate `Settings` directly (never mutating the module-level `settings`
singleton, which the whole rest of the test session relies on staying at its
ENV=test values -- see conftest.py) and call the guard function against
those instances.
"""
from __future__ import annotations

import pathlib
import sys

import pytest

from app.core.config import (
    _PRODUCTION_SECRET_CHECKS,
    DEFAULT_JWT_SECRET,
    InsecureProductionConfigError,
    Settings,
    _field_default,
    assert_production_secrets_safe,
)

# The three secrets whose committed defaults the guard must reject. Read off
# the model rather than pasted here, so a rotated placeholder cannot leave
# this test asserting against a value the app no longer uses.
GUARDED_SECRETS = [name for name, _hint, _why in _PRODUCTION_SECRET_CHECKS]

# A real-looking override for each, distinct from every committed default.
REAL_VALUES = {
    "JWT_SECRET": "a-real-random-secret-not-the-dev-default",
    # A genuinely generated Ed25519 PKCS8/DER key and Fernet key would be
    # nicer, but the guard only ever does an inequality test against the
    # committed default -- it never parses either value (the parsing happens
    # in tariff_signing / crypto, which have their own tests). Any distinct
    # string exercises the accept path.
    "TARIFF_SIGNING_PRIVATE_KEY": "a-real-operator-generated-tariff-key",
    "SECRET_ENCRYPTION_KEY": "a-real-operator-generated-fernet-key",
}


def test_guard_raises_in_production_with_default_jwt_secret():
    insecure = Settings(ENV="production", JWT_SECRET=DEFAULT_JWT_SECRET)

    with pytest.raises(InsecureProductionConfigError, match="JWT_SECRET"):
        assert_production_secrets_safe(insecure)


def test_guard_covers_exactly_the_three_committed_secrets():
    """A regression fence on the guard's scope. If a fourth secret gains a
    committed default it must be added to _PRODUCTION_SECRET_CHECKS (and this
    list), not left to the next audit to find."""
    assert GUARDED_SECRETS == [
        "JWT_SECRET",
        "TARIFF_SIGNING_PRIVATE_KEY",
        "SECRET_ENCRYPTION_KEY",
    ]


@pytest.mark.parametrize("field_name", GUARDED_SECRETS)
def test_guard_raises_in_production_for_each_committed_default(field_name):
    """Every other secret is set to a real value, so the ONLY thing that can
    trip the guard is the one field under test still holding its committed
    default -- this proves each check independently, not just that some check
    fires."""
    kwargs = dict(REAL_VALUES)
    kwargs[field_name] = _field_default(field_name)

    insecure = Settings(ENV="production", **kwargs)

    with pytest.raises(InsecureProductionConfigError, match=field_name):
        assert_production_secrets_safe(insecure)


def test_guard_allows_production_with_a_real_secret():
    """All three overridden -- the only configuration a real production
    deploy is allowed to boot on."""
    secure = Settings(ENV="production", **REAL_VALUES)

    assert_production_secrets_safe(secure)  # must not raise


@pytest.mark.parametrize("field_name", GUARDED_SECRETS)
def test_committed_default_is_non_empty_so_the_comparison_is_meaningful(field_name):
    """Guards the guard: if a default were ever blanked to "" the inequality
    test would still pass for any real value but would also silently stop
    catching a deploy that set the variable to an empty string."""
    assert _field_default(field_name) != ""


def test_guard_allows_non_production_env_even_with_default_secret():
    """The default ENV ("development") plus the default JWT_SECRET is
    exactly today's out-of-the-box dev/test setup -- the guard must leave it
    alone."""
    dev_default = Settings(JWT_SECRET=DEFAULT_JWT_SECRET)

    assert dev_default.is_production is False
    # ...and it is genuinely sitting on all three committed defaults.
    for field_name in GUARDED_SECRETS:
        assert getattr(dev_default, field_name) == _field_default(field_name)
    assert_production_secrets_safe(dev_default)  # must not raise


def test_module_level_settings_singleton_is_unaffected():
    """conftest.py sets ENV=test and a real JWT_SECRET before any app import,
    so the real module-level singleton must never trip this guard."""
    from app.core.config import settings

    assert settings.is_production is False
    assert_production_secrets_safe(settings)  # must not raise


# --- scripts/seed.py's production refusal ------------------------------
# Lives in this file rather than its own because it is the same class of
# defect the guard above closes: something destructive running in production
# because nothing checked ENV. seed.py is what minted the driver account
# whose credentials ended up in three checked-in documents and in a shipped
# APK, and docs/DEPLOY_UBUNTU.md used to instruct operators to run it on the
# production server. `scripts/` is not a package, hence the path insert.

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "scripts"))
import seed as seed_script


def test_seed_refuses_production_without_the_flag():
    with pytest.raises(seed_script.ProductionSeedRefused, match="ENV=production"):
        seed_script.assert_seed_allowed(is_production=True, allow_production=False)


def test_seed_allows_production_with_the_explicit_flag():
    seed_script.assert_seed_allowed(is_production=True, allow_production=True)


def test_seed_allows_non_production_without_the_flag():
    seed_script.assert_seed_allowed(is_production=False, allow_production=False)


def test_seed_flag_is_off_by_default_and_opt_in_by_name():
    """The flag must be exactly --i-know-this-is-production: a deploy script
    that copy-pastes the old bare `python scripts/seed.py` has to fail, not
    silently proceed."""
    assert seed_script._parse_args([]).allow_production is False
    assert seed_script._parse_args(["--i-know-this-is-production"]).allow_production is True


def test_seed_defines_no_credential_constants():
    """Regression fence on the actual leak: the module used to carry a
    hardcoded staff password and a hardcoded 6-digit demo driver PIN at
    module level, and printed both on completion. Only env-var *names* may
    live here now."""
    assert not hasattr(seed_script, "DEMO_PASSWORD")
    assert not hasattr(seed_script, "DEMO_DRIVER_PIN")
    assert seed_script.OWNER_PASSWORD_ENV == "SEED_OWNER_PASSWORD"
    assert seed_script.DRIVER_PIN_ENV == "SEED_DRIVER_PIN"


def test_generated_credentials_are_random_when_env_is_unset(monkeypatch):
    """Unset env => a fresh unguessable value on every call, and a PIN the
    numeric-only driver keypad can actually type."""
    monkeypatch.delenv(seed_script.OWNER_PASSWORD_ENV, raising=False)
    monkeypatch.delenv(seed_script.DRIVER_PIN_ENV, raising=False)

    pw1, supplied1 = seed_script._staff_password()
    pw2, _ = seed_script._staff_password()
    assert supplied1 is False
    assert pw1 != pw2
    assert len(pw1) >= 32

    pin, pin_supplied = seed_script._driver_pin()
    assert pin_supplied is False
    assert len(pin) == 6
    assert pin.isdigit()


def test_supplied_credentials_are_used_verbatim(monkeypatch):
    monkeypatch.setenv(seed_script.OWNER_PASSWORD_ENV, "operator-chosen-value")
    monkeypatch.setenv(seed_script.DRIVER_PIN_ENV, "000000")

    assert seed_script._staff_password() == ("operator-chosen-value", True)
    assert seed_script._driver_pin() == ("000000", True)
