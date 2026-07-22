"""Provider-neutral outbound port and sanitized failure contract."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum
from typing import Literal, Protocol
from uuid import UUID

from opsmind_ai_runtime.domain.analysis_contracts import AnalysisResponseV1


class ProviderErrorCategory(StrEnum):
    INVALID_REQUEST = "provider.invalid_request"
    UNAUTHORIZED = "provider.unauthorized"
    INSUFFICIENT_BALANCE = "provider.insufficient_balance"
    INVALID_RESPONSE = "provider.invalid_response"
    RATE_LIMITED = "provider.rate_limited"
    UNAVAILABLE = "provider.unavailable"
    INTERNAL = "provider.internal"
    DEADLINE_EXCEEDED = "provider.deadline_exceeded"


@dataclass(frozen=True, slots=True)
class ProviderError(RuntimeError):
    """Sanitized transport failure; provider response bodies are never retained."""

    category: ProviderErrorCategory
    status_code: int | None
    retryable: bool
    message: str

    def __str__(self) -> str:
        return self.message


class InvalidProviderResponse(ValueError):
    """Raised when an HTTP-200 provider payload is not safe to accept."""


@dataclass(frozen=True, slots=True)
class AnalysisAdapterContext:
    run_id: UUID
    prompt_version: str
    schema_version: Literal["analysis-v1"]
    model_id: str
    deadline_at: datetime
    max_completion_tokens: int
    input_cost_per_million_usd: float
    output_cost_per_million_usd: float


class AnalysisAdapter(Protocol):
    """Outbound model gateway used by the provider-neutral application service."""

    async def analyze(
        self,
        *,
        prompt: str,
        thinking: bool,
        user_id: str,
        context: AnalysisAdapterContext,
    ) -> AnalysisResponseV1: ...
