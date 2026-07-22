"""Durable-state port for replay, budget accounting, and invocation metadata."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol
from uuid import UUID, uuid4

from opsmind_ai_runtime.application.budget_guard import (
    BudgetAllowance,
    BudgetGuard,
    BudgetReservation,
)
from opsmind_ai_runtime.application.delegated_capability import CapabilityError, NonceStore
from opsmind_ai_runtime.domain.analysis_contracts import (
    AnalysisRequestV1,
    AnalysisResponseV1,
    DelegatedCapability,
)


class RuntimeStateUnavailable(RuntimeError):
    """Raised when authoritative replay/accounting state cannot be updated."""


@dataclass(frozen=True, slots=True)
class InvocationMetadata:
    """Secret-free metadata suitable for a durable invocation ledger."""

    invocation_id: UUID
    run_id: UUID
    incident_id: UUID
    tenant_id: UUID
    request_digest: str
    model_id: str
    prompt_version: str
    schema_version: str
    status: str
    total_tokens: int
    tool_calls: int
    cost_usd: float
    latency_ms: int
    provider_error_code: str | None


class InvocationAuditSink(Protocol):
    def append(self, metadata: InvocationMetadata) -> None: ...


@dataclass(frozen=True, slots=True)
class PrepareInvocation:
    request: AnalysisRequestV1
    capability: DelegatedCapability
    request_digest: str
    model_id: str
    provider: str
    estimated_input_tokens: int
    input_cost_per_token_usd: float
    output_cost_per_token_usd: float
    cost_limit_usd: float


@dataclass(frozen=True, slots=True)
class PreparedInvocation:
    invocation_id: UUID
    request: PrepareInvocation
    allowance: BudgetAllowance | None
    replay_response: AnalysisResponseV1 | None = None


class RuntimeStateStore(Protocol):
    async def prepare(self, command: PrepareInvocation) -> PreparedInvocation:
        """Consume nonce, replay success, or reserve one provider exchange."""

    async def complete(
        self,
        prepared: PreparedInvocation,
        response: AnalysisResponseV1,
        *,
        latency_ms: int,
    ) -> None:
        """Atomically commit usage and the validated normalized response."""

    async def fail(
        self,
        prepared: PreparedInvocation,
        *,
        error_code: str,
        provider_started: bool,
        latency_ms: int,
    ) -> None:
        """Release or conservatively charge an unsuccessful reservation."""


class InMemoryRuntimeStateStore:
    """Single-process compatibility store for offline tests and disabled mode."""

    def __init__(
        self,
        *,
        budget_guard: BudgetGuard,
        nonce_store: NonceStore,
        audit_sink: InvocationAuditSink | None = None,
    ) -> None:
        self._budget = budget_guard
        self._nonces = nonce_store
        self._audit = audit_sink
        self._completed: dict[tuple[UUID, UUID, str], AnalysisResponseV1] = {}

    async def prepare(self, command: PrepareInvocation) -> PreparedInvocation:
        if not self._nonces.consume(
            command.capability.nonce,
            expires_at=command.capability.expires_at,
        ):
            raise CapabilityError("delegated capability nonce was replayed")
        invocation_id = uuid4()
        replay = self._completed.get(
            (command.request.tenant_id, command.request.run_id, command.request_digest)
        )
        if replay is not None:
            return PreparedInvocation(invocation_id, command, None, replay)
        allowance = self._budget.reserve(
            BudgetReservation(
                run_id=command.request.run_id,
                token_budget=command.request.token_budget,
                tool_budget=command.request.tool_budget,
                cost_budget_usd=command.cost_limit_usd,
            ),
            estimated_tokens=command.estimated_input_tokens,
            input_cost_per_token_usd=command.input_cost_per_token_usd,
            output_cost_per_token_usd=command.output_cost_per_token_usd,
        )
        return PreparedInvocation(invocation_id, command, allowance)

    async def complete(
        self,
        prepared: PreparedInvocation,
        response: AnalysisResponseV1,
        *,
        latency_ms: int,
    ) -> None:
        self._budget.commit(
            prepared.request.request.run_id,
            tokens=response.usage.total_tokens,
            tools=len(response.requested_tool_calls),
            cost_usd=response.cost_estimate.amount,
        )
        key = (
            prepared.request.request.tenant_id,
            prepared.request.request.run_id,
            prepared.request.request_digest,
        )
        self._completed[key] = response
        self._append_metadata(
            prepared,
            status=response.status.value,
            total_tokens=response.usage.total_tokens,
            tool_calls=len(response.requested_tool_calls),
            cost_usd=response.cost_estimate.amount,
            latency_ms=latency_ms,
            error_code=None,
        )

    async def fail(
        self,
        prepared: PreparedInvocation,
        *,
        error_code: str,
        provider_started: bool,
        latency_ms: int,
    ) -> None:
        self._budget.cancel(
            prepared.request.request.run_id,
            charge_estimate=provider_started,
        )
        allowance = prepared.allowance
        charged_tokens = 0
        charged_cost = 0.0
        if provider_started and allowance is not None:
            charged_tokens = (
                prepared.request.estimated_input_tokens + allowance.max_completion_tokens
            )
            charged_cost = allowance.projected_cost_usd
        self._append_metadata(
            prepared,
            status="ambiguous" if provider_started else "failed",
            total_tokens=charged_tokens,
            tool_calls=0,
            cost_usd=charged_cost,
            latency_ms=latency_ms,
            error_code=error_code,
        )

    def _append_metadata(
        self,
        prepared: PreparedInvocation,
        *,
        status: str,
        total_tokens: int,
        tool_calls: int,
        cost_usd: float,
        latency_ms: int,
        error_code: str | None,
    ) -> None:
        if self._audit is None:
            return
        request = prepared.request.request
        self._audit.append(
            InvocationMetadata(
                invocation_id=prepared.invocation_id,
                run_id=request.run_id,
                incident_id=request.incident_id,
                tenant_id=request.tenant_id,
                request_digest=prepared.request.request_digest,
                model_id=prepared.request.model_id,
                prompt_version=request.prompt_version,
                schema_version=request.schema_version,
                status=status,
                total_tokens=total_tokens,
                tool_calls=tool_calls,
                cost_usd=cost_usd,
                latency_ms=latency_ms,
                provider_error_code=error_code,
            )
        )
