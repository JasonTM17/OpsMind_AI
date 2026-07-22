"""PostgreSQL invocation completion and failure transactions."""

from __future__ import annotations

from decimal import Decimal

from psycopg.types.json import Jsonb

from opsmind_ai_runtime.adapters.persistence.postgres_pool import (
    PostgresConnection,
    PostgresPool,
)
from opsmind_ai_runtime.application.runtime_state import (
    PreparedInvocation,
    RuntimeStateUnavailable,
)
from opsmind_ai_runtime.domain.analysis_contracts import AnalysisResponseV1


async def _set_tenant(connection: PostgresConnection, prepared: PreparedInvocation) -> None:
    await connection.execute(
        "SELECT set_config('opsmind.ai_runtime_tenant_id', %s, true)",
        (str(prepared.request.request.tenant_id),),
    )


async def complete_invocation(
    pool: PostgresPool,
    prepared: PreparedInvocation,
    response: AnalysisResponseV1,
    *,
    latency_ms: int,
) -> bool:
    """Commit a success; return true when provider usage violated its reservation."""

    async with pool.connection() as connection, connection.transaction():
        await _set_tenant(connection, prepared)
        budget = await _lock_budget(connection, prepared)
        _require_active_invocation(budget, prepared)
        reported_cost = Decimal(str(response.cost_estimate.amount))
        over_budget = (
            response.usage.total_tokens > _integer(budget, "active_reserved_tokens")
            or len(response.requested_tool_calls)
            > _integer(budget, "tool_limit") - _integer(budget, "committed_tools")
            or reported_cost > _decimal(budget, "active_reserved_cost_usd")
        )
        if over_budget:
            await _charge_reported_usage_and_clear(
                connection,
                prepared,
                tokens=response.usage.total_tokens,
                tools=len(response.requested_tool_calls),
                cost_usd=reported_cost,
            )
            invocation_cursor = await connection.execute(
                """
                    UPDATE ai_runtime.analysis_invocations
                       SET state = 'failed',
                           provider_error_code = 'budget.provider_usage_exceeded',
                           actual_tokens = %s, actual_tools = %s, actual_cost_usd = %s,
                           latency_ms = %s, finished_at = transaction_timestamp()
                     WHERE invocation_id = %s AND state = 'reserved'
                    """,
                (
                    response.usage.total_tokens,
                    len(response.requested_tool_calls),
                    reported_cost,
                    latency_ms,
                    prepared.invocation_id,
                ),
            )
            _require_single_update(
                invocation_cursor.rowcount,
                "over-budget invocation was not finalized",
            )
            return True
        budget_cursor = await connection.execute(
            """
                UPDATE ai_runtime.analysis_run_budgets
                   SET committed_tokens = committed_tokens + %s,
                       committed_tools = committed_tools + %s,
                       committed_cost_usd = committed_cost_usd + %s,
                       active_invocation_id = NULL, active_reserved_tokens = NULL,
                       active_reserved_cost_usd = NULL, active_lease_expires_at = NULL,
                       updated_at = transaction_timestamp()
                 WHERE organization_id = %s AND run_id = %s
                   AND active_invocation_id = %s
                """,
            (
                response.usage.total_tokens,
                len(response.requested_tool_calls),
                reported_cost,
                prepared.request.request.tenant_id,
                prepared.request.request.run_id,
                prepared.invocation_id,
            ),
        )
        _require_single_update(
            budget_cursor.rowcount,
            "successful invocation budget was not committed",
        )
        invocation_cursor = await connection.execute(
            """
                UPDATE ai_runtime.analysis_invocations
                   SET state = 'succeeded', response_status = %s, response_payload = %s,
                       actual_tokens = %s, actual_tools = %s, actual_cost_usd = %s,
                       latency_ms = %s, finished_at = transaction_timestamp()
                 WHERE invocation_id = %s AND state = 'reserved'
                """,
            (
                response.status.value,
                Jsonb(response.model_dump(mode="json")),
                response.usage.total_tokens,
                len(response.requested_tool_calls),
                reported_cost,
                latency_ms,
                prepared.invocation_id,
            ),
        )
        _require_single_update(
            invocation_cursor.rowcount,
            "successful invocation was not finalized",
        )
    return False


