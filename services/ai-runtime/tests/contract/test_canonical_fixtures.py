import json
from pathlib import Path

from opsmind_ai_runtime.application.delegated_capability import analysis_request_digest
from opsmind_ai_runtime.domain.analysis_contracts import AnalysisRequestV1, AnalysisResponseV1

REPOSITORY_ROOT = Path(__file__).resolve().parents[4]
FIXTURES = REPOSITORY_ROOT / "packages" / "contracts" / "fixtures" / "deepseek"


def _load_fixture(name: str) -> object:
    with (FIXTURES / name).open(encoding="utf-8") as fixture:
        return json.load(fixture)


def test_canonical_analysis_request_fixture_matches_runtime_contract() -> None:
    request = AnalysisRequestV1.model_validate(_load_fixture("analysis-request-v1.json"))
    expected_digest = (FIXTURES / "analysis-request-v1.digest").read_text(encoding="utf-8").strip()

    assert request.schema_version == "analysis-v1"
    assert analysis_request_digest(request) == expected_digest


def test_canonical_analysis_response_fixture_matches_runtime_contract() -> None:
    response = AnalysisResponseV1.model_validate(_load_fixture("analysis-response-v1.json"))

    assert response.schema_version == "analysis-v1"
    assert response.hypotheses[0].citations
