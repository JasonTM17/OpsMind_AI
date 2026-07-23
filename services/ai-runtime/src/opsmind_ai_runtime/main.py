"""FastAPI bootstrap for the provider-neutral AI runtime."""

import asyncio
from collections.abc import AsyncIterator, Callable
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from secrets import randbelow
from time import monotonic

from fastapi import FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict

from opsmind_ai_runtime.adapters.persistence.postgres_capability_probe_audit import (
    PostgresCapabilityProbeAuditSink,
)
from opsmind_ai_runtime.adapters.persistence.postgres_pool import PostgresPool, build_postgres_pool
from opsmind_ai_runtime.adapters.persistence.postgres_runtime_state import (
    PostgresRuntimeStateStore,
)
from opsmind_ai_runtime.api.body_limit import RequestBodyLimitMiddleware
from opsmind_ai_runtime.api.problem_details import build_problem_response, resolve_correlation_id
from opsmind_ai_runtime.api.v1.analysis import AnalysisExecutor, build_analysis_router
from opsmind_ai_runtime.application.analysis_service import (
    AnalysisService,
    UnavailableAnalysisService,
)
from opsmind_ai_runtime.application.rsa_jwks_capability import RsaJwksCapabilityVerifier
from opsmind_ai_runtime.application.tenant_egress_policy import TenantEgressPolicy
from opsmind_ai_runtime.config.settings import RuntimeSettings
from opsmind_ai_runtime.providers.deepseek.adapter import DeepSeekAdapter
from opsmind_ai_runtime.providers.deepseek.capability_probe import (
    DeepSeekCapabilityProbe,
    ProviderCapabilityProbeError,
    ProviderCapabilityState,
    StartupGatedAnalysisService,
)
from opsmind_ai_runtime.providers.deepseek.client import DeepSeekClient