async def fail_invocation(
    pool: PostgresPool,
    prepared: PreparedInvocation,
    *,
    error_code: str,
    provider_started: bool,
    latency_ms: int,
) -> None:
    async with pool.connection() as connection, connection.transaction():
        await _set_tenant(connection, prepared)
        budget = await _lock_budget(connection, prepared)
        active = budget.get("active_invocation_id")
        if active is None:
            await _require_terminal_invocation(connection, prepared)
            return
        _require_active_invocation(budget, prepared)
        if provider_started:
            await _charge_reservation_and_clear(connection, prepared)
        else:
            await _clear_reservation(connection, prepared)
        invocation_cursor = await connection.execute(
            """
                UPDATE ai_runtime.analysis_invocations
                   SET state = %s, provider_error_code = %s, latency_ms = %s,
                       finished_at = transaction_timestamp()
                 WHERE invocation_id = %s AND state = 'reserved'
                """,
            (
                "ambiguous" if provider_started else "failed",
                error_code,
                latency_ms,
                prepared.invocation_id,
            ),
        )
        _require_single_update(
            invocation_cursor.rowcount,
            "failed invocation was not finalized",
        )


async def _lock_budget(
    connection: PostgresConnection,
    prepared: PreparedInvocation,
) -> dict[str, object]:
    cursor = await connection.execute(
        """
        SELECT * FROM ai_runtime.analysis_run_budgets
         WHERE organization_id = %s AND run_id = %s
         FOR UPDATE
        """,
        (prepared.request.request.tenant_id, prepared.request.request.run_id),
    )
    row = await cursor.fetchone()
    if row is None:
        raise RuntimeStateUnavailable("run budget row is unavailable")
    return row


def _require_active_invocation(
    budget: dict[str, object],
    prepared: PreparedInvocation,
) -> None:
    if budget.get("active_invocation_id") != prepared.invocation_id:
        raise RuntimeStateUnavailable("active invocation does not match reservation")


async def _charge_reservation_and_clear(
    connection: PostgresConnection,
    prepared: PreparedInvocation,
) -> None:
    cursor = await connection.execute(
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
        (
            prepared.request.request.tenant_id,
            prepared.request.request.run_id,
            prepared.invocation_id,
        ),
    )
    _require_single_update(cursor.rowcount, "reserved budget was not charged")


async def _charge_reported_usage_and_clear(
    connection: PostgresConnection,
    prepared: PreparedInvocation,
    *,
    tokens: int,
    tools: int,
    cost_usd: Decimal,
) -> None:
    """Record known usage while preserving database hard-limit invariants."""

    cursor = await connection.execute(
        """
        UPDATE ai_runtime.analysis_run_budgets
           SET committed_tokens = least(token_limit, committed_tokens + %s),
               committed_tools = least(tool_limit, committed_tools + %s),
               committed_cost_usd = least(cost_limit_usd, committed_cost_usd + %s),
               active_invocation_id = NULL, active_reserved_tokens = NULL,
               active_reserved_cost_usd = NULL, active_lease_expires_at = NULL,
               updated_at = transaction_timestamp()
         WHERE organization_id = %s AND run_id = %s
           AND active_invocation_id = %s
        """,
        (
            tokens,
            tools,
            cost_usd,
            prepared.request.request.tenant_id,
            prepared.request.request.run_id,
            prepared.invocation_id,
        ),
    )
    _require_single_update(cursor.rowcount, "reported provider usage was not charged")


async def _clear_reservation(
    connection: PostgresConnection,
    prepared: PreparedInvocation,
) -> None:
    cursor = await connection.execute(
        """
        UPDATE ai_runtime.analysis_run_budgets
           SET active_invocation_id = NULL, active_reserved_tokens = NULL,
               active_reserved_cost_usd = NULL, active_lease_expires_at = NULL,
               updated_at = transaction_timestamp()
         WHERE organization_id = %s AND run_id = %s
           AND active_invocation_id = %s
        """,
        (
            prepared.request.request.tenant_id,
            prepared.request.request.run_id,
            prepared.invocation_id,
        ),
    )
    _require_single_update(cursor.rowcount, "reserved budget was not released")


async def _require_terminal_invocation(
    connection: PostgresConnection,
    prepared: PreparedInvocation,
) -> None:
    cursor = await connection.execute(
        "SELECT state FROM ai_runtime.analysis_invocations WHERE invocation_id = %s",
        (prepared.invocation_id,),
    )
    row = await cursor.fetchone()
    if row is None or row.get("state") not in {"failed", "ambiguous"}:
        raise RuntimeStateUnavailable("invocation terminal state is unavailable")


def _require_single_update(rowcount: int, message: str) -> None:
    if rowcount != 1:
        raise RuntimeStateUnavailable(message)


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
