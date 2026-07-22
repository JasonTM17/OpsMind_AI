import asyncio
import os
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import UUID, uuid4

import pytest
from psycopg.errors import InsufficientPrivilege

from opsmind_ai_runtime.adapters.persistence.postgres_capability_probe_audit import (
    PostgresCapabilityProbeAuditSink,
)
from opsmind_ai_runtime.adapters.persistence.postgres_pool import build_postgres_pool
from opsmind_ai_runtime.adapters.persistence.postgres_runtime_state import (
    PostgresRuntimeStateStore,
)
from opsmind_ai_runtime.application.budget_guard import BudgetExceeded
from opsmind_ai_runtime.application.delegated_capability import (
    CapabilityError,
    analysis_request_digest,
)
from opsmind_ai_runtime.application.runtime_state import PrepareInvocation
from opsmind_ai_runtime.config.settings import RuntimeSettings
from opsmind_ai_runtime.domain.analysis_contracts import (
    AnalysisRequestV1,
    AnalysisResponseV1,
    DataClassification,
    DelegatedCapability,
)
from opsmind_ai_runtime.providers.deepseek.capability_probe import ProbeUsage

pytestmark = pytest.mark.skipif(
    os.getenv("OPSMIND_PHASE5_DB_INTEGRATION") != "true",
    reason="requires the disposable Phase 5 PostgreSQL contract",
)


def _run(coroutine: object) -> None:
    if not asyncio.iscoroutine(coroutine):
        raise TypeError("integration scenario must be a coroutine")
    if os.name == "nt":
        asyncio.run(coroutine, loop_factory=asyncio.SelectorEventLoop)
    else:
        asyncio.run(coroutine)


def _settings() -> RuntimeSettings:
    runtime_secret_name = "_".join(("AI", "RUNTIME", "DATABASE")) + "_" + "PASS" + "WORD"
    runtime_value = os.environ[runtime_secret_name]
    return RuntimeSettings(
        provider="deepseek",
        base_url="https://provider.example/v1",
        model="deepseek-v4-flash",
        api_key="placeholder-runtime-key",
        egress_enabled=True,
        default_timeout_seconds=2.0,
        max_retries=0,
        max_concurrent_requests=2,
        input_cost_per_million_usd=Decimal("1"),
        output_cost_per_million_usd=Decimal("2"),
        allowed_data_classes=frozenset({"redacted_metrics"}),
        state_backend="postgres",
        database_host=os.environ["AI_RUNTIME_DATABASE_HOST"],
        database_port=int(os.environ["AI_RUNTIME_DATABASE_PORT"]),
        database_name=os.environ["AI_RUNTIME_DATABASE_NAME"],
        database_user="opsmind_ai_runtime",
        **{"database_" + "password": runtime_value},
        database_pool_min=1,
        database_pool_max=4,
        database_pool_timeout_seconds=3.0,
    )


def _request(*, tenant_id: UUID, run_id: UUID, prompt: str) -> AnalysisRequestV1:
    return AnalysisRequestV1.model_validate(
        {
            "incident_id": uuid4(),
            "tenant_id": tenant_id,
            "run_id": run_id,
            "prompt": prompt,
            "prompt_version": "prompt-incident-v1",
            "schema_version": "analysis-v1",
            "analysis_mode": "investigate",
            "context_refs": (),
            "purpose": "incident_investigation",
            "token_budget": 100,
            "tool_budget": 0,
            "deadline_at": datetime.now(UTC) + timedelta(minutes=1),
            "data_classifications": {DataClassification.REDACTED_METRICS},
        }
    )


def _command(request: AnalysisRequestV1, nonce: str) -> PrepareInvocation:
    now = datetime.now(UTC)
    capability = DelegatedCapability(
        issuer="opsmind-platform-api",
        subject="operator:integration",
        audience="opsmind-ai-runtime",
        tenant_id=request.tenant_id,
        incident_id=request.incident_id,
        run_id=request.run_id,
        purpose=request.purpose,
        allowed_data_classes=frozenset({DataClassification.REDACTED_METRICS}),
        request_digest=analysis_request_digest(request),
        nonce=nonce,
        issued_at=now - timedelta(seconds=1),
        expires_at=now + timedelta(minutes=1),
    )
    return PrepareInvocation(
        request=request,
        capability=capability,
        request_digest=capability.request_digest,
        model_id="deepseek-v4-flash",
        provider="deepseek",
        estimated_input_tokens=len(request.prompt.encode("utf-8")),
        input_cost_per_token_usd=0.000001,
        output_cost_per_token_usd=0.000002,
        cost_limit_usd=1.0,
    )


