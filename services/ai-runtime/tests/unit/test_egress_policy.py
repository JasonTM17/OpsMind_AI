from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import uuid4

import pytest

from opsmind_ai_runtime.application.egress_policy import (
    EgressDenied,
    evaluate_egress,
    redact_prompt,
)
from opsmind_ai_runtime.application.tenant_egress_policy import (
    EgressPolicyError,
    TenantEgressPolicy,
    TenantEgressRule,
)
from opsmind_ai_runtime.config.settings import RuntimeSettings
from opsmind_ai_runtime.domain.analysis_contracts import (
    AnalysisRequestV1,
    DataClassification,
    EvidenceReference,
)

JWT_CANARY = "eyJ" + "hbGciOiJSUzI1NiJ9" + ".payload.signature"


def _settings(**overrides: object) -> RuntimeSettings:
    values: dict[str, object] = {
        "provider": "deepseek",
        "base_url": "https://provider.example/v1",
        "model": "deepseek-v4-flash",
        "api_key": "placeholder",
        "egress_enabled": True,
        "default_timeout_seconds": 2.0,
        "max_retries": 0,
        "max_concurrent_requests": 1,
        "input_cost_per_million_usd": Decimal("1"),
        "output_cost_per_million_usd": Decimal("2"),
        "allowed_data_classes": frozenset({"redacted_metrics"}),
        "allowed_provider_hosts": frozenset({"provider.example"}),
        "provider_region": "sg",
        "egress_policy_file": "operator-policy.json",
    }
    values.update(overrides)
    return RuntimeSettings(**values)


def _request(classification: str = "redacted_metrics") -> AnalysisRequestV1:
    return AnalysisRequestV1(
        incident_id=uuid4(),
        tenant_id=uuid4(),
        run_id=uuid4(),
        prompt="email user@example.com password=do-not-send",
        prompt_version="prompt-incident-v1",
        schema_version="analysis-v1",
        analysis_mode="investigate",
        context_refs=(
            {
                "evidence_id": uuid4(),
                "digest": "sha256:" + "a" * 64,
                "source_type": "metric",
            },
        ),
        purpose="incident_investigation",
        token_budget=100,
        tool_budget=0,
        deadline_at=datetime.now(UTC) + timedelta(minutes=1),
        data_classifications={classification},
    )


def _policy(
    request: AnalysisRequestV1,
    *,
    provider: str = "deepseek",
    region: str = "sg",
) -> TenantEgressPolicy:
    return TenantEgressPolicy(
        (
            TenantEgressRule(
                tenant_id=request.tenant_id,
                purpose=request.purpose,
                provider=provider,
                region=region,
                data_classes=frozenset({DataClassification.REDACTED_METRICS}),
            ),
        )
    )


def test_redacts_before_egress() -> None:
    request = _request()
    decision = evaluate_egress(request, _settings(), _policy(request))
    assert "user@example.com" not in decision.sanitized_prompt
    assert "do-not-send" not in decision.sanitized_prompt


@pytest.mark.parametrize(
    "secret_text",
    [
        "Authorization: Bearer " + JWT_CANARY,
        'Authorization: "Bearer token with spaces"',
        'password="secret value with spaces"',
        '{"Authorization": "Bearer ' + "opaque" + '-credential"}',
        '{"api' + '_key": "' + "opaque" + '-api-value"}',
        '{"api' + 'Key": "' + "camel" + '-api-value"}',
        '{"xApi' + 'Key": "' + "camel" + '-header-value"}',
        '{"access' + 'Token": "' + "camel" + '-access-value"}',
        '{"refresh' + 'Token": "' + "camel" + '-refresh-value"}',
        '{"bearer' + 'Token": "' + "camel" + '-bearer-value"}',
        '{"authorization' + 'Header": "Bearer ' + "camel" + '-auth-header-value"}',
        '{"to' + 'ken": "' + "camel" + '-token-value"}',
        '{"api' + 'Credential": "' + "camel" + '-credential-value"}',
        '{"client' + 'Secret": "' + "camel" + '-client-value"}',
        '{"proxy' + 'Authorization": "Bearer ' + "camel" + '-proxy-value"}',
        '{"set' + 'Cookie": "' + "camel" + '-cookie-value"}',
        JWT_CANARY,
    ],
)
def test_redaction_never_leaves_credential_residue(secret_text: str) -> None:
    sanitized = redact_prompt(f"before\n{secret_text}\nafter")

    assert "[REDACTED_SECRET]" in sanitized
    assert "Bearer" not in sanitized
    assert "eyJ" not in sanitized
    assert "secret value" not in sanitized
    assert "opaque-credential" not in sanitized
    assert "opaque-api-value" not in sanitized
    assert "camel-" not in sanitized


def test_standalone_jwt_blocks_provider_egress() -> None:
    request = _request().model_copy(update={"prompt": f"inspect {JWT_CANARY}"})

    with pytest.raises(EgressDenied, match="credential"):
        evaluate_egress(request, _settings(), _policy(request))


def test_secret_classification_is_denied() -> None:
    request = _request("secret")
    with pytest.raises(EgressDenied):
        evaluate_egress(
            request,
            _settings(allowed_data_classes=frozenset({"secret"})),
            _policy(request),
        )


