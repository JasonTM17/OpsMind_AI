"""Versioned, provider-neutral analysis contracts.

These models are deliberately independent of FastAPI and DeepSeek. The model
response is untrusted input and is validated before it can become an accepted
analysis result or a normalized tool intent.
"""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

NonEmptyString = Annotated[str, Field(min_length=1, max_length=256)]
EvidenceNote = Annotated[str, Field(min_length=1, max_length=1_024)]


class AnalysisMode(StrEnum):
    """Allowed bounded analysis modes."""

    INVESTIGATE = "investigate"
    SUMMARIZE = "summarize"


class DataClassification(StrEnum):
    """Data classes that can be evaluated by the egress policy."""

    REDACTED_METRICS = "redacted_metrics"
    REDACTED_LOG_SUMMARY = "redacted_log_summary"
    REDACTED_INCIDENT_SUMMARY = "redacted_incident_summary"
    RAW_LOG = "raw_log"
    SECRET = "secret"
    PERSONAL_DATA = "personal_data"


class AnalysisStatus(StrEnum):
    """Terminal statuses returned by the runtime."""

    COMPLETE = "complete"
    NEED_MORE_EVIDENCE = "need_more_evidence"
    ABSTAIN = "abstain"
    PROVIDER_UNAVAILABLE = "provider_unavailable"
    BUDGET_EXCEEDED = "budget_exceeded"


class EvidenceReference(BaseModel):
    """Opaque reference to evidence already authorized by the platform."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    evidence_id: UUID
    digest: Annotated[str, Field(pattern=r"^sha256:[0-9a-f]{64}$")]
    source_type: Literal[
        "metric", "log_summary", "incident_summary", "trace", "change", "runbook"
    ]


class DelegatedCapability(BaseModel):
    """Platform-issued scope that must agree with the request body."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    issuer: NonEmptyString
    subject: NonEmptyString
    audience: NonEmptyString
    tenant_id: UUID
    incident_id: UUID
    run_id: UUID
    purpose: Literal["incident_investigation", "incident_summary"]
    allowed_data_classes: frozenset[DataClassification] = frozenset()
    request_digest: Annotated[str, Field(pattern=r"^sha256:[0-9a-f]{64}$")]
    nonce: Annotated[str, Field(min_length=16, max_length=128)]
    issued_at: datetime
    expires_at: datetime

    @field_validator("issued_at")
    @classmethod
    def issue_time_must_be_timezone_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("issued_at must include a timezone")
        return value

    @field_validator("expires_at")
    @classmethod
    def expiry_after_issue(cls, value: datetime, info: object) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("expires_at must include a timezone")
        issued_at = getattr(info, "data", {}).get("issued_at")
        if issued_at is not None and value <= issued_at:
            raise ValueError("expires_at must be after issued_at")
        return value


class AnalysisRequestV1(BaseModel):
    """Input accepted by the bounded analysis service."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    incident_id: UUID
    tenant_id: UUID
    run_id: UUID
    prompt: Annotated[str, Field(min_length=1, max_length=32_768)]
    prompt_version: Annotated[str, Field(pattern=r"^prompt-[a-z0-9-]+-v\d+$")]
    schema_version: Literal["analysis-v1"]
    analysis_mode: AnalysisMode
    context_refs: Annotated[tuple[EvidenceReference, ...], Field(max_length=200)]
    data_classifications: Annotated[
        frozenset[DataClassification], Field(min_length=1, max_length=5)
    ]
    purpose: Literal["incident_investigation", "incident_summary"]
    token_budget: Annotated[int, Field(ge=1, le=100_000)]
    tool_budget: Annotated[int, Field(ge=0, le=20)]
    deadline_at: datetime

    @field_validator("deadline_at")
    @classmethod
    def deadline_must_be_timezone_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("deadline_at must include a timezone")
        return value


class Citation(BaseModel):
    """Evidence citation required for every model claim."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    evidence_id: UUID
    digest: Annotated[str, Field(pattern=r"^sha256:[0-9a-f]{64}$")]
    claim: Annotated[str, Field(min_length=1, max_length=1_024)]


