import asyncio
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import uuid4

import pytest

from opsmind_ai_runtime.application.analysis_service import AnalysisFailure, AnalysisService
from opsmind_ai_runtime.application.budget_guard import BudgetGuard
from opsmind_ai_runtime.application.delegated_capability import (
    StaticCapabilityVerifier,
    analysis_request_digest,
)
from opsmind_ai_runtime.application.provider_gateway import InvalidProviderResponse
from opsmind_ai_runtime.application.tenant_egress_policy import (
    TenantEgressPolicy,
    TenantEgressRule,
)
from opsmind_ai_runtime.config.settings import RuntimeSettings
from opsmind_ai_runtime.domain.analysis_contracts import (
    AnalysisRequestV1,
    AnalysisResponseV1,
    DataClassification,
    DelegatedCapability,
)


class FakeAdapter:
    def __init__(self) -> None:
        self.called = False

    async def analyze(self, **kwargs: object) -> AnalysisResponseV1:
        self.called = True
        context = kwargs["context"]
        return AnalysisResponseV1(
            status="abstain",
            run_id=context.run_id,
            model_id=context.model_id,
            prompt_version=context.prompt_version,
            schema_version="analysis-v1",
            usage={"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
            cost_estimate={"amount": 0.0},
        )


class OverBudgetAdapter(FakeAdapter):
    async def analyze(self, **kwargs: object) -> AnalysisResponseV1:
        response = await super().analyze(**kwargs)
        return response.model_copy(
            update={
                "usage": response.usage.model_copy(
                    update={"prompt_tokens": 101, "completion_tokens": 0, "total_tokens": 101}
                )
            }
        )


class InvalidResponseAdapter(FakeAdapter):
    async def analyze(self, **kwargs: object) -> AnalysisResponseV1:
        self.called = True
        raise InvalidProviderResponse("synthetic provider detail")


class UnboundCitationAdapter(FakeAdapter):
    async def analyze(self, **kwargs: object) -> AnalysisResponseV1:
        self.called = True
        context = kwargs["context"]
        citation = {
            "evidence_id": uuid4(),
            "digest": "sha256:" + "b" * 64,
            "claim": "Invented evidence claim.",
        }
        return AnalysisResponseV1(
            status="complete",
            run_id=context.run_id,
            model_id=context.model_id,
            prompt_version=context.prompt_version,
            schema_version="analysis-v1",
            hypotheses=(
                {
                    "title": "Invented hypothesis",
                    "explanation": "This citation was not authorized.",
                    "confidence": 0.9,
                    "citations": (citation,),
                },
            ),
            citations=(citation,),
            confidence=0.9,
            usage={"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
            cost_estimate={"amount": 0.0},
        )


class BlockingAdapter(FakeAdapter):
    def __init__(self) -> None:
        super().__init__()
        self.started = asyncio.Event()
        self.release = asyncio.Event()

    async def analyze(self, **kwargs: object) -> AnalysisResponseV1:
        self.started.set()
        await self.release.wait()
        return await super().analyze(**kwargs)


class RecordingUsageAdapter(FakeAdapter):
    def __init__(self) -> None:
        super().__init__()
        self.completion_limits: list[int] = []

    async def analyze(self, **kwargs: object) -> AnalysisResponseV1:
        self.called = True
        context = kwargs["context"]
        self.completion_limits.append(context.max_completion_tokens)
        return AnalysisResponseV1(
            status="abstain",
            run_id=context.run_id,
            model_id=context.model_id,
            prompt_version=context.prompt_version,
            schema_version="analysis-v1",
            usage={"prompt_tokens": 30, "completion_tokens": 30, "total_tokens": 60},
            cost_estimate={"amount": 0.0},
        )


def _settings() -> RuntimeSettings:
    return RuntimeSettings(
        provider="deepseek",
        base_url="https://provider.example/v1",
        model="deepseek-v4-flash",
        api_key="placeholder",
        egress_enabled=True,
        default_timeout_seconds=2.0,
        max_retries=0,
        max_concurrent_requests=1,
        input_cost_per_million_usd=Decimal("1"),
        output_cost_per_million_usd=Decimal("2"),
        allowed_data_classes=frozenset({"redacted_metrics"}),
        allowed_provider_hosts=frozenset({"provider.example"}),
        provider_region="sg",
        egress_policy_file="operator-policy.json",
    )


def _request(**overrides: object) -> AnalysisRequestV1:
    values: dict[str, object] = {
        "incident_id": uuid4(),
        "tenant_id": uuid4(),
        "run_id": uuid4(),
        "prompt": "redacted metric summary",
        "prompt_version": "prompt-incident-v1",
        "schema_version": "analysis-v1",
        "analysis_mode": "investigate",
        "context_refs": (
            {
                "evidence_id": uuid4(),
                "digest": "sha256:" + "a" * 64,
                "source_type": "metric",
            },
        ),
        "purpose": "incident_investigation",
        "token_budget": 100,
        "tool_budget": 0,
        "deadline_at": datetime.now(UTC) + timedelta(minutes=1),
        "data_classifications": {DataClassification.REDACTED_METRICS},
    }
    values.update(overrides)
    return AnalysisRequestV1.model_validate(values)


def _capability(request: AnalysisRequestV1, *, nonce: str | None = None) -> DelegatedCapability:
    now = datetime.now(UTC)
    return DelegatedCapability(
        issuer="opsmind-platform-api",
        subject="operator:test",
        audience="opsmind-ai-runtime",
        tenant_id=request.tenant_id,
        incident_id=request.incident_id,
        run_id=request.run_id,
        purpose=request.purpose,
        allowed_data_classes=frozenset({DataClassification.REDACTED_METRICS}),
        request_digest=analysis_request_digest(request),
        nonce=nonce or f"nonce-{request.run_id}",
        issued_at=now - timedelta(seconds=1),
        expires_at=now + timedelta(minutes=1),
    )


def _policy(*requests: AnalysisRequestV1) -> TenantEgressPolicy:
    unique = {
        (request.tenant_id, request.purpose): TenantEgressRule(
            tenant_id=request.tenant_id,
            purpose=request.purpose,
            provider="deepseek",
            region="sg",
            data_classes=frozenset({DataClassification.REDACTED_METRICS}),
        )
        for request in requests
    }
    return TenantEgressPolicy(tuple(unique.values()))


def test_invalid_delegation_fails_before_adapter() -> None:
    request = _request()
    adapter = FakeAdapter()
    service = AnalysisService(
        settings=_settings(),
        verifier=StaticCapabilityVerifier({"valid": _capability(request)}),
        adapter=adapter,  # type: ignore[arg-type]
        egress_policy=_policy(request),
        budget_guard=BudgetGuard(),
    )
    with pytest.raises(AnalysisFailure, match="delegated capability rejected"):
        asyncio.run(service.analyze(request, capability_token="forged"))
    assert adapter.called is False


def test_capability_binds_exact_normalized_request_body() -> None:
    authorized_request = _request()
    tampered_request = authorized_request.model_copy(
        update={"prompt": "different content with the same declared classification"}
    )
    adapter = FakeAdapter()
    service = AnalysisService(
        settings=_settings(),
        verifier=StaticCapabilityVerifier({"valid": _capability(authorized_request)}),
        adapter=adapter,  # type: ignore[arg-type]
        egress_policy=_policy(authorized_request),
        budget_guard=BudgetGuard(),
    )

    with pytest.raises(AnalysisFailure, match="delegated capability rejected") as captured:
        asyncio.run(service.analyze(tampered_request, capability_token="valid"))
    assert captured.value.code == "delegation.invalid"
    assert adapter.called is False


def test_capability_lifetime_is_bounded() -> None:
    request = _request()
    capability = _capability(request)
    long_lived = capability.model_copy(
        update={"expires_at": capability.issued_at + timedelta(minutes=10)}
    )
    adapter = FakeAdapter()
    service = AnalysisService(
        settings=_settings(),
        verifier=StaticCapabilityVerifier({"valid": long_lived}),
        adapter=adapter,  # type: ignore[arg-type]
        egress_policy=_policy(request),
        budget_guard=BudgetGuard(),
    )

    with pytest.raises(AnalysisFailure, match="delegated capability rejected") as captured:
        asyncio.run(service.analyze(request, capability_token="valid"))
    assert captured.value.code == "delegation.invalid"
    assert adapter.called is False


def test_expired_deadline_fails_before_adapter() -> None:
    request = _request(deadline_at=datetime.now(UTC) - timedelta(seconds=1))
    adapter = FakeAdapter()
    service = AnalysisService(
        settings=_settings(),
        verifier=StaticCapabilityVerifier({"valid": _capability(request)}),
        adapter=adapter,  # type: ignore[arg-type]
        egress_policy=_policy(request),
        budget_guard=BudgetGuard(),
    )
    with pytest.raises(AnalysisFailure, match="deadline"):
        asyncio.run(service.analyze(request, capability_token="valid"))
    assert adapter.called is False


def test_valid_delegation_allows_only_normalized_result() -> None:
    request = _request()
    adapter = FakeAdapter()
    service = AnalysisService(
        settings=_settings(),
        verifier=StaticCapabilityVerifier({"valid": _capability(request)}),
        adapter=adapter,  # type: ignore[arg-type]
        egress_policy=_policy(request),
        budget_guard=BudgetGuard(),
    )
    response = asyncio.run(service.analyze(request, capability_token="valid"))
    assert response.status == "abstain"
    assert adapter.called is True


def test_capability_nonce_cannot_be_replayed() -> None:
    request = _request()
    adapter = FakeAdapter()
    service = AnalysisService(
        settings=_settings(),
        verifier=StaticCapabilityVerifier({"valid": _capability(request)}),
        adapter=adapter,  # type: ignore[arg-type]
        egress_policy=_policy(request),
        budget_guard=BudgetGuard(),
    )
    asyncio.run(service.analyze(request, capability_token="valid"))
    with pytest.raises(AnalysisFailure, match="delegated capability rejected") as captured:
        asyncio.run(service.analyze(request, capability_token="valid"))
    assert captured.value.code == "delegation.invalid"


def test_provider_usage_over_hard_budget_is_a_stable_failure() -> None:
    request = _request(token_budget=100)
    service = AnalysisService(
        settings=_settings(),
        verifier=StaticCapabilityVerifier({"valid": _capability(request)}),
        adapter=OverBudgetAdapter(),  # type: ignore[arg-type]
        egress_policy=_policy(request),
        budget_guard=BudgetGuard(),
    )

    with pytest.raises(AnalysisFailure, match="analysis budget exceeded") as captured:
        asyncio.run(service.analyze(request, capability_token="valid"))
    assert captured.value.code == "budget.exceeded"


def test_multibyte_prompt_is_rejected_when_no_safe_completion_budget_remains() -> None:
    request = _request(prompt="🔥" * 30, token_budget=100)
    adapter = FakeAdapter()
    service = AnalysisService(
        settings=_settings(),
        verifier=StaticCapabilityVerifier({"valid": _capability(request)}),
        adapter=adapter,  # type: ignore[arg-type]
        egress_policy=_policy(request),
        budget_guard=BudgetGuard(),
    )

    with pytest.raises(AnalysisFailure, match="analysis budget exceeded") as captured:
        asyncio.run(service.analyze(request, capability_token="valid"))
    assert captured.value.code == "budget.exceeded"
    assert adapter.called is False


def test_projected_provider_cost_is_rejected_before_adapter() -> None:
    request = _request()
    adapter = FakeAdapter()
    settings = replace(
        _settings(),
        output_cost_per_million_usd=Decimal("1000000"),
    )
    service = AnalysisService(
        settings=settings,
        verifier=StaticCapabilityVerifier({"valid": _capability(request)}),
        adapter=adapter,  # type: ignore[arg-type]
        egress_policy=_policy(request),
        budget_guard=BudgetGuard(max_cost_usd=1.0),
    )

    with pytest.raises(AnalysisFailure, match="analysis budget exceeded") as captured:
        asyncio.run(service.analyze(request, capability_token="valid"))
    assert captured.value.code == "budget.exceeded"
    assert adapter.called is False


def test_invalid_provider_payload_is_a_stable_failure_without_raw_detail() -> None:
    request = _request()
    service = AnalysisService(
        settings=_settings(),
        verifier=StaticCapabilityVerifier({"valid": _capability(request)}),
        adapter=InvalidResponseAdapter(),  # type: ignore[arg-type]
        egress_policy=_policy(request),
        budget_guard=BudgetGuard(),
    )

    with pytest.raises(AnalysisFailure, match="provider response rejected") as captured:
        asyncio.run(service.analyze(request, capability_token="valid"))
    assert captured.value.code == "provider.invalid_response"
    assert "synthetic provider detail" not in str(captured.value)


def test_provider_citation_must_match_authorized_evidence_id_and_digest() -> None:
    request = _request(
        context_refs=(
            {
                "evidence_id": uuid4(),
                "digest": "sha256:" + "a" * 64,
                "source_type": "metric",
            },
        )
    )
    service = AnalysisService(
        settings=_settings(),
        verifier=StaticCapabilityVerifier({"valid": _capability(request)}),
        adapter=UnboundCitationAdapter(),  # type: ignore[arg-type]
        egress_policy=_policy(request),
        budget_guard=BudgetGuard(),
    )

    with pytest.raises(AnalysisFailure, match="provider response rejected") as captured:
        asyncio.run(service.analyze(request, capability_token="valid"))
    assert captured.value.code == "provider.invalid_response"


def test_concurrency_admission_fails_fast_without_creating_waiter_queue() -> None:
    async def scenario() -> None:
        first_request = _request()
        second_request = _request(deadline_at=datetime.now(UTC) + timedelta(milliseconds=100))
        adapter = BlockingAdapter()
        service = AnalysisService(
            settings=_settings(),
            verifier=StaticCapabilityVerifier(
                {
                    "first": _capability(first_request),
                    "second": _capability(second_request),
                }
            ),
            adapter=adapter,  # type: ignore[arg-type]
            egress_policy=_policy(first_request, second_request),
            budget_guard=BudgetGuard(),
        )
        first = asyncio.create_task(service.analyze(first_request, capability_token="first"))
        await adapter.started.wait()
        with pytest.raises(AnalysisFailure, match="delegated") as invalid:
            await service.analyze(second_request, capability_token="unknown")
        assert invalid.value.code == "delegation.invalid"
        with pytest.raises(AnalysisFailure, match="capacity") as captured:
            await service.analyze(second_request, capability_token="second")
        assert captured.value.code == "runtime.overloaded"
        adapter.release.set()
        await first

    asyncio.run(scenario())


def test_provider_completion_cap_uses_cumulative_run_usage() -> None:
    request = _request()
    second_request = request.model_copy(update={"prompt": "redacted metric report!"})
    adapter = RecordingUsageAdapter()
    service = AnalysisService(
        settings=_settings(),
        verifier=StaticCapabilityVerifier(
            {
                "first": _capability(request, nonce="nonce-first-exchange"),
                "second": _capability(second_request, nonce="nonce-second-exchange"),
            }
        ),
        adapter=adapter,  # type: ignore[arg-type]
        egress_policy=_policy(request, second_request),
        budget_guard=BudgetGuard(),
    )

    asyncio.run(service.analyze(request, capability_token="first"))
    with pytest.raises(AnalysisFailure, match="analysis budget exceeded"):
        asyncio.run(service.analyze(second_request, capability_token="second"))

    assert adapter.completion_limits == [77, 17]


def test_completed_exact_request_replays_without_second_provider_call() -> None:
    request = _request()
    adapter = RecordingUsageAdapter()
    service = AnalysisService(
        settings=_settings(),
        verifier=StaticCapabilityVerifier(
            {
                "first": _capability(request, nonce="nonce-first-replay-exchange"),
                "replay": _capability(request, nonce="nonce-second-replay-exchange"),
            }
        ),
        adapter=adapter,  # type: ignore[arg-type]
        egress_policy=_policy(request),
        budget_guard=BudgetGuard(),
    )

    first = asyncio.run(service.analyze(request, capability_token="first"))
    replay = asyncio.run(service.analyze(request, capability_token="replay"))

    assert replay == first
    assert adapter.completion_limits == [77]
