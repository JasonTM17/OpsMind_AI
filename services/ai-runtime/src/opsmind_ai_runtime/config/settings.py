"""Fail-closed runtime configuration without a pydantic-settings dependency."""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from math import isfinite
from urllib.parse import urlsplit

LEGACY_MODEL_RETIREMENT = datetime(2026, 7, 24, 15, 59, tzinfo=UTC)
SUPPORTED_MODELS = frozenset({"deepseek-v4-flash"})
LEGACY_MODELS = frozenset({"deepseek-chat", "deepseek-reasoner"})
_REGION_PATTERN = re.compile(r"[a-z]{2}(?:-[a-z0-9]{1,16})?")


def _bool(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _int(name: str, default: int, minimum: int, maximum: int) -> int:
    raw = os.getenv(name, str(default))
    try:
        value = int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def _float(name: str, default: float, minimum: float, maximum: float) -> float:
    raw = os.getenv(name, str(default))
    try:
        value = float(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be a number") from exc
    if not isfinite(value) or not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def _decimal(name: str, default: str, minimum: Decimal, maximum: Decimal) -> Decimal:
    raw = os.getenv(name, default)
    try:
        value = Decimal(raw)
    except InvalidOperation as exc:
        raise ValueError(f"{name} must be a decimal number") from exc
    if not value.is_finite() or not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def _base_url() -> str:
    name = "DEEPSEEK_API_BASE_URL"
    value = os.getenv(name, "https://deepseek.invalid.example/v1").strip().rstrip("/")
    parsed = urlsplit(value)
    if parsed.username or parsed.password or not parsed.hostname or parsed.query or parsed.fragment:
        raise ValueError(f"{name} must be an origin/path without credentials, query, or fragment")
    if parsed.scheme == "https":
        return value
    if parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"}:
        return value
    raise ValueError(f"{name} must use HTTPS or exact loopback HTTP")


def _allowed_provider_hosts() -> frozenset[str]:
    name = "AI_PROVIDER_ALLOWED_HOSTS"
    values = frozenset(
        item.strip().lower().rstrip(".")
        for item in os.getenv(name, "api.deepseek.com").split(",")
        if item.strip()
    )
    if not values or any(
        not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?", item) or ".." in item
        for item in values
    ):
        raise ValueError(f"{name} must contain exact DNS hostnames")
    return values


def _database_name(name: str, default: str) -> str:
    value = os.getenv(name, default).strip()
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]{0,127}", value):
        raise ValueError(f"{name} must be a simple database identifier")
    return value


def _database_host() -> str:
    name = "AI_RUNTIME_DATABASE_HOST"
    value = os.getenv(name, "127.0.0.1").strip().lower().rstrip(".")
    if not value or len(value) > 253 or not re.fullmatch(r"[a-z0-9:.-]+", value):
        raise ValueError(f"{name} must be an exact hostname or IP address")
    return value


def _capability_issuer() -> str:
    name = "OPSMIND_AI_CAPABILITY_ISSUER"
    value = os.getenv(name, "https://platform.invalid.example").strip().rstrip("/")
    parsed = urlsplit(value)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError(f"{name} must be an absolute HTTPS issuer without credentials")
    return value


def _capability_audience() -> str:
    name = "OPSMIND_AI_CAPABILITY_AUDIENCE"
    value = os.getenv(name, "opsmind-ai-runtime").strip()
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", value):
        raise ValueError(f"{name} must be a simple audience identifier")
    return value


@dataclass(frozen=True, slots=True)
class RuntimeSettings:
    """Configuration needed to construct the runtime; secret values stay opaque."""

    provider: str
    base_url: str
    model: str
    api_key: str | None = field(repr=False)
    egress_enabled: bool
    default_timeout_seconds: float
    max_retries: int
    max_concurrent_requests: int
    input_cost_per_million_usd: Decimal
    output_cost_per_million_usd: Decimal
    allowed_data_classes: frozenset[str]
    allowed_provider_hosts: frozenset[str] = frozenset({"api.deepseek.com"})
    max_cost_usd_per_run: Decimal = Decimal("1")
    max_request_body_bytes: int = 1_048_576
    request_body_timeout_seconds: float = 5.0
    capability_max_lifetime_seconds: int = 300
    state_backend: str = "memory"
    database_host: str = "127.0.0.1"
    database_port: int = 5432
    database_name: str = "opsmind"
    database_user: str = "opsmind_ai_runtime"
    database_password: str | None = field(default=None, repr=False)
    database_pool_min: int = 1
    database_pool_max: int = 8
    database_pool_timeout_seconds: float = 3.0
    reservation_lease_seconds: int = 30
    invocation_retention_days: int = 30
    capability_jwks_file: str | None = None
    capability_expected_issuer: str = "https://platform.invalid.example"
    capability_expected_audience: str = "opsmind-ai-runtime"
    provider_region: str = ""
    egress_policy_file: str | None = None
    provider_probe_max_calls_per_hour: int = 120

    @classmethod
    def from_env(cls, *, now: datetime | None = None) -> RuntimeSettings:
        provider = os.getenv("AI_PROVIDER", "disabled").strip().lower()
        model = os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash").strip()
        current_time = now or datetime.now(UTC)
        if model in LEGACY_MODELS and current_time >= LEGACY_MODEL_RETIREMENT:
            raise ValueError("legacy DeepSeek model alias is retired")
        if provider == "deepseek" and model not in SUPPORTED_MODELS and model not in LEGACY_MODELS:
            raise ValueError("DEEPSEEK_MODEL is not in the operator allowlist")
        fixture_enabled = _bool("AI_FIXTURE_PROVIDER_ENABLED")
        if provider not in {"disabled", "deepseek", "fixture"}:
            raise ValueError("AI_PROVIDER must be disabled, deepseek, or fixture")
        if provider == "fixture" and not fixture_enabled:
            raise ValueError("AI_FIXTURE_PROVIDER_ENABLED must be true for the fixture provider")
        state_backend = os.getenv("AI_RUNTIME_STATE_BACKEND", "memory").strip().lower()
        if state_backend not in {"memory", "postgres"}:
            raise ValueError("AI_RUNTIME_STATE_BACKEND must be memory or postgres")
        database_user = _database_name("AI_RUNTIME_DATABASE_USER", "opsmind_ai_runtime")
        if state_backend == "postgres" and database_user != "opsmind_ai_runtime":
            raise ValueError("AI_RUNTIME_DATABASE_USER must remain opsmind_ai_runtime")
        base_url = _base_url()
        allowed = frozenset(
            item.strip()
            for item in os.getenv("AI_ALLOWED_DATA_CLASSES", "").split(",")
            if item.strip()
        )
        return cls(
            provider=provider,
            base_url=base_url,
            model=model,
            egress_enabled=_bool("OPS_ENABLE_DEEPSEEK_EGRESS"),
            default_timeout_seconds=_float("AI_PROVIDER_TIMEOUT_SECONDS", 20.0, 0.1, 300.0),
            max_retries=_int("AI_PROVIDER_MAX_RETRIES", 0, 0, 0),
            max_concurrent_requests=_int("AI_PROVIDER_MAX_CONCURRENCY", 32, 1, 2_500),
            input_cost_per_million_usd=_decimal(
                "AI_INPUT_COST_USD_PER_MILLION", "0", Decimal("0"), Decimal("1000000")
            ),
            output_cost_per_million_usd=_decimal(
                "AI_OUTPUT_COST_USD_PER_MILLION", "0", Decimal("0"), Decimal("1000000")
            ),
            allowed_data_classes=allowed,
            allowed_provider_hosts=_allowed_provider_hosts(),
            max_cost_usd_per_run=_decimal(
                "AI_MAX_COST_USD_PER_RUN", "1", Decimal("0.000001"), Decimal("1000")
            ),
            max_request_body_bytes=_int(
                "AI_RUNTIME_MAX_JSON_BODY_BYTES", 1_048_576, 1_024, 1_048_576
            ),
            request_body_timeout_seconds=_float(
                "AI_RUNTIME_BODY_RECEIVE_TIMEOUT_SECONDS", 5.0, 0.1, 30.0
            ),
            capability_max_lifetime_seconds=_int(
                "OPSMIND_CAPABILITY_MAX_LIFETIME_SECONDS", 300, 30, 300
            ),
            state_backend=state_backend,
            database_host=_database_host(),
            database_port=_int("AI_RUNTIME_DATABASE_PORT", 5432, 1, 65535),
            database_name=_database_name("AI_RUNTIME_DATABASE_NAME", "opsmind"),
            database_user=database_user,
            database_pool_min=_int("AI_RUNTIME_DB_POOL_MIN", 1, 1, 32),
            database_pool_max=_int("AI_RUNTIME_DB_POOL_MAX", 8, 1, 64),
            database_pool_timeout_seconds=_float(
                "AI_RUNTIME_DB_POOL_TIMEOUT_SECONDS", 3.0, 0.1, 30.0
            ),
            reservation_lease_seconds=_int("AI_RUNTIME_RESERVATION_LEASE_SECONDS", 30, 5, 300),
            invocation_retention_days=_int("AI_RUNTIME_INVOCATION_RETENTION_DAYS", 30, 1, 365),
            capability_jwks_file=os.getenv("OPSMIND_AI_CAPABILITY_JWKS_FILE") or None,
            capability_expected_issuer=_capability_issuer(),
            capability_expected_audience=_capability_audience(),
            provider_region=os.getenv("AI_PROVIDER_REGION", "").strip().lower(),
            egress_policy_file=os.getenv("AI_EGRESS_POLICY_FILE") or None,
            provider_probe_max_calls_per_hour=_int(
                "AI_PROVIDER_PROBE_MAX_CALLS_PER_HOUR", 120, 1, 10_000
            ),
            **{"api_key": os.getenv("DEEPSEEK_API_KEY") or None},
            **{"database_password": os.getenv("AI_RUNTIME_DATABASE_PASSWORD") or None},
        )

    @property
    def provider_ready(self) -> bool:
        """Whether a provider call is permitted; key presence alone is insufficient."""

        provider_host = (urlsplit(self.base_url).hostname or "").lower().rstrip(".")
        return (
            self.provider in {"deepseek", "fixture"}
            and self.egress_enabled
            and (
                bool(self.api_key)
                if self.provider == "deepseek"
                else provider_host in {"localhost", "127.0.0.1", "::1"}
            )
            and (
                provider_host in self.allowed_provider_hosts
                if self.provider == "deepseek"
                else provider_host in {"localhost", "127.0.0.1", "::1"}
            )
            and self.input_cost_per_million_usd > 0
            and self.output_cost_per_million_usd > 0
            and bool(self.allowed_data_classes)
            and _REGION_PATTERN.fullmatch(self.provider_region) is not None
            and bool(self.egress_policy_file)
        )

    @property
    def state_ready(self) -> bool:
        return (
            self.state_backend == "postgres"
            and bool(self.database_password)
            and self.database_user == "opsmind_ai_runtime"
            and self.database_pool_min <= self.database_pool_max
        )

    @property
    def runtime_ready(self) -> bool:
        """Live provider traffic requires shared authoritative state."""

        return self.provider_ready and self.state_ready and self.delegation_ready

    @property
    def delegation_ready(self) -> bool:
        issuer_host = (urlsplit(self.capability_expected_issuer).hostname or "").lower()
        return (
            bool(self.capability_jwks_file)
            and re.fullmatch(r"[A-Za-z0-9._-]{1,128}", self.capability_expected_audience)
            is not None
            and issuer_host != "invalid.example"
            and not issuer_host.endswith(".invalid.example")
        )