class HealthResponse(BaseModel):
    """Stable readiness payload that contains no environment details."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    service: str
    status: str


def create_app(
    *,
    analysis_service: AnalysisExecutor | None = None,
    health_status: str | Callable[[], str] = "ok",
    max_request_body_bytes: int = 1_048_576,
    request_body_timeout_seconds: float = 5.0,
    lifespan: Callable[[FastAPI], AbstractAsyncContextManager[None]] | None = None,
) -> FastAPI:
    """Create an app; dependency injection keeps provider tests offline."""

    runtime_app = FastAPI(
        title="OpsMind AI Runtime",
        version="0.1.0",
        description="Provider-neutral bounded analysis runtime with fail-closed egress.",
        lifespan=lifespan,
    )
    runtime_app.add_middleware(
        RequestBodyLimitMiddleware,
        max_bytes=max_request_body_bytes,
        receive_timeout_seconds=request_body_timeout_seconds,
    )
    runtime_app.include_router(
        build_analysis_router(analysis_service or UnavailableAnalysisService())
    )

    @runtime_app.exception_handler(RequestValidationError)
    async def request_validation_problem(
        request: Request, _: RequestValidationError
    ) -> JSONResponse:
        correlation_id = resolve_correlation_id(request.headers.get("X-Correlation-ID"))
        return build_problem_response(422, "request.invalid", correlation_id)

    def current_status() -> str:
        return health_status() if callable(health_status) else health_status

    @runtime_app.get("/health", response_model=HealthResponse, tags=["operations"])
    def health() -> HealthResponse:
        """Report process liveness without exposing configuration or dependency details."""

        return HealthResponse(service="ai-runtime", status=current_status())

    @runtime_app.get("/ready", response_model=HealthResponse, tags=["operations"])
    def ready(response: Response) -> HealthResponse:
        """Report dependency readiness with a routable non-2xx degraded state."""

        status_value = current_status()
        response.status_code = 200 if status_value == "ok" else 503
        return HealthResponse(service="ai-runtime", status=status_value)

    return runtime_app


def build_default_app() -> FastAPI:
    """Build the disabled-by-default process app.

    Every prerequisite must be present before constructing a provider client.
    A configured key alone never enables provider traffic.
    """

    settings = RuntimeSettings.from_env()
    if not settings.runtime_ready:
        runtime_status = "ok" if settings.provider == "disabled" else "degraded"
        return create_app(
            analysis_service=UnavailableAnalysisService(),
            health_status=runtime_status,
            max_request_body_bytes=settings.max_request_body_bytes,
            request_body_timeout_seconds=settings.request_body_timeout_seconds,
        )
    try:
        verifier = RsaJwksCapabilityVerifier.from_file(
            settings.capability_jwks_file or "",
            expected_issuer=settings.capability_expected_issuer,
            expected_audience=settings.capability_expected_audience,
        )
        egress_policy = TenantEgressPolicy.from_file(
            settings.egress_policy_file or "",
            allow_incident_summary=settings.provider == "fixture",
        )
    except (OSError, ValueError):
        return create_app(
            analysis_service=UnavailableAnalysisService("delegation.unavailable"),
            health_status="degraded",
            max_request_body_bytes=settings.max_request_body_bytes,
            request_body_timeout_seconds=settings.request_body_timeout_seconds,
        )
    client = DeepSeekClient(
        base_url=settings.base_url,
        credential=settings.api_key or "fixture-provider",
        model=settings.model,
        timeout_seconds=settings.default_timeout_seconds,
        max_retries=settings.max_retries,
    )
    pool = build_postgres_pool(settings)
    service = AnalysisService(
        settings=settings,
        verifier=verifier,
        adapter=DeepSeekAdapter(client),
        egress_policy=egress_policy,
        state_store=PostgresRuntimeStateStore(
            pool,
            lease_seconds=settings.reservation_lease_seconds,
            retention_days=settings.invocation_retention_days,
        ),
        expected_issuer=settings.capability_expected_issuer,
        expected_audience=settings.capability_expected_audience,
    )
    capability_state = ProviderCapabilityState()
    capability_probe = DeepSeekCapabilityProbe(
        client,
        model=settings.model,
        timeout_seconds=settings.default_timeout_seconds,
        audit_sink=PostgresCapabilityProbeAuditSink(
            pool,
            provider=settings.provider,
            model=settings.model,
            region=settings.provider_region,
            max_calls_per_hour=settings.provider_probe_max_calls_per_hour,
            input_cost_per_token_usd=settings.input_cost_per_million_usd / 1_000_000,
            output_cost_per_token_usd=settings.output_cost_per_million_usd / 1_000_000,
        ),
    )
    return create_app(
        analysis_service=StartupGatedAnalysisService(service, capability_state),
        health_status=capability_state.health_status,
        max_request_body_bytes=settings.max_request_body_bytes,
        request_body_timeout_seconds=settings.request_body_timeout_seconds,
        lifespan=_runtime_lifespan(
            pool,
            settings.database_pool_timeout_seconds,
            capability_probe,
            capability_state,
            startup_jitter_seconds=30,
        ),
    )


def _runtime_lifespan(
    pool: PostgresPool,
    timeout_seconds: float,
    capability_probe: DeepSeekCapabilityProbe,
    capability_state: ProviderCapabilityState,
    *,
    startup_jitter_seconds: int = 0,
) -> Callable[[FastAPI], AbstractAsyncContextManager[None]]:
    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        await pool.open()
        monitor: asyncio.Task[None] | None = None
        try:
            await _database_probe(pool, timeout_seconds)
            capability_state.mark_database_ready()
            if startup_jitter_seconds > 0:
                await asyncio.sleep(float(randbelow(startup_jitter_seconds + 1)))
            try:
                await capability_probe.verify()
            except ProviderCapabilityProbeError:
                capability_state.mark_provider_unready()
            else:
                capability_state.mark_provider_ready()
            monitor = asyncio.create_task(
                _readiness_monitor(pool, timeout_seconds, capability_probe, capability_state)
            )
            monitor.add_done_callback(
                lambda task: _fail_closed_on_monitor_exit(task, capability_state)
            )
            yield
        finally:
            if monitor is not None:
                monitor.cancel()
                await asyncio.gather(monitor, return_exceptions=True)
            capability_state.mark_unready()
            await pool.close()

    return lifespan


async def _readiness_monitor(
    pool: PostgresPool,
    timeout_seconds: float,
    capability_probe: DeepSeekCapabilityProbe,
    capability_state: ProviderCapabilityState,
) -> None:
    """Continuously reconcile dependency readiness without probing on every request."""

    next_provider_probe = monotonic() + _provider_probe_delay(capability_state.provider_ready)
    while True:
        try:
            await asyncio.sleep(5.0)
            try:
                await _database_probe(pool, timeout_seconds)
            except Exception:
                capability_state.mark_database_unready()
            else:
                capability_state.mark_database_ready()

            if monotonic() < next_provider_probe:
                continue
            try:
                await capability_probe.verify()
            except ProviderCapabilityProbeError:
                capability_state.mark_provider_unready()
            else:
                capability_state.mark_provider_ready()
            next_provider_probe = monotonic() + _provider_probe_delay(
                capability_state.provider_ready
            )
        except asyncio.CancelledError:
            raise
        except Exception:
            capability_state.mark_unready()
            next_provider_probe = monotonic() + _provider_probe_delay(False)


def _provider_probe_delay(healthy: bool) -> float:
    """Add bounded per-process jitter so replica probes do not synchronize."""

    base_seconds, jitter_seconds = (300, 30) if healthy else (30, 5)
    return float(base_seconds + randbelow(jitter_seconds + 1))


async def _database_probe(pool: PostgresPool, timeout_seconds: float) -> None:
    """Verify a usable database connection, not just pool initialization."""

    await pool.wait(timeout=timeout_seconds)
    async with pool.connection(timeout=timeout_seconds) as connection:
        await connection.execute("SELECT 1")


def _fail_closed_on_monitor_exit(
    task: asyncio.Task[None],
    capability_state: ProviderCapabilityState,
) -> None:
    """A dead dependency monitor must never leave a stale green readiness state."""

    if task.cancelled():
        return
    if task.exception() is not None:
        capability_state.mark_unready()


app = build_default_app()
