"""PostgreSQL nonce, replay, lease recovery, and reservation transactions."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from decimal import Decimal
from uuid import UUID, uuid4

from opsmind_ai_runtime.adapters.persistence.postgres_pool import (
    PostgresConnection,
    PostgresPool,
)
from opsmind_ai_runtime.application.budget_guard import (
    BudgetExceeded,
    BudgetReservation,
    calculate_allowance,
)
from opsmind_ai_runtime.application.delegated_capability import CapabilityError
from opsmind_ai_runtime.application.runtime_state import (
    PreparedInvocation,
    PrepareInvocation,
    RuntimeStateUnavailable,
)
from opsmind_ai_runtime.domain.analysis_contracts import AnalysisResponseV1


async def _set_tenant(connection: PostgresConnection, tenant_id: UUID) -> None:
    await connection.execute(
        "SELECT set_config('opsmind.ai_runtime_tenant_id', %s, true)",
        (str(tenant_id),),
    )


async def consume_nonce(
    pool: PostgresPool,
    command: PrepareInvocation,
    nonce_digest: str,
) -> None:
    async with pool.connection() as connection, connection.transaction():
        await _set_tenant(connection, command.request.tenant_id)
        cursor = await connection.execute(
            """
                INSERT INTO ai_runtime.capability_nonces (
                    nonce_digest, organization_id, run_id, request_digest, expires_at
                ) VALUES (%s, %s, %s, %s, %s)
                ON CONFLICT (nonce_digest) DO NOTHING
                RETURNING nonce_digest
                """,
            (
                nonce_digest,
                command.request.tenant_id,
                command.request.run_id,
                command.request_digest,
                command.capability.expires_at,
            ),
        )
        if await cursor.fetchone() is None:
            raise CapabilityError("delegated capability nonce was replayed")


async def prepare_invocation(
    pool: PostgresPool,
    command: PrepareInvocation,
    nonce_digest: str,
    *,
    lease_seconds: int,
    retention_days: int,
) -> PreparedInvocation:
    replay = await _recover_previous_invocation(pool, command)
    if replay is not None:
        return replay
    now = datetime.now(UTC)
    if command.request.deadline_at <= now:
        raise BudgetExceeded("request deadline cannot hold a reservation")
    # The provider call is globally bounded by request.deadline_at. A lease may
    # outlive that deadline, but must never expire while the call is legitimate.
    lease_expires_at = max(
        command.request.deadline_at,
        now + timedelta(seconds=lease_seconds),
    )
    invocation_id = uuid4()
    async with pool.connection() as connection, connection.transaction():
        await _set_tenant(connection, command.request.tenant_id)
        replay = await _find_replay(connection, command)
        if replay is not None:
            return replay
        await _ensure_budget_row(connection, command)
        budget = await _lock_budget_row(connection, command)
        # The row lock may have waited behind a successful completion. Recheck
        # replay after the lock so two replicas cannot call the provider twice.
        replay = await _find_replay(connection, command)
        if replay is not None:
            return replay
        if budget.get("active_invocation_id") is not None:
            raise BudgetExceeded("run already has an analysis exchange in flight")
        allowance = calculate_allowance(
            BudgetReservation(
                run_id=command.request.run_id,
                token_budget=command.request.token_budget,
                tool_budget=command.request.tool_budget,
                cost_budget_usd=command.cost_limit_usd,
            ),
            committed_tokens=_integer(budget, "committed_tokens"),
            committed_tools=_integer(budget, "committed_tools"),
            committed_cost_usd=float(_decimal(budget, "committed_cost_usd")),
            estimated_tokens=command.estimated_input_tokens,
            input_cost_per_token_usd=command.input_cost_per_token_usd,
            output_cost_per_token_usd=command.output_cost_per_token_usd,
        )
        reserved_tokens = command.estimated_input_tokens + allowance.max_completion_tokens
        reserved_cost = Decimal(str(allowance.projected_cost_usd))
        await connection.execute(
            """
                INSERT INTO ai_runtime.analysis_invocations (
                    invocation_id, organization_id, incident_id, run_id,
                    capability_nonce_digest, request_digest, provider, model_id,
                    prompt_version, schema_version, state, reserved_tokens,
                    reserved_cost_usd, request_deadline_at, lease_expires_at, retain_until
                ) VALUES (
                    %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
                    'reserved', %s, %s, %s, %s, %s
                )
                """,
            (
                invocation_id,
                command.request.tenant_id,
                command.request.incident_id,
                command.request.run_id,
                nonce_digest,
                command.request_digest,
                command.provider,
                command.model_id,
                command.request.prompt_version,
                command.request.schema_version,
                reserved_tokens,
                reserved_cost,
                command.request.deadline_at,
                lease_expires_at,
                now + timedelta(days=retention_days),
            ),
        )
        cursor = await connection.execute(
            """
                UPDATE ai_runtime.analysis_run_budgets
                   SET active_invocation_id = %s,
                       active_reserved_tokens = %s,
                       active_reserved_cost_usd = %s,
                       active_lease_expires_at = %s,
                       updated_at = transaction_timestamp()
                 WHERE organization_id = %s AND run_id = %s
                   AND active_invocation_id IS NULL
                """,
            (
                invocation_id,
                reserved_tokens,
                reserved_cost,
                lease_expires_at,
                command.request.tenant_id,
                command.request.run_id,
            ),
        )
        if cursor.rowcount != 1:
            raise RuntimeStateUnavailable("run budget reservation was not persisted")
    return PreparedInvocation(invocation_id, command, allowance)


async def _recover_previous_invocation(
    pool: PostgresPool,
    command: PrepareInvocation,
) -> PreparedInvocation | None:
    """Commit an expired lease independently from any new reservation failure."""

    async with pool.connection() as connection, connection.transaction():
        await _set_tenant(connection, command.request.tenant_id)
        replay = await _find_replay(connection, command)
        if replay is not None:
            return replay
        await _ensure_budget_row(connection, command)
        budget = await _lock_budget_row(connection, command)
        await _recover_expired_reservation(connection, budget, datetime.now(UTC))
    return None


async def _find_replay(
    connection: PostgresConnection,
    command: PrepareInvocation,
) -> PreparedInvocation | None:
    cursor = await connection.execute(
        """
        SELECT invocation_id, response_payload
          FROM ai_runtime.analysis_invocations
         WHERE organization_id = %s AND run_id = %s
           AND request_digest = %s AND state = 'succeeded'
        """,
        (command.request.tenant_id, command.request.run_id, command.request_digest),
    )
    row = await cursor.fetchone()
    if row is None:
        return None
    invocation_id = _uuid(row, "invocation_id")
    response = AnalysisResponseV1.model_validate(row.get("response_payload"))
    return PreparedInvocation(invocation_id, command, None, response)


async def _ensure_budget_row(
    connection: PostgresConnection,
    command: PrepareInvocation,
) -> None:
    await connection.execute(
        """
        INSERT INTO ai_runtime.analysis_run_budgets (
            organization_id, incident_id, run_id, token_limit, tool_limit, cost_limit_usd
        ) VALUES (%s, %s, %s, %s, %s, %s)
        ON CONFLICT (organization_id, run_id) DO NOTHING
        """,
        (
            command.request.tenant_id,
            command.request.incident_id,
            command.request.run_id,
            command.request.token_budget,
            command.request.tool_budget,
            Decimal(str(command.cost_limit_usd)),
        ),
    )


async def _lock_budget_row(
    connection: PostgresConnection,
    command: PrepareInvocation,
) -> dict[str, object]:
    cursor = await connection.execute(
        """
        SELECT * FROM ai_runtime.analysis_run_budgets
         WHERE organization_id = %s AND run_id = %s
         FOR UPDATE
        """,
        (command.request.tenant_id, command.request.run_id),
    )
    row = await cursor.fetchone()
    if row is None:
        raise RuntimeStateUnavailable("run budget row is unavailable")
    if (
        _uuid(row, "incident_id") != command.request.incident_id
        or _integer(row, "token_limit") != command.request.token_budget
        or _integer(row, "tool_limit") != command.request.tool_budget
        or _decimal(row, "cost_limit_usd") != Decimal(str(command.cost_limit_usd))
    ):
        raise BudgetExceeded("run budget cannot change during an exchange")
    return row


async def _recover_expired_reservation(
    connection: PostgresConnection,
    row: dict[str, object],
    now: datetime,
) -> dict[str, object]:
    active_id = row.get("active_invocation_id")
    if active_id is None:
        return row
    lease_expires_at = row.get("active_lease_expires_at")
    if not isinstance(lease_expires_at, datetime):
        raise RuntimeStateUnavailable("active reservation lease is invalid")
    if lease_expires_at > now:
        raise BudgetExceeded("run already has an analysis exchange in flight")
    invocation_id = _uuid(row, "active_invocation_id")
    reserved_tokens = _integer(row, "active_reserved_tokens")
    reserved_cost = _decimal(row, "active_reserved_cost_usd")
    invocation_cursor = await connection.execute(
        """
        UPDATE ai_runtime.analysis_invocations
           SET state = 'ambiguous', provider_error_code = 'runtime.lease_expired',
               latency_ms = greatest(0, floor(extract(epoch FROM
                   (transaction_timestamp() - started_at)) * 1000)::integer),
               finished_at = transaction_timestamp()
         WHERE invocation_id = %s AND state = 'reserved'
        """,
        (invocation_id,),
    )
    if invocation_cursor.rowcount != 1:
        raise RuntimeStateUnavailable("expired invocation was not reserved")
    budget_cursor = await connection.execute(
        """
        UPDATE ai_runtime.analysis_run_budgets
           SET committed_tokens = committed_tokens + active_reserved_tokens,
               committed_cost_usd = committed_cost_usd + active_reserved_cost_usd,
               active_invocation_id = NULL, active_reserved_tokens = NULL,
               active_reserved_cost_usd = NULL, active_lease_expires_at = NULL,
               updated_at = transaction_timestamp()
         WHERE organization_id = %s AND run_id = %s
           AND active_invocation_id = %s
        """,
        (row["organization_id"], row["run_id"], invocation_id),
    )
    if budget_cursor.rowcount != 1:
        raise RuntimeStateUnavailable("expired reservation was not charged")
    row["committed_tokens"] = _integer(row, "committed_tokens") + reserved_tokens
    row["committed_cost_usd"] = _decimal(row, "committed_cost_usd") + reserved_cost
    row["active_invocation_id"] = None
    return row


def _integer(row: dict[str, object], name: str) -> int:
    value = row.get(name)
    if not isinstance(value, int) or isinstance(value, bool):
        raise RuntimeStateUnavailable(f"invalid integer state: {name}")
    return value


def _decimal(row: dict[str, object], name: str) -> Decimal:
    value = row.get(name)
    if not isinstance(value, Decimal):
        raise RuntimeStateUnavailable(f"invalid decimal state: {name}")
    return value


def _uuid(row: dict[str, object], name: str) -> UUID:
    value = row.get(name)
    if not isinstance(value, UUID):
        raise RuntimeStateUnavailable(f"invalid UUID state: {name}")
    return value
