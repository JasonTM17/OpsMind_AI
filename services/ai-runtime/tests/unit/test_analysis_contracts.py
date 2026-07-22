from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from pydantic import ValidationError

from opsmind_ai_runtime.domain.analysis_contracts import AnalysisRequestV1, DataClassification


def _request(**overrides: object) -> AnalysisRequestV1:
    values: dict[str, object] = {
        "incident_id": uuid4(),
        "tenant_id": uuid4(),
        "run_id": uuid4(),
        "prompt": "Investigate the redacted latency signal.",
        "prompt_version": "prompt-incident-investigation-v1",
        "schema_version": "analysis-v1",
        "analysis_mode": "investigate",
        "context_refs": (),
        "purpose": "incident_investigation",
        "token_budget": 1000,
        "tool_budget": 2,
        "deadline_at": datetime.now(UTC) + timedelta(minutes=1),
        "data_classifications": {DataClassification.REDACTED_METRICS},
    }
    values.update(overrides)
    return AnalysisRequestV1.model_validate(values)


def test_request_rejects_naive_deadline() -> None:
    with pytest.raises(ValidationError, match="timezone"):
        _request(deadline_at=datetime.now())


def test_request_rejects_unknown_fields() -> None:
    with pytest.raises(ValidationError, match="extra"):
        _request(untrusted_tenant_header="tenant-a")


def test_request_keeps_data_classes_explicit() -> None:
    request = _request()
    assert request.data_classifications == {DataClassification.REDACTED_METRICS}


def test_request_rejects_missing_or_empty_data_classification() -> None:
    with pytest.raises(ValidationError, match="data_classifications"):
        _request(data_classifications=set())
