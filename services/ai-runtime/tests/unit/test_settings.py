from datetime import UTC, datetime

import pytest

from opsmind_ai_runtime.config.settings import LEGACY_MODEL_RETIREMENT, RuntimeSettings


def test_default_settings_are_disabled_and_use_flash() -> None:
    settings = RuntimeSettings.from_env()
    assert settings.provider == "disabled"
    assert settings.model == "deepseek-v4-flash"
    assert settings.provider_ready is False


def test_retired_legacy_model_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_PROVIDER", "deepseek")
    monkeypatch.setenv("DEEPSEEK_MODEL", "deepseek-chat")
    with pytest.raises(ValueError, match="retired"):
        RuntimeSettings.from_env(now=LEGACY_MODEL_RETIREMENT)


def test_provider_key_without_egress_flag_is_not_ready(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_PROVIDER", "deepseek")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "opaque-test-key")
    monkeypatch.setenv("DEEPSEEK_API_BASE_URL", "https://provider.example/v1")
    assert RuntimeSettings.from_env(now=datetime.now(UTC)).provider_ready is False


@pytest.mark.parametrize(
    "url",
    [
        "http://localhost.attacker.example/v1",
        "https://user:password@provider.example/v1",
        "https://provider.example/v1?redirect=https://attacker.example",
    ],
)
def test_provider_base_url_rejects_ambiguous_or_credentialed_targets(
    monkeypatch: pytest.MonkeyPatch, url: str
) -> None:
    monkeypatch.setenv("DEEPSEEK_API_BASE_URL", url)

    with pytest.raises(ValueError, match="DEEPSEEK_API_BASE_URL"):
        RuntimeSettings.from_env()


@pytest.mark.parametrize("value", ["nan", "inf", "0", "301"])
def test_provider_timeout_must_be_finite_and_bounded(
    monkeypatch: pytest.MonkeyPatch, value: str
) -> None:
    monkeypatch.setenv("AI_PROVIDER_TIMEOUT_SECONDS", value)

    with pytest.raises(ValueError, match="AI_PROVIDER_TIMEOUT_SECONDS"):
        RuntimeSettings.from_env()


@pytest.mark.parametrize("value", ["NaN", "Infinity", "-0.01", "1000001"])
def test_provider_prices_must_be_finite_and_non_negative(
    monkeypatch: pytest.MonkeyPatch, value: str
) -> None:
    monkeypatch.setenv("AI_INPUT_COST_USD_PER_MILLION", value)

    with pytest.raises(ValueError, match="AI_INPUT_COST_USD_PER_MILLION"):
        RuntimeSettings.from_env()


def test_non_idempotent_provider_retry_configuration_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("AI_PROVIDER_MAX_RETRIES", "1")

    with pytest.raises(ValueError, match="AI_PROVIDER_MAX_RETRIES"):
        RuntimeSettings.from_env()


def test_capability_lifetime_cannot_exceed_platform_issuer_limit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("OPSMIND_CAPABILITY_MAX_LIFETIME_SECONDS", "301")

    with pytest.raises(ValueError, match="OPSMIND_CAPABILITY_MAX_LIFETIME_SECONDS"):
        RuntimeSettings.from_env()


def test_unproven_pro_model_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_PROVIDER", "deepseek")
    monkeypatch.setenv("DEEPSEEK_MODEL", "deepseek-v4-pro")

    with pytest.raises(ValueError, match="allowlist"):
        RuntimeSettings.from_env()


def test_provider_ready_requires_exact_destination_allowlist(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("AI_PROVIDER", "deepseek")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "opaque-test-key")
    monkeypatch.setenv("OPS_ENABLE_DEEPSEEK_EGRESS", "true")
    monkeypatch.setenv("DEEPSEEK_API_BASE_URL", "https://provider.example/v1")
    monkeypatch.setenv("AI_PROVIDER_ALLOWED_HOSTS", "different.example")

    assert RuntimeSettings.from_env().provider_ready is False


def test_provider_ready_rejects_unknown_zero_pricing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("AI_PROVIDER", "deepseek")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "opaque-test-key")
    monkeypatch.setenv("OPS_ENABLE_DEEPSEEK_EGRESS", "true")
    monkeypatch.setenv("DEEPSEEK_API_BASE_URL", "https://provider.example/v1")
    monkeypatch.setenv("AI_PROVIDER_ALLOWED_HOSTS", "provider.example")

    assert RuntimeSettings.from_env().provider_ready is False


def test_live_runtime_requires_postgres_state_and_asymmetric_capability_trust(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("AI_PROVIDER", "deepseek")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "opaque-test-key")
    monkeypatch.setenv("OPS_ENABLE_DEEPSEEK_EGRESS", "true")
    monkeypatch.setenv("DEEPSEEK_API_BASE_URL", "https://provider.example/v1")
    monkeypatch.setenv("AI_PROVIDER_ALLOWED_HOSTS", "provider.example")
    monkeypatch.setenv("AI_INPUT_COST_USD_PER_MILLION", "1")
    monkeypatch.setenv("AI_OUTPUT_COST_USD_PER_MILLION", "2")
    monkeypatch.setenv("AI_ALLOWED_DATA_CLASSES", "redacted_metrics")
    monkeypatch.setenv("AI_PROVIDER_REGION", "sg")
    monkeypatch.setenv("AI_EGRESS_POLICY_FILE", "D:/config/egress-policy.json")

    assert RuntimeSettings.from_env().runtime_ready is False

    monkeypatch.setenv("AI_RUNTIME_STATE_BACKEND", "postgres")
    monkeypatch.setenv("AI_RUNTIME_DATABASE_PASSWORD", "runtime-secret")
    assert RuntimeSettings.from_env().runtime_ready is False

    monkeypatch.setenv("OPSMIND_AI_CAPABILITY_JWKS_FILE", "D:/secret-mount/capability-jwks.json")
    monkeypatch.setenv("OPSMIND_AI_CAPABILITY_ISSUER", "https://platform.example.test")
    assert RuntimeSettings.from_env().runtime_ready is True


def test_capability_audience_rejects_ambiguous_values(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("OPSMIND_AI_CAPABILITY_AUDIENCE", "opsmind ai runtime")

    with pytest.raises(ValueError, match="OPSMIND_AI_CAPABILITY_AUDIENCE"):
        RuntimeSettings.from_env()


@pytest.mark.parametrize("value", ["0", "10001", "not-an-integer"])
def test_provider_probe_hourly_quota_is_bounded(
    monkeypatch: pytest.MonkeyPatch, value: str
) -> None:
    monkeypatch.setenv("AI_PROVIDER_PROBE_MAX_CALLS_PER_HOUR", value)

    with pytest.raises(ValueError, match="AI_PROVIDER_PROBE_MAX_CALLS_PER_HOUR"):
        RuntimeSettings.from_env()
