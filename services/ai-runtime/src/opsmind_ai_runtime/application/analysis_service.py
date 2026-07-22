"""Bounded analysis orchestration and invocation metadata."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from time import monotonic

from opsmind_ai_runtime.application.admission_gate import AdmissionGate
from opsmind_ai_runtime.application.budget_guard import BudgetExceeded, BudgetGuard
from opsmind_ai_runtime.application.delegated_capability import (
    CapabilityError,
    CapabilityVerifier,
    InMemoryNonceStore,
    NonceStore,
    analysis_request_digest,
    validate_capability,
)
from opsmind_ai_runtime.application.egress_policy import EgressDenied, evaluate_egress
from opsmind_ai_runtime.application.provider_gateway import (
    AnalysisAdapter,
    AnalysisAdapterContext,
    InvalidProviderResponse,
    ProviderError,
)
from opsmind_ai_runtime.application.runtime_state import (
    InMemoryRuntimeStateStore,
    InvocationAuditSink,
    PreparedInvocation,
    PrepareInvocation,
    RuntimeStateStore,
    RuntimeStateUnavailable,
)
from opsmind_ai_runtime.application.tenant_egress_policy import TenantEgressPolicy
from opsmind_ai_runtime.config.settings import RuntimeSettings
from opsmind_ai_runtime.domain.analysis_contracts import (
    AnalysisRequestV1,
    AnalysisResponseV1,
    DelegatedCapability,
)


class AnalysisFailure(RuntimeError):
    """Stable application failure; raw provider details never escape."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class UnavailableAnalysisService:
    """Fail-closed executor used when provider/delegation prerequisites are absent."""

    def __init__(self, code: str = "provider.unavailable") -> None:
        self._code = code

    async def analyze(
        self, request: AnalysisRequestV1, *, capability_token: str
    ) -> AnalysisResponseV1:
        _ = (request, capability_token)
        raise AnalysisFailure(self._code, "analysis runtime is unavailable")


def _validate_response_scope(request: AnalysisRequestV1, response: AnalysisResponseV1) -> None:
    if response.run_id != request.run_id:
        raise InvalidProviderResponse("provider response run scope mismatch")
    authorized = {(reference.evidence_id, reference.digest) for reference in request.context_refs}
    returned_citations = (
        *response.citations,
        *(citation for hypothesis in response.hypotheses for citation in hypothesis.citations),
    )
    if any(
        (citation.evidence_id, citation.digest) not in authorized for citation in returned_citations
    ):
        raise InvalidProviderResponse("provider cited evidence outside authorized context")


