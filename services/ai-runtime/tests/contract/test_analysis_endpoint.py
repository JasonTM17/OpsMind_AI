from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import uuid4

from fastapi.testclient import TestClient

from opsmind_ai_runtime.application.analysis_service import AnalysisFailure, AnalysisService
from opsmind_ai_runtime.application.budget_guard import BudgetGuard
from opsmind_ai_runtime.application.delegated_capability import (
    StaticCapabilityVerifier,
    analysis_request_digest,
)
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
from opsmind_ai_runtime.main import create_app


class StubAdapter:
    async def analyze(self, **kwargs: object) -> AnalysisResponseV1:
        context = kwargs["context"]
        return AnalysisResponseV1(
            status="abstain",
            run_id=context.run_id,
            model_id=context.model_id,
            prompt_version=context.prompt_version,
            schema_version="analysis-v1",
            usage={"prompt_tokens": 1, "completion_tokens": 0, "total_tokens": 1},
            cost_estimate={"amount": 0.0},
        )


class InvalidProviderResponseExecutor:
    async def analyze(
        self, request: AnalysisRequestV1, *, capability_token: str
    ) -> AnalysisResponseV1:
        _ = (request, capability_token)
        raise AnalysisFailure("provider.invalid_response", "provider response rejected")


class StateUnavailableExecutor:
    async def analyze(
        self, request: AnalysisRequestV1, *, capability_token: str
    ) -> AnalysisResponseV1:
        _ = (request, capability_token)
        raise AnalysisFailure("runtime.state_unavailable", "analysis state unavailable")


class ProviderFailureExecutor:
    def __init__(self, code: str) -> None:
        self._code = code

    async def analyze(
        self, request: AnalysisRequestV1, *, capability_token: str
    ) -> AnalysisResponseV1:
        _ = (request, capability_token)
        raise AnalysisFailure(self._code, "provider detail must not escape")


class UnexpectedFailureExecutor:
    async def analyze(
        self, request: AnalysisRequestV1, *, capability_token: str
    ) -> AnalysisResponseV1:
        _ = (request, capability_token)
        raise RuntimeError("secret internal stack detail")


def _service(
    verifier: StaticCapabilityVerifier | None = None,
    request: AnalysisRequestV1 | None = None,
) -> AnalysisService:
    tenant_id = request.tenant_id if request is not None else uuid4()
    purpose = request.purpose if request is not None else "incident_investigation"
    return AnalysisService(
        settings=RuntimeSettings(
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
        ),
        verifier=verifier or StaticCapabilityVerifier({}),
        adapter=StubAdapter(),  # type: ignore[arg-type]
        egress_policy=TenantEgressPolicy(
            (
                TenantEgressRule(
                    tenant_id=tenant_id,
                    purpose=purpose,
                    provider="deepseek",
                    region="sg",
                    data_classes=frozenset({DataClassification.REDACTED_METRICS}),
                ),
            )
        ),
        budget_guard=BudgetGuard(),
    )


def _request_body() -> dict[str, object]:
    return {
        "incident_id": str(uuid4()),
        "tenant_id": str(uuid4()),
        "run_id": str(uuid4()),
        "prompt": "redacted",
        "prompt_version": "prompt-incident-v1",
        "schema_version": "analysis-v1",
        "analysis_mode": "investigate",
        "context_refs": [
            {
                "evidence_id": str(uuid4()),
                "digest": "sha256:" + "a" * 64,
                "source_type": "metric",
            }
        ],
        "data_classifications": ["redacted_metrics"],
        "purpose": "incident_investigation",
        "token_budget": 100,
        "tool_budget": 0,
        "deadline_at": (datetime.now(UTC) + timedelta(minutes=1)).isoformat(),
    }


def _authorized_service(body: dict[str, object]) -> AnalysisService:
    request = AnalysisRequestV1.model_validate(body)
    now = datetime.now(UTC)
    claims = DelegatedCapability(
        issuer="opsmind-platform-api",
        subject="operator:test",
        audience="opsmind-ai-runtime",
        tenant_id=request.tenant_id,
        incident_id=request.incident_id,
        run_id=request.run_id,
        purpose=request.purpose,
        allowed_data_classes=frozenset({DataClassification.REDACTED_METRICS}),
        request_digest=analysis_request_digest(request),
        nonce="nonce-1234567890",
        issued_at=now - timedelta(seconds=1),
        expires_at=now + timedelta(minutes=1),
    )
    return _service(StaticCapabilityVerifier({"valid": claims}), request)


