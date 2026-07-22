"""Bounded Psycopg pool construction without logging connection secrets."""

from __future__ import annotations

from psycopg import AsyncConnection
from psycopg.conninfo import make_conninfo
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from opsmind_ai_runtime.config.settings import RuntimeSettings

PostgresRow = dict[str, object]
PostgresConnection = AsyncConnection[PostgresRow]
PostgresPool = AsyncConnectionPool[PostgresConnection]


def build_postgres_pool(settings: RuntimeSettings) -> PostgresPool:
    """Create a closed pool; application lifespan explicitly opens it."""

    if not settings.state_ready or settings.database_password is None:
        raise ValueError("durable runtime state configuration is incomplete")
    connection_parameters: dict[str, str | int] = {
        "host": settings.database_host,
        "port": settings.database_port,
        "dbname": settings.database_name,
        "user": settings.database_user,
        "password": settings.database_password,
        "application_name": "opsmind-ai-runtime",
        "connect_timeout": max(1, int(settings.database_pool_timeout_seconds)),
    }
    conninfo = make_conninfo("", **connection_parameters)
    return AsyncConnectionPool(
        conninfo,
        connection_class=AsyncConnection,
        kwargs={
            "autocommit": True,
            "row_factory": dict_row,
            "options": "-c statement_timeout=30000 -c lock_timeout=3000 "
            "-c idle_in_transaction_session_timeout=10000",
        },
        min_size=settings.database_pool_min,
        max_size=settings.database_pool_max,
        open=False,
        timeout=settings.database_pool_timeout_seconds,
        max_waiting=settings.database_pool_max * 4,
        name="opsmind-ai-runtime-state",
    )