class Hypothesis(BaseModel):
    """Model hypothesis kept distinct from observed evidence."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    title: Annotated[str, Field(min_length=1, max_length=256)]
    explanation: Annotated[str, Field(min_length=1, max_length=4_096)]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    citations: Annotated[tuple[Citation, ...], Field(max_length=50)] = ()


class ToolIntent(BaseModel):
    """Normalized read-only intent; execution belongs to Phase 6."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    intent_id: UUID
    connector: Literal["metrics", "logs", "traces", "changes", "runbooks"]
    operation: NonEmptyString
    arguments_digest: Annotated[str, Field(pattern=r"^sha256:[0-9a-f]{64}$")]
    rationale: Annotated[str, Field(min_length=1, max_length=1_024)]


class Usage(BaseModel):
    """Provider usage normalized without retaining provider raw payloads."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    prompt_tokens: Annotated[int, Field(ge=0)]
    completion_tokens: Annotated[int, Field(ge=0)]
    total_tokens: Annotated[int, Field(ge=0)]

    @field_validator("total_tokens")
    @classmethod
    def total_matches_components(cls, value: int, info: object) -> int:
        data = getattr(info, "data", {})
        prompt = data.get("prompt_tokens")
        completion = data.get("completion_tokens")
        if prompt is not None and completion is not None and value != prompt + completion:
            raise ValueError("total_tokens must equal prompt_tokens + completion_tokens")
        return value


class CostEstimate(BaseModel):
    """Reproducible, explicitly estimated provider cost."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    currency: Literal["USD"] = "USD"
    amount: Annotated[float, Field(ge=0.0, le=1_000_000.0)]


class AnalysisResponseV1(BaseModel):
    """Accepted normalized analysis result."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    status: AnalysisStatus
    run_id: UUID
    model_id: NonEmptyString
    prompt_version: NonEmptyString
    schema_version: Literal["analysis-v1"]
    hypotheses: Annotated[tuple[Hypothesis, ...], Field(max_length=20)] = ()
    counter_evidence: Annotated[tuple[EvidenceNote, ...], Field(max_length=100)] = ()
    missing_evidence: Annotated[tuple[EvidenceNote, ...], Field(max_length=100)] = ()
    citations: Annotated[tuple[Citation, ...], Field(max_length=100)] = ()
    confidence: Annotated[float, Field(ge=0.0, le=1.0)] = 0.0
    usage: Usage
    cost_estimate: CostEstimate
    requested_tool_calls: Annotated[tuple[ToolIntent, ...], Field(max_length=20)] = ()

    @model_validator(mode="after")
    def terminal_state_is_coherent(self) -> AnalysisResponseV1:
        if self.status == AnalysisStatus.COMPLETE:
            if not self.hypotheses or not self.citations:
                raise ValueError("complete response requires hypotheses and citations")
            top_level = {
                (citation.evidence_id, citation.digest, citation.claim)
                for citation in self.citations
            }
            if any(not hypothesis.citations for hypothesis in self.hypotheses):
                raise ValueError("every complete hypothesis requires citations")
            if any(
                (citation.evidence_id, citation.digest, citation.claim) not in top_level
                for hypothesis in self.hypotheses
                for citation in hypothesis.citations
            ):
                raise ValueError("hypothesis citations must appear in response citations")
        if self.requested_tool_calls and self.status != AnalysisStatus.NEED_MORE_EVIDENCE:
            raise ValueError("tool intents require need_more_evidence status")
        if self.status == AnalysisStatus.NEED_MORE_EVIDENCE and not (
            self.requested_tool_calls or self.missing_evidence
        ):
            raise ValueError("need_more_evidence requires missing evidence or tool intents")
        return self


class ProblemDetails(BaseModel):
    """Stable error shape for callers and telemetry."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    type: Literal["about:blank"] = "about:blank"
    title: NonEmptyString
    status: Annotated[int, Field(ge=400, le=599)]
    code: NonEmptyString
    correlation_id: NonEmptyString
