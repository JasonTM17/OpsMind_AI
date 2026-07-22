"""Psycopg implementation of authoritative AI runtime state."""

from __future__ import annotations

import hashlib

from psycopg import Error as PsycopgError
from psycopg_pool import PoolClosed, PoolTimeout, TooManyRequests

from opsmind_ai_runtime.adapters.persistence.postgres_pool import PostgresPool
from opsmind_ai_runtime.adapters.persistence.postgres_runtime_state_finish import (
    complete_invocation,
    fail_invocation,
)
from opsmind_ai_runtime.adapters.persistence.postgres_runtime_state_prepare import (
    consume_nonce,
    prepare_invocation,
)
from opsmind_ai_runtime.application.budget_guard import BudgetExceeded
from opsmind_ai_runtime.application.delegated_capability import CapabilityError
from opsmind_ai_runtime.application.runtime_state import (
    PreparedInvocation,
    PrepareInvocation,
    RuntimeStateUnavailable,
)
from opsmind_ai_runtime.domain.analysis_contracts import AnalysisResponseV1


class PostgresRuntimeStateStore:
    """Shared nonce, replay, lease, and cumulative-budget authority."""

    def __init__(
        self,
        pool: PostgresPool,
        *,
        lease_seconds: int,
        retention_days: int,
    ) -> None:
        self._pool = pool
        self._lease_seconds = lease_seconds
        self._retention_days = retention_days

    async def prepare(self, command: PrepareInvocation) -> PreparedInvocation:
        nonce_digest = _secret_digest(command.capability.nonce)
        try:
            # Capabilities authorize one attempt, not one successful reservation.
            # Commit consumption first so a crash can burn authority but can never
            # make the bearer reusable; the nonce row remains the attempt record.
            await consume_nonce(self._pool, command, nonce_digest)
            return await prepare_invocation(
                self._pool,
                command,
                nonce_digest,
                lease_seconds=self._lease_seconds,
                retention_days=self._retention_days,
            )
        except (CapabilityError, BudgetExceeded):
            raise
        except _DATABASE_FAILURES as exc:
            raise RuntimeStateUnavailable("durable runtime state is unavailable") from exc

    async def complete(
        self,
        prepared: PreparedInvocation,
        response: AnalysisResponseV1,
        *,
        latency_ms: int,
    ) -> None:
        try:
            over_budget = await complete_invocation(
                self._pool,
                prepared,
                response,
                latency_ms=latency_ms,
            )
        except _DATABASE_FAILURES as exc:
            raise RuntimeStateUnavailable("durable runtime state is unavailable") from exc
        if over_budget:
            raise BudgetExceeded("provider usage exceeded hard budget")

    async def fail(
        self,
        prepared: PreparedInvocation,
        *,
        error_code: str,
        provider_started: bool,
        latency_ms: int,
    ) -> None:
        try:
            await fail_invocation(
                self._pool,
                prepared,
                error_code=error_code,
                provider_started=provider_started,
                latency_ms=latency_ms,
            )
        except _DATABASE_FAILURES as exc:
            raise RuntimeStateUnavailable("durable runtime state is unavailable") from exc


_DATABASE_FAILURES = (
    PsycopgError,
    PoolClosed,
    PoolTimeout,
    TooManyRequests,
    KeyError,
    TypeError,
    ValueError,
    RuntimeStateUnavailable,
)


def _secret_digest(value: str) -> str:
    return f"sha256:{hashlib.sha256(value.encode('utf-8')).hexdigest()}"
