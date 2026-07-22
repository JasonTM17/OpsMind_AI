"""Append-only audit sink for synthetic provider capability exchanges."""

from __future__ import annotations

from decimal import Decimal
from uuid import UUID, uuid4

from opsmind_ai_runtime.adapters.persistence.postgres_pool import PostgresPool
from opsmind_ai_runtime.providers.deepseek.capability_probe import ProbeUsage


class PostgresCapabilityProbeAuditSink:
    """Persist only bounded probe lifecycle and usage metadata; never prompt or key material."""

    def __init__(
        self,
        pool: PostgresPool,
        *,
        provider: str,
        model: str,
        region: str,
        max_calls_per_hour: int,
        input_cost_per_token_usd: Decimal,
        output_cost_per_token_usd: Decimal,
    ) -> None:
        if max_calls_per_hour < 1:
            raise ValueError("provider probe hourly quota must be positive")
        self._pool = pool
        self._provider = provider
        self._model = model
        self._region = region
        self._max_calls_per_hour = max_calls_per_hour
        self._input_cost_per_token_usd = input_cost_per_token_usd
        self._output_cost_per_token_usd = output_cost_per_token_usd

    async def record_started(self, probe_id: UUID) -> bool:
        lock_identity = "".join(
            f"{len(value)}:{value}" for value in (self._provider, self._model, self._region)
        )
        async with self._pool.connection() as connection, connection.transaction():
            await connection.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                (lock_identity,),
            )
            cursor = await connection.execute(
                """
                SELECT count(*) AS calls
                  FROM ai_runtime.provider_capability_probe_events
                 WHERE provider = %s
                   AND model_id = %s
                   AND provider_region = %s
                   AND event_type = 'started'
                   AND occurred_at >= transaction_timestamp() - interval '1 hour'
                """,
                (self._provider, self._model, self._region),
            )
            row = await cursor.fetchone()
            if row is None or _required_int(row, "calls") >= self._max_calls_per_hour:
                return False
            await connection.execute(
                """
                INSERT INTO ai_runtime.provider_capability_probe_events (
                    event_id, probe_id, provider, model_id, provider_region, event_type
                ) VALUES (%s, %s, %s, %s, %s, 'started')
                """,
                (uuid4(), probe_id, self._provider, self._model, self._region),
            )
        return True

    async def record_finished(
        self,
        probe_id: UUID,
        *,
        succeeded: bool,
        usage: ProbeUsage | None,
        failure_code: str | None = None,
    ) -> None:
        if succeeded and (usage is None or failure_code is not None):
            raise ValueError("successful provider probe audit requires usage only")
        if not succeeded and (
            usage is not None
            or failure_code
            not in {
                "provider_capability_probe_failed",
                "provider_capability_probe_cancelled",
            }
        ):
            raise ValueError("failed provider probe audit requires a bounded failure code")
        cost = None
        if usage is not None:
            cost = (
                Decimal(usage.prompt_tokens) * self._input_cost_per_token_usd
                + Decimal(usage.completion_tokens) * self._output_cost_per_token_usd
            )
        async with self._pool.connection() as connection:
            cursor = await connection.execute(
                """
                INSERT INTO ai_runtime.provider_capability_probe_events (
                    event_id, probe_id, provider, model_id, provider_region,
                    event_type, outcome, prompt_tokens, completion_tokens,
                    total_tokens, cost_usd, error_code
                )
                SELECT %s, %s, %s, %s, %s, 'finished', %s, %s, %s, %s, %s, %s
                  FROM ai_runtime.provider_capability_probe_events
                 WHERE probe_id = %s AND event_type = 'started'
                RETURNING event_id
                """,
                (
                    uuid4(),
                    probe_id,
                    self._provider,
                    self._model,
                    self._region,
                    "succeeded" if succeeded else "failed",
                    usage.prompt_tokens if usage is not None else None,
                    usage.completion_tokens if usage is not None else None,
                    usage.total_tokens if usage is not None else None,
                    cost,
                    failure_code,
                    probe_id,
                ),
            )
            if await cursor.fetchone() is None:
                raise RuntimeError("provider probe start audit is missing")


def _required_int(row: dict[str, object], key: str) -> int:
    value = row.get(key)
    if type(value) is not int:
        raise ValueError(f"provider probe audit field is not an integer: {key}")
    return value
