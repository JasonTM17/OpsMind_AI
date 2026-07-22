"""Synthetic startup probe for the configured DeepSeek model contract."""

from __future__ import annotations

import asyncio
import json
from contextlib import suppress
from dataclasses import dataclass
from typing import Protocol
from uuid import UUID, uuid4

from opsmind_ai_runtime.application.analysis_service import AnalysisFailure
from opsmind_ai_runtime.application.provider_gateway import (
    InvalidProviderResponse,
    ProviderError,
)
from opsmind_ai_runtime.domain.analysis_contracts import AnalysisRequestV1, AnalysisResponseV1
from opsmind_ai_runtime.providers.deepseek.client import DeepSeekClient


class ProviderCapabilityProbeError(RuntimeError):
    """Raised without raw provider content when the startup contract is not proven."""


@dataclass(frozen=True, slots=True)
class ProbeUsage:
    """Validated provider usage from one synthetic capability exchange."""

    prompt_tokens: int
    completion_tokens: int
    total_tokens: int


class CapabilityProbeAuditSink(Protocol):
    async def record_started(self, probe_id: UUID) -> bool: ...

    async def record_finished(
        self,
        probe_id: UUID,
        *,
        succeeded: bool,
        usage: ProbeUsage | None,
        failure_code: str | None = None,
    ) -> None: ...


class AnalysisExecutor(Protocol):
    async def analyze(
        self, request: AnalysisRequestV1, *, capability_token: str
    ) -> AnalysisResponseV1: ...


@dataclass(slots=True)
class ProviderCapabilityState:
    """Process-local gate; provider traffic is impossible before probe success."""

    provider_ready: bool = False
    database_ready: bool = False

    @property
    def ready(self) -> bool:
        return self.provider_ready and self.database_ready

    def mark_ready(self) -> None:
        self.provider_ready = True
        self.database_ready = True

    def mark_unready(self) -> None:
        self.provider_ready = False
        self.database_ready = False

    def mark_provider_ready(self) -> None:
        self.provider_ready = True

    def mark_provider_unready(self) -> None:
        self.provider_ready = False

    def mark_database_ready(self) -> None:
        self.database_ready = True

    def mark_database_unready(self) -> None:
        self.database_ready = False

    def health_status(self) -> str:
        return "ok" if self.ready else "degraded"


class StartupGatedAnalysisService:
    """Reject analysis while the provider/model capability is unproven."""

    def __init__(self, delegate: AnalysisExecutor, state: ProviderCapabilityState) -> None:
        self._delegate = delegate
        self._state = state

    async def analyze(
        self, request: AnalysisRequestV1, *, capability_token: str
    ) -> AnalysisResponseV1:
        if not self._state.ready:
            raise AnalysisFailure("provider.unavailable", "analysis runtime is unavailable")
        return await self._delegate.analyze(request, capability_token=capability_token)


class DeepSeekCapabilityProbe:
    """Prove exact model, JSON output, thinking, terminal response, and sane usage."""

    _EXPECTED = {"opsmind_probe": "ok"}

    def __init__(
        self,
        client: DeepSeekClient,
        *,
        model: str,
        timeout_seconds: float,
        audit_sink: CapabilityProbeAuditSink | None = None,
    ) -> None:
        self._client = client
        self._model = model
        self._timeout_seconds = min(timeout_seconds, 10.0)
        self._audit_sink = audit_sink

    async def verify(self) -> ProbeUsage:
        probe_id = uuid4()
        if self._audit_sink is not None:
            try:
                started = await self._audit_sink.record_started(probe_id)
            except Exception as exc:
                raise ProviderCapabilityProbeError("provider probe audit is unavailable") from exc
            if not started:
                raise ProviderCapabilityProbeError("provider capability probe quota is exhausted")
        try:
            raw = await self._client.complete(
                prompt=(
                    "Think briefly, then return a JSON object only. "
                    'The exact JSON value must be {"opsmind_probe":"ok"}.'
                ),
                thinking=True,
                user_id="opsmind:startup-capability-probe",
                max_tokens=64,
                timeout_seconds=self._timeout_seconds,
            )
            usage = self._validate(raw)
        except (
            ProviderError,
            InvalidProviderResponse,
            KeyError,
            IndexError,
            TypeError,
            ValueError,
        ) as exc:
            if self._audit_sink is not None:
                try:
                    await self._audit_sink.record_finished(
                        probe_id,
                        succeeded=False,
                        usage=None,
                        failure_code="provider_capability_probe_failed",
                    )
                except Exception as audit_exc:
                    raise ProviderCapabilityProbeError("provider probe audit failed") from audit_exc
            raise ProviderCapabilityProbeError("provider capability probe failed") from exc
        except asyncio.CancelledError:
            if self._audit_sink is not None:
                with suppress(Exception):
                    await asyncio.shield(
                        self._audit_sink.record_finished(
                            probe_id,
                            succeeded=False,
                            usage=None,
                            failure_code="provider_capability_probe_cancelled",
                        )
                    )
            raise
        if self._audit_sink is not None:
            try:
                await self._audit_sink.record_finished(
                    probe_id,
                    succeeded=True,
                    usage=usage,
                )
            except Exception as exc:
                raise ProviderCapabilityProbeError("provider probe audit failed") from exc
        return usage

    def _validate(self, raw: dict[str, object]) -> ProbeUsage:
        if raw.get("model") != self._model:
            raise InvalidProviderResponse("provider probe returned an unexpected model")
        choices = raw.get("choices")
        if not isinstance(choices, list) or len(choices) != 1:
            raise InvalidProviderResponse("provider probe choices are invalid")
        choice = choices[0]
        if not isinstance(choice, dict) or choice.get("finish_reason") != "stop":
            raise InvalidProviderResponse("provider probe is not terminal")
        message = choice.get("message")
        if not isinstance(message, dict):
            raise InvalidProviderResponse("provider probe message is invalid")
        reasoning = message.get("reasoning_content")
        if not isinstance(reasoning, str) or not reasoning.strip():
            raise InvalidProviderResponse("provider probe did not prove thinking support")
        content = message.get("content")
        if not isinstance(content, str) or json.loads(content) != self._EXPECTED:
            raise InvalidProviderResponse("provider probe did not prove JSON output")
        usage = raw.get("usage")
        if not isinstance(usage, dict):
            raise InvalidProviderResponse("provider probe usage is invalid")
        prompt = usage.get("prompt_tokens")
        completion = usage.get("completion_tokens")
        total = usage.get("total_tokens")
        if (
            type(prompt) is not int
            or type(completion) is not int
            or type(total) is not int
            or prompt < 0
            or completion < 0
            or total != prompt + completion
        ):
            raise InvalidProviderResponse("provider probe usage is incoherent")
        if total > 1_024:
            raise InvalidProviderResponse("provider probe usage exceeded the audit bound")
        return ProbeUsage(prompt, completion, total)
