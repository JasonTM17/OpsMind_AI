import asyncio
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

from opsmind_ai_runtime.application.analysis_service import AnalysisFailure
from opsmind_ai_runtime.domain.analysis_contracts import AnalysisRequestV1, AnalysisResponseV1
from opsmind_ai_runtime.providers.deepseek.capability_probe import (
    DeepSeekCapabilityProbe,
    ProbeUsage,
    ProviderCapabilityProbeError,
    ProviderCapabilityState,
    StartupGatedAnalysisService,
)
from opsmind_ai_runtime.providers.deepseek.client import DeepSeekClient


class ProbeTransport:
    def __init__(self, response: dict[str, object]) -> None:
        self.response = response
        self.payload: dict[str, object] | None = None

    async def post_json(
        self,
        url: str,
        *,
        headers: dict[str, str],
        payload: dict[str, object],
        timeout_seconds: float,
    ) -> tuple[int, dict[str, object]]:
        _ = (url, headers, timeout_seconds)
        self.payload = payload
        return 200, self.response


class RecordingProbeAudit:
    def __init__(self) -> None:
        self.started: list[object] = []
        self.finished: list[tuple[object, bool, ProbeUsage | None, str | None]] = []

    async def record_started(self, probe_id: object) -> bool:
        self.started.append(probe_id)
        return True

    async def record_finished(
        self,
        probe_id: object,
        *,
        succeeded: bool,
        usage: ProbeUsage | None,
        failure_code: str | None = None,
    ) -> None:
        self.finished.append((probe_id, succeeded, usage, failure_code))


class RecordingExecutor:
    def __init__(self) -> None:
        self.called = False

    async def analyze(
        self, request: AnalysisRequestV1, *, capability_token: str
    ) -> AnalysisResponseV1:
        _ = capability_token
        self.called = True
        return AnalysisResponseV1(
            status="abstain",
            run_id=request.run_id,
            model_id="deepseek-v4-flash",
            prompt_version=request.prompt_version,
            schema_version="analysis-v1",
            usage={"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
            cost_estimate={"amount": 0},
        )


class FailingExecutor:
    async def analyze(
        self, request: AnalysisRequestV1, *, capability_token: str
    ) -> AnalysisResponseV1:
        _ = (request, capability_token)
        raise AnalysisFailure("provider.invalid_request", "synthetic request failure")


def _probe_response(**message_overrides: object) -> dict[str, object]:
    message = {
        "content": '{"opsmind_probe":"ok"}',
        "reasoning_content": "synthetic capability proof",
    }
    message.update(message_overrides)
    return {
        "model": "deepseek-v4-flash",
        "choices": [{"finish_reason": "stop", "message": message}],
        "usage": {"prompt_tokens": 4, "completion_tokens": 3, "total_tokens": 7},
    }


def _probe(
    transport: ProbeTransport,
    audit_sink: RecordingProbeAudit | None = None,
) -> DeepSeekCapabilityProbe:
    client = DeepSeekClient(
        base_url="https://provider.example/v1",
        credential="placeholder",
        model="deepseek-v4-flash",
        transport=transport,
    )
    return DeepSeekCapabilityProbe(
        client,
        model="deepseek-v4-flash",
        timeout_seconds=2,
        audit_sink=audit_sink,
    )


def _request() -> AnalysisRequestV1:
    return AnalysisRequestV1(
        incident_id=uuid4(),
        tenant_id=uuid4(),
        run_id=uuid4(),
        prompt="redacted metric",
        prompt_version="prompt-incident-v1",
        schema_version="analysis-v1",
        analysis_mode="investigate",
        context_refs=(),
        data_classifications={"redacted_metrics"},
        purpose="incident_investigation",
        token_budget=10,
        tool_budget=0,
        deadline_at=datetime.now(UTC) + timedelta(minutes=1),
    )


def test_probe_proves_exact_model_json_output_and_thinking() -> None:
    transport = ProbeTransport(_probe_response())

    asyncio.run(_probe(transport).verify())

    assert transport.payload is not None
    assert transport.payload["model"] == "deepseek-v4-flash"
    assert transport.payload["thinking"] == {"type": "enabled"}
    assert transport.payload["response_format"] == {"type": "json_object"}
    assert transport.payload["max_tokens"] == 64


def test_probe_audits_bounded_usage_without_provider_content() -> None:
    transport = ProbeTransport(_probe_response())
    audit = RecordingProbeAudit()
    usage = asyncio.run(_probe(transport, audit).verify())

    assert usage == ProbeUsage(prompt_tokens=4, completion_tokens=3, total_tokens=7)
    assert len(audit.started) == 1
    assert audit.finished == [(audit.started[0], True, usage, None)]


def test_cancelled_probe_records_a_terminal_audit_event() -> None:
    class BlockingTransport(ProbeTransport):
        async def post_json(
            self,
            url: str,
            *,
            headers: dict[str, str],
            payload: dict[str, object],
            timeout_seconds: float,
        ) -> tuple[int, dict[str, object]]:
            _ = (url, headers, payload, timeout_seconds)
            await asyncio.Event().wait()
            raise AssertionError("unreachable")

    async def scenario() -> None:
        audit = RecordingProbeAudit()
        task = asyncio.create_task(_probe(BlockingTransport(_probe_response()), audit).verify())
        await asyncio.sleep(0)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert audit.finished == [
            (
                audit.started[0],
                False,
                None,
                "provider_capability_probe_cancelled",
            )
        ]

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "response",
    [
        _probe_response(reasoning_content=""),
        _probe_response(content='{"opsmind_probe":"unexpected"}'),
        {**_probe_response(), "model": "deepseek-v4-pro"},
        {
            **_probe_response(),
            "usage": {"prompt_tokens": 4, "completion_tokens": 3, "total_tokens": 8},
        },
    ],
)
def test_probe_fails_closed_on_capability_drift(response: dict[str, object]) -> None:
    with pytest.raises(ProviderCapabilityProbeError, match="probe failed"):
        asyncio.run(_probe(ProbeTransport(response)).verify())


def test_failed_probe_records_a_bounded_terminal_audit_event() -> None:
    audit = RecordingProbeAudit()

    with pytest.raises(ProviderCapabilityProbeError, match="probe failed"):
        asyncio.run(_probe(ProbeTransport(_probe_response(reasoning_content="")), audit).verify())

    assert audit.finished == [
        (
            audit.started[0],
            False,
            None,
            "provider_capability_probe_failed",
        )
    ]


def test_startup_gate_rejects_traffic_until_probe_state_is_ready() -> None:
    state = ProviderCapabilityState()
    delegate = RecordingExecutor()
    gate = StartupGatedAnalysisService(delegate, state)
    request = _request()

    with pytest.raises(AnalysisFailure) as captured:
        asyncio.run(gate.analyze(request, capability_token="synthetic"))
    assert captured.value.code == "provider.unavailable"
    assert state.health_status() == "degraded"
    assert delegate.called is False

    state.mark_ready()
    response = asyncio.run(gate.analyze(request, capability_token="synthetic"))
    assert response.run_id == request.run_id
    assert state.health_status() == "ok"
    assert delegate.called is True


def test_request_failure_does_not_turn_process_readiness_into_a_global_outage() -> None:
    state = ProviderCapabilityState()
    state.mark_ready()
    gate = StartupGatedAnalysisService(FailingExecutor(), state)

    with pytest.raises(AnalysisFailure, match="synthetic request failure"):
        asyncio.run(gate.analyze(_request(), capability_token="synthetic"))

    assert state.ready is True