def test_analysis_endpoint_requires_delegated_capability() -> None:
    client = TestClient(create_app(analysis_service=_service()))
    response = client.post("/api/v1/analysis", json=_request_body())
    assert response.status_code == 401
    assert response.headers["content-type"] == "application/problem+json"
    assert response.json()["code"] == "delegation.invalid"
    assert response.headers["x-correlation-id"] == response.json()["correlation_id"]


def test_problem_does_not_reflect_unsafe_correlation_id() -> None:
    client = TestClient(create_app(analysis_service=_service()))
    response = client.post(
        "/api/v1/analysis",
        json=_request_body(),
        headers={"X-Correlation-ID": "unsafe value with spaces"},
    )
    assert response.status_code == 401
    assert "unsafe value with spaces" not in response.text
    assert response.headers["x-correlation-id"] != "unsafe value with spaces"


def test_validation_error_uses_stable_problem_details() -> None:
    client = TestClient(create_app(analysis_service=_service()))
    response = client.post(
        "/api/v1/analysis",
        json={"tenant_id": "not-a-uuid"},
        headers={"X-Correlation-ID": "corr-test-1"},
    )
    assert response.status_code == 422
    assert response.headers["content-type"] == "application/problem+json"
    assert response.json() == {
        "type": "about:blank",
        "title": "Analysis request rejected",
        "status": 422,
        "code": "request.invalid",
        "correlation_id": "corr-test-1",
    }


def test_authorized_analysis_returns_correlation_header() -> None:
    body = _request_body()
    client = TestClient(create_app(analysis_service=_authorized_service(body)))
    response = client.post(
        "/api/v1/analysis",
        json=body,
        headers={
            "X-OpsMind-Delegated-Capability": "valid",
            "X-Correlation-ID": "corr-success-1",
        },
    )
    assert response.status_code == 200
    assert response.headers["x-correlation-id"] == "corr-success-1"
    assert response.json()["status"] == "abstain"


def test_invalid_provider_response_maps_to_bad_gateway_problem() -> None:
    client = TestClient(create_app(analysis_service=InvalidProviderResponseExecutor()))
    response = client.post(
        "/api/v1/analysis",
        json=_request_body(),
        headers={"X-OpsMind-Delegated-Capability": "synthetic"},
    )

    assert response.status_code == 502
    assert response.headers["content-type"] == "application/problem+json"
    assert response.json()["code"] == "provider.invalid_response"
    assert "provider response rejected" not in response.text


def test_permanent_provider_failures_map_to_bad_gateway_without_detail() -> None:
    for code in (
        "provider.invalid_request",
        "provider.unauthorized",
        "provider.insufficient_balance",
        "provider.internal",
    ):
        client = TestClient(create_app(analysis_service=ProviderFailureExecutor(code)))
        response = client.post(
            "/api/v1/analysis",
            json=_request_body(),
            headers={"X-OpsMind-Delegated-Capability": "synthetic"},
        )

        assert response.status_code == 502
        assert response.json()["code"] == code
        assert "provider detail must not escape" not in response.text


def test_state_unavailable_maps_to_service_unavailable_problem() -> None:
    client = TestClient(create_app(analysis_service=StateUnavailableExecutor()))
    response = client.post(
        "/api/v1/analysis",
        json=_request_body(),
        headers={"X-OpsMind-Delegated-Capability": "synthetic"},
    )

    assert response.status_code == 503
    assert response.headers["content-type"] == "application/problem+json"
    assert response.json()["code"] == "runtime.state_unavailable"


def test_unexpected_executor_failure_maps_to_stable_problem_details() -> None:
    client = TestClient(create_app(analysis_service=UnexpectedFailureExecutor()))

    response = client.post(
        "/api/v1/analysis",
        json=_request_body(),
        headers={"X-OpsMind-Delegated-Capability": "synthetic"},
    )

    assert response.status_code == 500
    assert response.headers["content-type"] == "application/problem+json"
    assert response.json()["code"] == "runtime.unexpected_failure"
    assert "secret internal stack detail" not in response.text


def test_oversized_request_is_rejected_before_json_or_capability_processing() -> None:
    client = TestClient(create_app(max_request_body_bytes=1_024))
    response = client.post(
        "/api/v1/analysis",
        content=b"{" + b"x" * 1_024,
        headers={"Content-Type": "application/json", "X-Correlation-ID": "corr-large-1"},
    )

    assert response.status_code == 413
    assert response.headers["content-type"] == "application/problem+json"
    assert response.json()["code"] == "request.too_large"
    assert response.headers["x-correlation-id"] == "corr-large-1"