class AnalysisService:
    def __init__(
        self,
        *,
        settings: RuntimeSettings,
        verifier: CapabilityVerifier,
        adapter: AnalysisAdapter,
        egress_policy: TenantEgressPolicy,
        budget_guard: BudgetGuard | None = None,
        audit_sink: InvocationAuditSink | None = None,
        nonce_store: NonceStore | None = None,
        state_store: RuntimeStateStore | None = None,
        expected_issuer: str = "opsmind-platform-api",
        expected_audience: str = "opsmind-ai-runtime",
    ) -> None:
        self._settings = settings
        self._verifier = verifier
        self._adapter = adapter
        self._egress_policy = egress_policy
        if state_store is None:
            if budget_guard is None:
                raise ValueError("budget_guard is required for in-memory runtime state")
            state_store = InMemoryRuntimeStateStore(
                budget_guard=budget_guard,
                nonce_store=nonce_store or InMemoryNonceStore(),
                audit_sink=audit_sink,
            )
        self._state = state_store
        self._admission = AdmissionGate(settings.max_concurrent_requests)
        self._concurrency = asyncio.Semaphore(settings.max_concurrent_requests)
        self._expected_issuer = expected_issuer
        self._expected_audience = expected_audience

    async def analyze(
        self, request: AnalysisRequestV1, *, capability_token: str
    ) -> AnalysisResponseV1:
        capability = self._verifier.verify(capability_token)
        if capability is None:
            raise AnalysisFailure("delegation.invalid", "delegated capability rejected")
        if request.deadline_at <= datetime.now(UTC):
            raise AnalysisFailure("request.deadline_exceeded", "analysis deadline has passed")
        try:
            validate_capability(
                capability,
                request,
                expected_issuer=self._expected_issuer,
                expected_audience=self._expected_audience,
                max_lifetime=timedelta(seconds=self._settings.capability_max_lifetime_seconds),
            )
        except CapabilityError as exc:
            raise AnalysisFailure("delegation.invalid", "delegated capability rejected") from exc
        if not self._admission.try_acquire():
            raise AnalysisFailure("runtime.overloaded", "analysis admission capacity exhausted")
        try:
            return await self._analyze_admitted(request, capability=capability)
        finally:
            self._admission.release()

    async def _analyze_admitted(
        self, request: AnalysisRequestV1, *, capability: DelegatedCapability
    ) -> AnalysisResponseV1:
        try:
            egress = evaluate_egress(request, self._settings, self._egress_policy)
        except EgressDenied as exc:
            raise AnalysisFailure("egress.denied", "analysis egress denied") from exc
        # UTF-8 bytes are a conservative tokenizer-independent upper bound for
        # provider input tokens; underestimating here would defeat a hard cap.
        estimated_tokens = max(1, len(egress.sanitized_prompt.encode("utf-8")))
        try:
            prepared = await self._state.prepare(
                PrepareInvocation(
                    request=request,
                    capability=capability,
                    request_digest=analysis_request_digest(request),
                    model_id=self._settings.model,
                    provider=self._settings.provider,
                    estimated_input_tokens=estimated_tokens,
                    input_cost_per_token_usd=float(self._settings.input_cost_per_million_usd)
                    / 1_000_000,
                    output_cost_per_token_usd=float(self._settings.output_cost_per_million_usd)
                    / 1_000_000,
                    cost_limit_usd=float(self._settings.max_cost_usd_per_run),
                )
            )
        except CapabilityError as exc:
            raise AnalysisFailure("delegation.invalid", "delegated capability rejected") from exc
        except BudgetExceeded as exc:
            raise AnalysisFailure("budget.exceeded", "analysis budget exceeded") from exc
        except RuntimeStateUnavailable as exc:
            raise AnalysisFailure(
                "runtime.state_unavailable", "analysis state unavailable"
            ) from exc
        if prepared.replay_response is not None:
            try:
                _validate_response_scope(request, prepared.replay_response)
            except InvalidProviderResponse as exc:
                raise AnalysisFailure(
                    "provider.invalid_response", "stored analysis response rejected"
                ) from exc
            return prepared.replay_response
        if prepared.allowance is None:
            raise AnalysisFailure("runtime.state_unavailable", "analysis state unavailable")

        acquired = False
        provider_started = False
        provider_started_at = monotonic()
        try:
            remaining = (request.deadline_at - datetime.now(UTC)).total_seconds()
            if remaining <= 0:
                raise TimeoutError
            await asyncio.wait_for(self._concurrency.acquire(), timeout=remaining)
            acquired = True
            if capability.expires_at <= datetime.now(UTC):
                raise CapabilityError("delegated capability expired while queued")
            provider_started = True
            response = await self._adapter.analyze(
                prompt=egress.sanitized_prompt,
                thinking=request.analysis_mode.value == "investigate",
                user_id=f"run:{request.run_id}",
                context=AnalysisAdapterContext(
                    run_id=request.run_id,
                    prompt_version=request.prompt_version,
                    schema_version=request.schema_version,
                    model_id=self._settings.model,
                    deadline_at=request.deadline_at,
                    max_completion_tokens=prepared.allowance.max_completion_tokens,
                    input_cost_per_million_usd=float(self._settings.input_cost_per_million_usd),
                    output_cost_per_million_usd=float(self._settings.output_cost_per_million_usd),
                ),
            )
            _validate_response_scope(request, response)
        except TimeoutError as exc:
            await self._fail_prepared(
                prepared,
                error_code="request.deadline_exceeded",
                provider_started=provider_started,
                started_at=provider_started_at,
            )
            raise AnalysisFailure(
                "request.deadline_exceeded", "analysis deadline has passed"
            ) from exc
        except CapabilityError as exc:
            await self._fail_prepared(
                prepared,
                error_code="delegation.invalid",
                provider_started=False,
                started_at=provider_started_at,
            )
            raise AnalysisFailure("delegation.invalid", "delegated capability rejected") from exc
        except ProviderError as exc:
            await self._fail_prepared(
                prepared,
                error_code=exc.category.value,
                provider_started=provider_started,
                started_at=provider_started_at,
            )
            raise AnalysisFailure(exc.category.value, "provider unavailable") from exc
        except InvalidProviderResponse as exc:
            await self._fail_prepared(
                prepared,
                error_code="provider.invalid_response",
                provider_started=provider_started,
                started_at=provider_started_at,
            )
            raise AnalysisFailure(
                "provider.invalid_response", "provider response rejected"
            ) from exc
        except asyncio.CancelledError:
            await self._fail_prepared(
                prepared,
                error_code="request.cancelled",
                provider_started=provider_started,
                started_at=provider_started_at,
            )
            raise
        except Exception as exc:
            await self._fail_prepared(
                prepared,
                error_code="runtime.unexpected_failure",
                provider_started=provider_started,
                started_at=provider_started_at,
            )
            raise AnalysisFailure(
                "runtime.unexpected_failure", "analysis runtime failed unexpectedly"
            ) from exc
        finally:
            if acquired:
                self._concurrency.release()
        try:
            await self._state.complete(
                prepared,
                response,
                latency_ms=_elapsed_ms(provider_started_at),
            )
        except BudgetExceeded as exc:
            raise AnalysisFailure("budget.exceeded", "analysis budget exceeded") from exc
        except RuntimeStateUnavailable as exc:
            raise AnalysisFailure(
                "runtime.state_unavailable", "analysis state unavailable"
            ) from exc
        return response

    async def _fail_prepared(
        self,
        prepared: PreparedInvocation,
        *,
        error_code: str,
        provider_started: bool,
        started_at: float,
    ) -> None:
        try:
            await self._state.fail(
                prepared,
                error_code=error_code,
                provider_started=provider_started,
                latency_ms=_elapsed_ms(started_at),
            )
        except RuntimeStateUnavailable as exc:
            raise AnalysisFailure(
                "runtime.state_unavailable", "analysis state unavailable"
            ) from exc


def _elapsed_ms(started_at: float) -> int:
    return max(0, int((monotonic() - started_at) * 1_000))
