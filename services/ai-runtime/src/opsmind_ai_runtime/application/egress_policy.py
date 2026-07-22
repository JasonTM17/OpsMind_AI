"""Last-hop data-class and redaction policy for provider egress."""

from __future__ import annotations

import re
from dataclasses import dataclass

from opsmind_ai_runtime.application.tenant_egress_policy import TenantEgressPolicy
from opsmind_ai_runtime.config.settings import RuntimeSettings
from opsmind_ai_runtime.domain.analysis_contracts import AnalysisRequestV1, DataClassification


class EgressDenied(PermissionError):
    """Raised before serialization when data cannot leave the platform."""


_SECRET_PATTERNS = (
    re.compile(r"(?i)\b(?:sk|ds)-[a-z0-9_-]{16,}\b"),
    re.compile(
        r"(?im)(?:[\"']\s*)?\b(?:authorization|proxy[-_]?authorization|x[-_]?api[-_]?key|"
        r"api[-_]?key|api[-_]?credential|password|passwd|access[-_]?token|refresh[-_]?token|"
        r"bearer[-_]?token|authorization[-_]?header|token|client[-_]?secret|"
        r"cookie|set[-_]?cookie)\b(?:\s*[\"'])?\s*[:=]\s*"
        r"(?:(?:bearer|basic|token)\s+)?"
        r"(?:\"[^\"\r\n]*\"|'[^'\r\n]*'|[^\r\n,;}]+)"
    ),
    re.compile(r"\beyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\b"),
)
_BLOCKED_SECRET_PATTERNS = (
    re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b"),
    re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"),
    re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b"),
    re.compile(r"\beyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\b"),
    re.compile(r"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----"),
)
_EMAIL_PATTERN = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.IGNORECASE)


@dataclass(frozen=True, slots=True)
class EgressDecision:
    """Sanitized prompt plus the policy facts that authorized it."""

    sanitized_prompt: str
    provider: str
    data_classes: frozenset[DataClassification]


def redact_prompt(prompt: str) -> str:
    """Remove credential-like and personal identifiers without logging matches."""

    redacted = prompt
    for pattern in _SECRET_PATTERNS:
        redacted = pattern.sub("[REDACTED_SECRET]", redacted)
    return _EMAIL_PATTERN.sub("[REDACTED_EMAIL]", redacted)


def evaluate_egress(
    request: AnalysisRequestV1,
    settings: RuntimeSettings,
    policy: TenantEgressPolicy,
) -> EgressDecision:
    """Apply provider, purpose, and classification policy immediately before send."""

    if not settings.provider_ready:
        raise EgressDenied("provider egress is disabled or unavailable")
    if any(pattern.search(request.prompt) for pattern in _BLOCKED_SECRET_PATTERNS):
        raise EgressDenied("prompt contains prohibited credential material")
    if request.data_classifications - {
        DataClassification.REDACTED_METRICS,
        DataClassification.REDACTED_LOG_SUMMARY,
    }:
        raise EgressDenied("requested data class is not eligible for external egress")
    source_classifications = {
        {
            "metric": DataClassification.REDACTED_METRICS,
            "log_summary": DataClassification.REDACTED_LOG_SUMMARY,
            "incident_summary": DataClassification.REDACTED_INCIDENT_SUMMARY,
        }.get(reference.source_type)
        for reference in request.context_refs
    }
    if not source_classifications or None in source_classifications:
        raise EgressDenied("external egress requires approved evidence metadata")
    if source_classifications != set(request.data_classifications):
        raise EgressDenied("declared data classes do not match authorized evidence metadata")
    if not {item.value for item in request.data_classifications}.issubset(
        settings.allowed_data_classes
    ):
        raise EgressDenied("global data-class policy does not allow provider egress")
    if not policy.authorizes(
        tenant_id=request.tenant_id,
        purpose=request.purpose,
        provider=settings.provider,
        region=settings.provider_region,
        data_classes=request.data_classifications,
    ):
        raise EgressDenied("tenant/purpose/provider/region policy denies provider egress")
    sanitized = redact_prompt(request.prompt)
    if any(marker in sanitized for marker in ("[REDACTED_SECRET]", "[REDACTED_EMAIL]")):
        # Redaction is allowed, but policy evidence must never imply raw text was sent.
        sanitized = sanitized.replace("\x00", "")
    return EgressDecision(
        sanitized_prompt=sanitized,
        provider=settings.provider,
        data_classes=request.data_classifications,
    )
