"""Atomic per-run token, tool, and cost reservations."""

from __future__ import annotations

from dataclasses import dataclass
from math import floor
from threading import Lock
from uuid import UUID


class BudgetExceeded(RuntimeError):
    """Raised when a reservation would exceed the caller's hard budget."""


@dataclass(frozen=True, slots=True)
class BudgetReservation:
    run_id: UUID
    token_budget: int
    tool_budget: int
    cost_budget_usd: float


@dataclass(frozen=True, slots=True)
class BudgetAllowance:
    max_completion_tokens: int
    projected_cost_usd: float


def calculate_allowance(
    reservation: BudgetReservation,
    *,
    committed_tokens: int,
    committed_tools: int,
    committed_cost_usd: float,
    estimated_tokens: int,
    input_cost_per_token_usd: float,
    output_cost_per_token_usd: float,
) -> BudgetAllowance:
    """Calculate one provider cap from authoritative cumulative usage."""

    if (
        min(committed_tokens, committed_tools, committed_cost_usd) < 0
        or estimated_tokens < 1
        or min(input_cost_per_token_usd, output_cost_per_token_usd) < 0
        or reservation.tool_budget < 0
    ):
        raise BudgetExceeded("invalid budget reservation")
    remaining_tokens = reservation.token_budget - committed_tokens - estimated_tokens
    if remaining_tokens < 1:
        raise BudgetExceeded("token budget exceeded")
    if committed_tools > reservation.tool_budget:
        raise BudgetExceeded("tool budget exceeded")
    remaining_cost = reservation.cost_budget_usd - committed_cost_usd
    input_cost = estimated_tokens * input_cost_per_token_usd
    if input_cost >= remaining_cost:
        raise BudgetExceeded("cost budget exceeded")
    max_completion_tokens = remaining_tokens
    if output_cost_per_token_usd > 0:
        max_completion_tokens = min(
            max_completion_tokens,
            floor((remaining_cost - input_cost) / output_cost_per_token_usd),
        )
    if max_completion_tokens < 1:
        raise BudgetExceeded("cost budget exceeded")
    projected_cost_usd = input_cost + max_completion_tokens * output_cost_per_token_usd
    return BudgetAllowance(max_completion_tokens, projected_cost_usd)


@dataclass(slots=True)
class _Usage:
    tokens: int = 0
    tools: int = 0
    cost_usd: float = 0.0


@dataclass(frozen=True, slots=True)
class _InFlight:
    reserved_tokens: int
    estimated_cost_usd: float


class BudgetGuard:
    """Small in-memory guard for one bounded request process.

    Durable workflow and quota accounting remain owned by the Platform API. The
    guard prevents accidental provider loops inside this runtime instance.
    """

    def __init__(self, *, max_cost_usd: float = 100.0) -> None:
        self._max_cost_usd = max_cost_usd
        self._lock = Lock()
        self._usage: dict[UUID, _Usage] = {}
        self._limits: dict[UUID, BudgetReservation] = {}
        self._in_flight: dict[UUID, _InFlight] = {}

    @property
    def max_cost_usd(self) -> float:
        return self._max_cost_usd

    def reserve(
        self,
        reservation: BudgetReservation,
        *,
        estimated_tokens: int,
        input_cost_per_token_usd: float = 0.0,
        output_cost_per_token_usd: float = 0.0,
    ) -> BudgetAllowance:
        if (
            estimated_tokens < 1
            or min(input_cost_per_token_usd, output_cost_per_token_usd) < 0
            or reservation.tool_budget < 0
        ):
            raise BudgetExceeded("invalid budget reservation")
        with self._lock:
            existing = self._limits.get(reservation.run_id)
            if existing is not None and existing != reservation:
                raise BudgetExceeded("run budget cannot change during an exchange")
            if reservation.run_id in self._in_flight:
                raise BudgetExceeded("run already has an analysis exchange in flight")
            self._limits[reservation.run_id] = reservation
            usage = self._usage.setdefault(reservation.run_id, _Usage())
            allowance = calculate_allowance(
                reservation,
                committed_tokens=usage.tokens,
                committed_tools=usage.tools,
                committed_cost_usd=usage.cost_usd,
                estimated_tokens=estimated_tokens,
                input_cost_per_token_usd=input_cost_per_token_usd,
                output_cost_per_token_usd=output_cost_per_token_usd,
            )
            self._in_flight[reservation.run_id] = _InFlight(
                reserved_tokens=estimated_tokens + allowance.max_completion_tokens,
                estimated_cost_usd=allowance.projected_cost_usd,
            )
            return allowance

    def commit(self, run_id: UUID, *, tokens: int, tools: int, cost_usd: float) -> None:
        if min(tokens, tools, cost_usd) < 0:
            raise BudgetExceeded("negative usage is invalid")
        with self._lock:
            limits = self._limits.get(run_id)
            if limits is None or run_id not in self._in_flight:
                raise BudgetExceeded("usage has no reservation")
            usage = self._usage.setdefault(run_id, _Usage())
            usage.tokens += tokens
            usage.tools += tools
            usage.cost_usd += cost_usd
            self._in_flight.pop(run_id, None)
            if (
                usage.tokens > limits.token_budget
                or usage.tools > limits.tool_budget
                or usage.cost_usd > limits.cost_budget_usd
            ):
                raise BudgetExceeded("provider usage exceeded hard budget")

    def cancel(self, run_id: UUID, *, charge_estimate: bool) -> None:
        """Release an exchange, conservatively charging unknown provider work."""

        with self._lock:
            in_flight = self._in_flight.pop(run_id, None)
            if in_flight is not None and charge_estimate:
                usage = self._usage.setdefault(run_id, _Usage())
                usage.tokens += in_flight.reserved_tokens
                usage.cost_usd += in_flight.estimated_cost_usd

    def clear(self, run_id: UUID) -> None:
        with self._lock:
            self._usage.pop(run_id, None)
            self._limits.pop(run_id, None)
            self._in_flight.pop(run_id, None)
