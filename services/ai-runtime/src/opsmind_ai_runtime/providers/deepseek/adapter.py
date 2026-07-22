"""DeepSeek response adapter into the application-owned analysis contract."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from opsmind_ai_runtime.application.provider_gateway import (
    AnalysisAdapterContext,
    InvalidProviderResponse,
)
from opsmind_ai_runtime.domain.analysis_contracts import (
    AnalysisResponseV1,
    AnalysisStatus,
    Citation,
    CostEstimate,
    Hypothesis,
    ToolIntent,
    Usage,
)
from opsmind_ai_runtime.providers.deepseek.client import DeepSeekClient


class _StructuredAnalysis(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    status: Literal["complete", "need_more_evidence", "abstain"]
    hypotheses: Annotated[tuple[Hypothesis, ...], Field(max_length=20)]
    counter_evidence: Annotated[tuple[str, ...], Field(max_length=100)]
    missing_evidence: Annotated[tuple[str, ...], Field(max_length=100)]
    citations: Annotated[tuple[Citation, ...], Field(max_length=100)]
    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    requested_tool_calls: Annotated[tuple[ToolIntent, ...], Field(max_length=20)] = ()


def _usage(raw: object) -> Usage:
    if not isinstance(raw, dict):
        raise InvalidProviderResponse("provider usage must be an object")

    def non_negative_integer(name: str) -> int:
        value = raw.get(name)
        if type(value) is not int or value < 0:
            raise InvalidProviderResponse("provider usage must contain non-negative integers")
        return value

    prompt_tokens = non_negative_integer("prompt_tokens")
    completion_tokens = non_negative_integer("completion_tokens")
    total_tokens = non_negative_integer("total_tokens")
    return Usage(
        prompt_tokens=prompt_tokens,
        completion_tokens=completion_tokens,
        total_tokens=total_tokens,
    )


class DeepSeekAdapter:
    """Normalize DeepSeek chat completions without exposing provider objects."""

    def __init__(self, client: DeepSeekClient) -> None:
        self._client = client

    async def analyze(
        self, *, prompt: str, thinking: bool, user_id: str, context: AnalysisAdapterContext
    ) -> AnalysisResponseV1:
        remaining = (context.deadline_at - datetime.now(UTC)).total_seconds()
        raw = await self._client.complete(
            prompt=prompt,
            thinking=thinking,
            user_id=user_id,
            max_tokens=context.max_completion_tokens,
            timeout_seconds=remaining,
        )
        try:
            choice = raw["choices"][0]
            message = choice["message"]
            if not isinstance(choice, dict) or not isinstance(message, dict):
                raise InvalidProviderResponse("provider response envelope is invalid")
            if choice.get("finish_reason") not in {"stop", "tool_calls"}:
                raise InvalidProviderResponse("provider response is not terminal")
            if raw.get("model") != context.model_id:
                raise InvalidProviderResponse("provider returned an unexpected model")
            content = message.get("content")
            if not isinstance(content, str) or not content.strip():
                raise InvalidProviderResponse("provider returned empty structured content")
            # Never read or persist reasoning_content; it is intentionally ignored.
            normalized = _StructuredAnalysis.model_validate_json(content)
            usage = _usage(raw.get("usage", {}))
            response = AnalysisResponseV1(
                status=AnalysisStatus(normalized.status),
                run_id=context.run_id,
                model_id=context.model_id,
                prompt_version=context.prompt_version,
                schema_version=context.schema_version,
                hypotheses=normalized.hypotheses,
                counter_evidence=normalized.counter_evidence,
                missing_evidence=normalized.missing_evidence,
                citations=normalized.citations,
                confidence=normalized.confidence,
                usage=usage,
                cost_estimate=CostEstimate(
                    amount=(
                        usage.prompt_tokens * context.input_cost_per_million_usd
                        + usage.completion_tokens * context.output_cost_per_million_usd
                    )
                    / 1_000_000,
                ),
                requested_tool_calls=normalized.requested_tool_calls,
            )
            return response
        except (
            KeyError,
            IndexError,
            TypeError,
            ValueError,
            ValidationError,
        ) as exc:
            if isinstance(exc, InvalidProviderResponse):
                raise
            raise InvalidProviderResponse("provider response failed schema validation") from exc
