import asyncio
from contextlib import asynccontextmanager, suppress
from datetime import UTC, datetime, timedelta
from uuid import uuid4

from fastapi import FastAPI
from fastapi.testclient import TestClient

from opsmind_ai_runtime.main import (
    _fail_closed_on_monitor_exit,
    _provider_probe_delay,
    _runtime_lifespan,
    app,
    build_default_app,
    create_app,
)
from opsmind_ai_runtime.providers.deepseek.capability_probe import (
    ProviderCapabilityProbeError,
    ProviderCapabilityState,
)


class FakePool:
    def __init__(self) -> None:
        self.opened = False
        self.closed = False

    async def open(self) -> None:
        self.opened = True

    async def wait(self, *, timeout: float) -> None:
        assert timeout > 0

    @asynccontextmanager
    async def connection(self, *, timeout: float):
        assert timeout > 0
        yield FakeConnection()

    async def close(self) -> None:
        self.closed = True


class FakeConnection:
    async def execute(self, query: str) -> None:
        assert query == "SELECT 1"


class FakeProbe:
    def __init__(self, *, succeeds: bool) -> None:
        self.succeeds = succeeds

    async def verify(self) -> None:
        if not self.succeeds:
            raise ProviderCapabilityProbeError("synthetic failure")


def test_health_is_stable_and_contains_no_configuration() -> None:
    response = TestClient(app).get("/health")

    assert response.status_code == 200
    assert response.json() == {"service": "ai-runtime", "status": "ok"}


def test_readiness_returns_non_success_when_dependencies_are_degraded() -> None:
    response = TestClient(create_app(health_status="degraded")).get("/ready")

    assert response.status_code == 503
    assert response.json() == {"service": "ai-runtime", "status": "degraded"}


def test_readiness_returns_success_when_dependencies_are_ready() -> None:
    response = TestClient(create_app(health_status="ok")).get("/ready")

    assert response.status_code == 200
    assert response.json() == {"service": "ai-runtime", "status": "ok"}


def test_configured_provider_without_key_reports_degraded(monkeypatch) -> None:
    monkeypatch.setenv("AI_PROVIDER", "deepseek")
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    monkeypatch.setenv("DEEPSEEK_API_BASE_URL", "https://provider.example/v1")
    response = TestClient(build_default_app()).get("/health")
    assert response.json() == {"service": "ai-runtime", "status": "degraded"}


def test_disabled_default_keeps_analysis_route_fail_closed() -> None:
    body = {
        "incident_id": str(uuid4()),
        "tenant_id": str(uuid4()),
        "run_id": str(uuid4()),
        "prompt": "synthetic redacted input",
        "prompt_version": "prompt-incident-v1",
        "schema_version": "analysis-v1",
        "analysis_mode": "investigate",
        "context_refs": [],
        "data_classifications": ["redacted_metrics"],
        "purpose": "incident_investigation",
        "token_budget": 100,
        "tool_budget": 0,
        "deadline_at": (datetime.now(UTC) + timedelta(minutes=1)).isoformat(),
    }
    response = TestClient(app).post(
        "/api/v1/analysis",
        json=body,
        headers={"X-OpsMind-Delegated-Capability": "placeholder"},
    )
    assert response.status_code == 503
    assert response.headers["content-type"] == "application/problem+json"
    assert response.json()["code"] == "provider.unavailable"


def test_live_runtime_requires_shared_state_in_addition_to_provider_config(monkeypatch) -> None:
    monkeypatch.setenv("AI_PROVIDER", "deepseek")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "placeholder")
    monkeypatch.setenv("DEEPSEEK_API_BASE_URL", "https://provider.example/v1")
    monkeypatch.setenv("OPS_ENABLE_DEEPSEEK_EGRESS", "true")
    monkeypatch.setenv("AI_ALLOWED_DATA_CLASSES", "redacted_metrics")
    monkeypatch.setenv("AI_PROVIDER_ALLOWED_HOSTS", "provider.example")
    monkeypatch.setenv("AI_INPUT_COST_USD_PER_MILLION", "1")
    monkeypatch.setenv("AI_OUTPUT_COST_USD_PER_MILLION", "2")
    response = TestClient(build_default_app()).get("/health")
    assert response.json() == {"service": "ai-runtime", "status": "degraded"}


def test_live_runtime_requires_asymmetric_capability_keys(monkeypatch) -> None:
    monkeypatch.setenv("AI_PROVIDER", "deepseek")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "placeholder")
    monkeypatch.setenv("DEEPSEEK_API_BASE_URL", "https://provider.example/v1")
    monkeypatch.setenv("OPS_ENABLE_DEEPSEEK_EGRESS", "true")
    monkeypatch.setenv("AI_ALLOWED_DATA_CLASSES", "redacted_metrics")
    monkeypatch.setenv("AI_PROVIDER_ALLOWED_HOSTS", "provider.example")
    monkeypatch.setenv("AI_INPUT_COST_USD_PER_MILLION", "1")
    monkeypatch.setenv("AI_OUTPUT_COST_USD_PER_MILLION", "2")
    monkeypatch.setenv("AI_RUNTIME_STATE_BACKEND", "postgres")
    monkeypatch.setenv("AI_RUNTIME_DATABASE_PASSWORD", "placeholder")
    monkeypatch.delenv("OPSMIND_AI_CAPABILITY_JWKS_FILE", raising=False)

    response = TestClient(build_default_app()).get("/health")

    assert response.json() == {"service": "ai-runtime", "status": "degraded"}


def test_runtime_lifespan_opens_gate_only_after_successful_provider_probe() -> None:
    async def scenario() -> None:
        pool = FakePool()
        state = ProviderCapabilityState()
        lifespan = _runtime_lifespan(pool, 1.0, FakeProbe(succeeds=True), state)  # type: ignore[arg-type]

        async with lifespan(FastAPI()):
            assert pool.opened is True
            assert state.ready is True

        assert pool.closed is True
        assert state.ready is False

    asyncio.run(scenario())


def test_runtime_lifespan_stays_degraded_when_provider_probe_fails() -> None:
    async def scenario() -> None:
        pool = FakePool()
        state = ProviderCapabilityState()
        lifespan = _runtime_lifespan(pool, 1.0, FakeProbe(succeeds=False), state)  # type: ignore[arg-type]

        async with lifespan(FastAPI()):
            assert state.ready is False

        assert pool.closed is True

    asyncio.run(scenario())


def test_unexpected_readiness_monitor_exit_fails_closed() -> None:
    async def scenario() -> None:
        state = ProviderCapabilityState()
        state.mark_ready()

        async def fail() -> None:
            raise RuntimeError("synthetic monitor failure")

        task = asyncio.create_task(fail())
        with suppress(RuntimeError):
            await task
        _fail_closed_on_monitor_exit(task, state)

        assert state.ready is False

    asyncio.run(scenario())


def test_provider_probe_schedule_has_bounded_healthy_and_retry_jitter(monkeypatch) -> None:
    monkeypatch.setattr("opsmind_ai_runtime.main.randbelow", lambda upper: 0)
    assert _provider_probe_delay(True) == 300
    assert _provider_probe_delay(False) == 30

    monkeypatch.setattr("opsmind_ai_runtime.main.randbelow", lambda upper: upper - 1)
    assert _provider_probe_delay(True) == 330
    assert _provider_probe_delay(False) == 35