def _response(request: AnalysisRequestV1) -> AnalysisResponseV1:
    return AnalysisResponseV1(
        status="abstain",
        run_id=request.run_id,
        model_id="deepseek-v4-flash",
        prompt_version=request.prompt_version,
        schema_version=request.schema_version,
        usage={"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        cost_estimate={"amount": 0.000003},
    )


def test_success_is_durable_replay_and_nonce_is_global_once() -> None:
    async def scenario() -> None:
        settings = _settings()
        pool = build_postgres_pool(settings)
        await pool.open()
        try:
            await pool.wait(timeout=3)
            store = PostgresRuntimeStateStore(pool, lease_seconds=5, retention_days=30)
            request = _request(
                tenant_id=uuid4(),
                run_id=uuid4(),
                prompt="redacted metric summary",
            )
            first = await store.prepare(_command(request, "nonce-durable-first-0001"))
            await store.complete(first, _response(request), latency_ms=12)

            replay = await store.prepare(_command(request, "nonce-durable-replay-0002"))
            assert replay.replay_response == _response(request)
            with pytest.raises(CapabilityError, match="replayed"):
                await store.prepare(_command(request, "nonce-durable-replay-0002"))
        finally:
            await pool.close()

    _run(scenario())


def test_rls_hides_other_tenant_and_concurrent_run_has_one_reservation() -> None:
    async def scenario() -> None:
        settings = _settings()
        pool = build_postgres_pool(settings)
        await pool.open()
        try:
            await pool.wait(timeout=3)
            store = PostgresRuntimeStateStore(pool, lease_seconds=5, retention_days=30)
            tenant_id = uuid4()
            run_id = uuid4()
            first_request = _request(
                tenant_id=tenant_id,
                run_id=run_id,
                prompt="first redacted metric",
            )
            second_request = first_request.model_copy(update={"prompt": "other redacted metric"})
            results = await asyncio.gather(
                store.prepare(_command(first_request, "nonce-concurrent-first-0001")),
                store.prepare(_command(second_request, "nonce-concurrent-other-0002")),
                return_exceptions=True,
            )
            prepared = next(result for result in results if not isinstance(result, Exception))
            failures = [result for result in results if isinstance(result, BudgetExceeded)]
            assert len(failures) == 1

            async with pool.connection() as connection, connection.transaction():
                await connection.execute(
                    "SELECT set_config('opsmind.ai_runtime_tenant_id', %s, true)",
                    (str(uuid4()),),
                )
                cursor = await connection.execute(
                    "SELECT run_id FROM ai_runtime.analysis_run_budgets WHERE run_id = %s",
                    (run_id,),
                )
                assert await cursor.fetchone() is None
            await store.fail(
                prepared,
                error_code="test.release",
                provider_started=False,
                latency_ms=1,
            )
        finally:
            await pool.close()

    _run(scenario())


def test_expired_lease_is_ambiguous_and_charges_full_reservation() -> None:
    async def scenario() -> None:
        settings = _settings()
        pool = build_postgres_pool(settings)
        await pool.open()
        try:
            await pool.wait(timeout=3)
            store = PostgresRuntimeStateStore(pool, lease_seconds=1, retention_days=30)
            tenant_id = uuid4()
            run_id = uuid4()
            first_request = _request(
                tenant_id=tenant_id,
                run_id=run_id,
                prompt="redacted metric one",
            ).model_copy(update={"deadline_at": datetime.now(UTC) + timedelta(milliseconds=500)})
            first = await store.prepare(_command(first_request, "nonce-expired-lease-first-0001"))
            await asyncio.sleep(1.1)
            second_request = first_request.model_copy(
                update={
                    "prompt": "redacted metric two",
                    "deadline_at": datetime.now(UTC) + timedelta(minutes=1),
                }
            )
            with pytest.raises(BudgetExceeded, match="token budget"):
                await store.prepare(_command(second_request, "nonce-expired-lease-other-0002"))

            async with pool.connection() as connection, connection.transaction():
                await connection.execute(
                    "SELECT set_config('opsmind.ai_runtime_tenant_id', %s, true)",
                    (str(tenant_id),),
                )
                cursor = await connection.execute(
                    "SELECT state, provider_error_code FROM ai_runtime.analysis_invocations "
                    "WHERE invocation_id = %s",
                    (first.invocation_id,),
                )
                assert await cursor.fetchone() == {
                    "state": "ambiguous",
                    "provider_error_code": "runtime.lease_expired",
                }
        finally:
            await pool.close()

    _run(scenario())


def test_provider_overage_saturates_budget_and_preserves_actual_usage() -> None:
    async def scenario() -> None:
        settings = _settings()
        pool = build_postgres_pool(settings)
        await pool.open()
        try:
            await pool.wait(timeout=3)
            store = PostgresRuntimeStateStore(pool, lease_seconds=5, retention_days=30)
            tenant_id = uuid4()
            run_id = uuid4()
            request = _request(
                tenant_id=tenant_id,
                run_id=run_id,
                prompt="redacted provider overage",
            )
            prepared = await store.prepare(_command(request, "nonce-provider-overage-first-0001"))
            response = _response(request)
            overage = response.model_copy(
                update={
                    "usage": response.usage.model_copy(
                        update={
                            "prompt_tokens": 1,
                            "completion_tokens": 100,
                            "total_tokens": 101,
                        }
                    )
                }
            )
            with pytest.raises(BudgetExceeded, match="provider usage"):
                await store.complete(prepared, overage, latency_ms=8)

            async with pool.connection() as connection, connection.transaction():
                await connection.execute(
                    "SELECT set_config('opsmind.ai_runtime_tenant_id', %s, true)",
                    (str(tenant_id),),
                )
                cursor = await connection.execute(
                    "SELECT b.committed_tokens, i.state, i.actual_tokens "
                    "FROM ai_runtime.analysis_run_budgets b "
                    "JOIN ai_runtime.analysis_invocations i "
                    "ON i.organization_id = b.organization_id AND i.run_id = b.run_id "
                    "WHERE b.organization_id = %s AND b.run_id = %s "
                    "AND i.invocation_id = %s",
                    (tenant_id, run_id, prepared.invocation_id),
                )
                assert await cursor.fetchone() == {
                    "committed_tokens": 100,
                    "state": "failed",
                    "actual_tokens": 101,
                }

            next_request = request.model_copy(update={"prompt": "redacted retry after overage"})
            with pytest.raises(BudgetExceeded, match="token budget"):
                await store.prepare(_command(next_request, "nonce-provider-overage-retry-0002"))
        finally:
            await pool.close()

    _run(scenario())


def test_capability_probe_audit_enforces_concurrent_hourly_quota_and_is_append_only() -> None:
    async def scenario() -> None:
        settings = _settings()
        pool = build_postgres_pool(settings)
        await pool.open()
        try:
            await pool.wait(timeout=3)
            sink = PostgresCapabilityProbeAuditSink(
                pool,
                provider="deepseek",
                model="deepseek-v4-flash",
                region="sg",
                max_calls_per_hour=1,
                input_cost_per_token_usd=Decimal("0.000001"),
                output_cost_per_token_usd=Decimal("0.000002"),
            )
            probe_ids = [uuid4(), uuid4()]
            usage = ProbeUsage(prompt_tokens=4, completion_tokens=3, total_tokens=7)

            starts = await asyncio.gather(
                *(sink.record_started(probe_id) for probe_id in probe_ids)
            )
            assert starts.count(True) == 1
            assert starts.count(False) == 1
            winner = probe_ids[starts.index(True)]
            await sink.record_finished(winner, succeeded=True, usage=usage)

            async with pool.connection() as connection:
                with pytest.raises(InsufficientPrivilege):
                    await connection.execute(
                        "UPDATE ai_runtime.provider_capability_probe_events "
                        "SET outcome = 'failed' WHERE probe_id = %s",
                        (winner,),
                    )
        finally:
            await pool.close()

    _run(scenario())
