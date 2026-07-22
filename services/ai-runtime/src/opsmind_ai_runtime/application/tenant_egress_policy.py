"""Strict operator-owned tenant policy for external provider egress."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from uuid import UUID

from opsmind_ai_runtime.domain.analysis_contracts import DataClassification

_MAX_POLICY_BYTES = 65_536
_MAX_RULES = 1_000
_PURPOSES = frozenset({"incident_investigation", "incident_summary"})
_EGRESS_DATA_CLASSES = frozenset(
    {
        DataClassification.REDACTED_METRICS,
        DataClassification.REDACTED_LOG_SUMMARY,
    }
)
_PROVIDER_PATTERN = re.compile(r"[a-z][a-z0-9_-]{0,31}")
_REGION_PATTERN = re.compile(r"[a-z]{2}(?:-[a-z0-9]{1,16})?")


class EgressPolicyError(ValueError):
    """Raised when an operator policy is malformed or ambiguous."""


@dataclass(frozen=True, slots=True)
class TenantEgressRule:
    """One exact tenant/provider authorization boundary."""

    tenant_id: UUID
    purpose: str
    provider: str
    region: str
    data_classes: frozenset[DataClassification]

    def __post_init__(self) -> None:
        if self.purpose not in _PURPOSES:
            raise EgressPolicyError("egress rule purpose is unsupported")
        if _PROVIDER_PATTERN.fullmatch(self.provider) is None:
            raise EgressPolicyError("egress rule provider is invalid")
        if _REGION_PATTERN.fullmatch(self.region) is None:
            raise EgressPolicyError("egress rule region is invalid")
        if not self.data_classes or not self.data_classes.issubset(_EGRESS_DATA_CLASSES):
            raise EgressPolicyError("egress rule contains an ineligible data class")


class TenantEgressPolicy:
    """Immutable exact-match policy. Absence of a matching rule always denies."""

    def __init__(self, rules: tuple[TenantEgressRule, ...]) -> None:
        if not rules:
            raise EgressPolicyError("egress policy must contain at least one rule")
        if len(rules) > _MAX_RULES:
            raise EgressPolicyError("egress policy contains too many rules")
        indexed: dict[tuple[UUID, str, str, str], frozenset[DataClassification]] = {}
        for rule in rules:
            key = (rule.tenant_id, rule.purpose, rule.provider, rule.region)
            if key in indexed:
                raise EgressPolicyError("egress policy contains a duplicate rule")
            indexed[key] = rule.data_classes
        self._rules = indexed

    @classmethod
    def from_file(cls, path: str | Path) -> TenantEgressPolicy:
        """Load a bounded strict JSON policy from an operator-mounted file."""

        policy_path = Path(path)
        with policy_path.open("rb") as stream:
            payload = stream.read(_MAX_POLICY_BYTES + 1)
        if len(payload) > _MAX_POLICY_BYTES:
            raise EgressPolicyError("egress policy exceeds the size limit")
        try:
            document = json.loads(payload, object_pairs_hook=_strict_object)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise EgressPolicyError("egress policy is not valid JSON") from exc
        return cls(_parse_document(document))

    def authorizes(
        self,
        *,
        tenant_id: UUID,
        purpose: str,
        provider: str,
        region: str,
        data_classes: frozenset[DataClassification],
    ) -> bool:
        allowed = self._rules.get((tenant_id, purpose, provider, region))
        return allowed is not None and bool(data_classes) and data_classes.issubset(allowed)


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise EgressPolicyError("egress policy contains a duplicate JSON member")
        result[key] = value
    return result


def _parse_document(document: object) -> tuple[TenantEgressRule, ...]:
    if not isinstance(document, dict) or set(document) != {"version", "rules"}:
        raise EgressPolicyError("egress policy envelope is invalid")
    if document["version"] != "egress-policy-v1":
        raise EgressPolicyError("egress policy version is unsupported")
    raw_rules = document["rules"]
    if not isinstance(raw_rules, list) or not 1 <= len(raw_rules) <= _MAX_RULES:
        raise EgressPolicyError("egress policy rules are invalid")
    return tuple(_parse_rule(rule) for rule in raw_rules)


def _parse_rule(raw: object) -> TenantEgressRule:
    required = {"tenant_id", "purpose", "provider", "region", "data_classes"}
    if not isinstance(raw, dict) or set(raw) != required:
        raise EgressPolicyError("egress policy rule fields are invalid")
    data_classes = raw["data_classes"]
    if (
        not isinstance(data_classes, list)
        or not 1 <= len(data_classes) <= len(_EGRESS_DATA_CLASSES)
        or any(not isinstance(value, str) for value in data_classes)
        or len(set(data_classes)) != len(data_classes)
    ):
        raise EgressPolicyError("egress policy data classes are invalid")
    try:
        return TenantEgressRule(
            tenant_id=UUID(_required_string(raw["tenant_id"], "tenant_id")),
            purpose=_required_string(raw["purpose"], "purpose"),
            provider=_required_string(raw["provider"], "provider"),
            region=_required_string(raw["region"], "region"),
            data_classes=frozenset(DataClassification(value) for value in data_classes),
        )
    except (ValueError, TypeError) as exc:
        if isinstance(exc, EgressPolicyError):
            raise
        raise EgressPolicyError("egress policy rule value is invalid") from exc


def _required_string(value: object, name: str) -> str:
    if not isinstance(value, str) or not value:
        raise EgressPolicyError(f"egress policy {name} must be a non-empty string")
    return value
