import json
from pathlib import Path
from uuid import uuid4

import pytest
from pydantic import ValidationError

from opsmind_ai_runtime.domain.analysis_contracts import AnalysisRequestV1, AnalysisResponseV1

CONTRACT_ROOT = (
    Path(__file__).parents[4] / "packages" / "contracts" / "json-schema" / "ai-runtime" / "v1"
)


def _canonical(name: str) -> dict[str, object]:
    return json.loads((CONTRACT_ROOT / name).read_text(encoding="utf-8"))


def test_request_required_fields_align_with_pydantic() -> None:
    canonical = _canonical("analysis-request.schema.json")
    generated = AnalysisRequestV1.model_json_schema()
    assert set(canonical["required"]) == set(generated["required"])
    assert set(canonical["properties"]) == set(generated["properties"])


def test_response_required_fields_align_with_pydantic() -> None:
    canonical = _canonical("analysis-response.schema.json")
    generated = AnalysisResponseV1.model_json_schema()
    assert set(canonical["required"]) == set(generated["required"])
    assert set(canonical["properties"]) == set(generated["properties"])


def test_response_schema_publishes_terminal_state_and_usage_invariants() -> None:
    canonical = _canonical("analysis-response.schema.json")

    assert len(canonical["allOf"]) == 3
    assert canonical["x-semantic-invariants"] == [
        "usage.total_tokens equals usage.prompt_tokens plus usage.completion_tokens",
        "every complete hypothesis citation also appears in top-level citations",
    ]


def test_runtime_contract_executes_published_usage_invariant() -> None:
    with pytest.raises(ValidationError, match="total_tokens"):
        AnalysisResponseV1.model_validate(
            {
                "status": "abstain",
                "run_id": uuid4(),
                "model_id": "deepseek-v4-flash",
                "prompt_version": "prompt-incident-v1",
                "schema_version": "analysis-v1",
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 3},
                "cost_estimate": {"amount": 0},
            }
        )


def test_runtime_contract_executes_published_citation_subset_invariant() -> None:
    evidence_id = uuid4()
    digest = "sha256:" + "a" * 64
    with pytest.raises(ValidationError, match="appear in response citations"):
        AnalysisResponseV1.model_validate(
            {
                "status": "complete",
                "run_id": uuid4(),
                "model_id": "deepseek-v4-flash",
                "prompt_version": "prompt-incident-v1",
                "schema_version": "analysis-v1",
                "hypotheses": [
                    {
                        "title": "Bounded hypothesis",
                        "explanation": "Synthetic explanation",
                        "confidence": 0.5,
                        "citations": [
                            {"evidence_id": evidence_id, "digest": digest, "claim": "nested"}
                        ],
                    }
                ],
                "citations": [
                    {"evidence_id": evidence_id, "digest": digest, "claim": "top-level"}
                ],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
                "cost_estimate": {"amount": 0},
            }
        )