def test_key_alone_does_not_enable_egress() -> None:
    request = _request()
    with pytest.raises(EgressDenied):
        evaluate_egress(request, _settings(egress_enabled=False), _policy(request))


def test_high_confidence_cloud_credential_marker_blocks_egress() -> None:
    request = _request().model_copy(update={"prompt": "AKIA" + "A" * 16})

    with pytest.raises(EgressDenied, match="credential"):
        evaluate_egress(request, _settings(), _policy(request))


def test_declared_classification_must_match_evidence_source_metadata() -> None:
    request = _request().model_copy(
        update={"data_classifications": frozenset({"redacted_log_summary"})}
    )

    with pytest.raises(EgressDenied, match="do not match"):
        evaluate_egress(
            request,
            _settings(allowed_data_classes=frozenset({"redacted_log_summary"})),
            _policy(request),
        )


@pytest.mark.parametrize(
    ("policy_override", "settings_override"),
    [
        ({"provider": "other-provider"}, {}),
        ({"region": "us"}, {}),
        ({}, {"provider_region": "us"}),
    ],
)
def test_policy_requires_exact_provider_and_region(
    policy_override: dict[str, str], settings_override: dict[str, str]
) -> None:
    request = _request()

    with pytest.raises(EgressDenied, match="tenant/purpose/provider/region"):
        evaluate_egress(
            request,
            _settings(**settings_override),
            _policy(request, **policy_override),
        )


def test_policy_denies_a_different_tenant() -> None:
    request = _request()
    other_tenant_request = request.model_copy(update={"tenant_id": uuid4()})

    with pytest.raises(EgressDenied, match="tenant/purpose/provider/region"):
        evaluate_egress(request, _settings(), _policy(other_tenant_request))


def test_unapproved_incident_summary_class_cannot_enter_external_policy() -> None:
    with pytest.raises(EgressPolicyError, match="ineligible data class"):
        TenantEgressRule(
            tenant_id=uuid4(),
            purpose="incident_investigation",
            provider="deepseek",
            region="sg",
            data_classes=frozenset({DataClassification.REDACTED_INCIDENT_SUMMARY}),
        )


def test_policy_loader_rejects_duplicate_json_members(tmp_path) -> None:
    policy_file = tmp_path / "egress-policy.json"
    policy_file.write_text(
        '{"version":"egress-policy-v1","version":"egress-policy-v1","rules":[]}',
        encoding="utf-8",
    )

    with pytest.raises(EgressPolicyError, match="duplicate JSON member"):
        TenantEgressPolicy.from_file(policy_file)


def test_policy_loader_rejects_duplicate_authorization_rules(tmp_path) -> None:
    tenant_id = str(uuid4())
    rule = (
        '{"tenant_id":"' + tenant_id + '","purpose":"incident_investigation","provider":"deepseek",'
        '"region":"sg","data_classes":["redacted_metrics"]}'
    )
    policy_file = tmp_path / "egress-policy.json"
    policy_file.write_text(
        '{"version":"egress-policy-v1","rules":[' + rule + "," + rule + "]}",
        encoding="utf-8",
    )

    with pytest.raises(EgressPolicyError, match="duplicate rule"):
        TenantEgressPolicy.from_file(policy_file)


def test_policy_loader_accepts_one_exact_authorization(tmp_path) -> None:
    request = _request()
    policy_file = tmp_path / "egress-policy.json"
    policy_file.write_text(
        "{"
        '"version":"egress-policy-v1",'
        '"rules":[{'
        f'"tenant_id":"{request.tenant_id}",'
        '"purpose":"incident_investigation",'
        '"provider":"deepseek",'
        '"region":"sg",'
        '"data_classes":["redacted_metrics"]'
        "}]}\n",
        encoding="utf-8",
    )

    policy = TenantEgressPolicy.from_file(policy_file)

    assert policy.authorizes(
        tenant_id=request.tenant_id,
        purpose=request.purpose,
        provider="deepseek",
        region="sg",
        data_classes=request.data_classifications,
    )


def test_local_fixture_policy_may_include_redacted_incident_summary(tmp_path) -> None:
    request = _request().model_copy(
        update={
            "data_classifications": frozenset(
                {DataClassification.REDACTED_INCIDENT_SUMMARY}
            ),
            "context_refs": (
                EvidenceReference(
                    evidence_id=uuid4(),
                    digest="sha256:" + "b" * 64,
                    source_type="incident_summary",
                ),
            ),
        }
    )
    policy_file = tmp_path / "fixture-egress-policy.json"
    policy_file.write_text(
        "{"
        '"version":"egress-policy-v1",'
        '"rules":[{'
        f'"tenant_id":"{request.tenant_id}",'
        '"purpose":"incident_investigation",'
        '"provider":"fixture",'
        '"region":"zz",'
        '"data_classes":["redacted_incident_summary"]'
        "}]}\n",
        encoding="utf-8",
    )

    policy = TenantEgressPolicy.from_file(policy_file, allow_incident_summary=True)
    settings = _settings(
        provider="fixture",
        base_url="http://127.0.0.1:19090/v1",
        api_key=None,
        allowed_data_classes=frozenset({"redacted_incident_summary"}),
        allowed_provider_hosts=frozenset(),
        provider_region="zz",
    )

    decision = evaluate_egress(request, settings, policy)

    assert decision.provider == "fixture"
    assert "user@example.com" not in decision.sanitized_prompt
