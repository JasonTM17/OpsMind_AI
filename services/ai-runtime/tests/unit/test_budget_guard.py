from uuid import uuid4

import pytest

from opsmind_ai_runtime.application.budget_guard import (
    BudgetExceeded,
    BudgetGuard,
    BudgetReservation,
)


def test_budget_guard_rejects_provider_usage_over_hard_limit() -> None:
    run_id = uuid4()
    guard = BudgetGuard(max_cost_usd=1.0)
    guard.reserve(
        BudgetReservation(run_id=run_id, token_budget=10, tool_budget=1, cost_budget_usd=1.0),
        estimated_tokens=1,
    )
    with pytest.raises(BudgetExceeded, match="hard budget"):
        guard.commit(run_id, tokens=11, tools=0, cost_usd=0.1)


def test_budget_guard_cannot_change_limits_mid_run() -> None:
    run_id = uuid4()
    guard = BudgetGuard()
    guard.reserve(
        BudgetReservation(run_id=run_id, token_budget=10, tool_budget=1, cost_budget_usd=1.0),
        estimated_tokens=1,
    )
    with pytest.raises(BudgetExceeded, match="cannot change"):
        guard.reserve(
            BudgetReservation(run_id=run_id, token_budget=20, tool_budget=1, cost_budget_usd=1.0),
            estimated_tokens=1,
        )


def test_budget_guard_rejects_concurrent_exchange_for_same_run() -> None:
    run_id = uuid4()
    reservation = BudgetReservation(
        run_id=run_id, token_budget=100, tool_budget=1, cost_budget_usd=1.0
    )
    guard = BudgetGuard()
    guard.reserve(reservation, estimated_tokens=10)

    with pytest.raises(BudgetExceeded, match="in flight"):
        guard.reserve(reservation, estimated_tokens=10)


def test_budget_guard_adds_sequential_provider_usage() -> None:
    run_id = uuid4()
    reservation = BudgetReservation(
        run_id=run_id, token_budget=100, tool_budget=1, cost_budget_usd=1.0
    )
    guard = BudgetGuard()
    allowance = guard.reserve(reservation, estimated_tokens=1)
    assert allowance.max_completion_tokens == 99
    guard.commit(run_id, tokens=60, tools=0, cost_usd=0.1)
    allowance = guard.reserve(reservation, estimated_tokens=1)
    assert allowance.max_completion_tokens == 39

    with pytest.raises(BudgetExceeded, match="hard budget"):
        guard.commit(run_id, tokens=60, tools=0, cost_usd=0.1)

    with pytest.raises(BudgetExceeded, match="token budget"):
        guard.reserve(reservation, estimated_tokens=1)


def test_ambiguous_provider_failure_charges_full_reserved_token_allowance() -> None:
    run_id = uuid4()
    reservation = BudgetReservation(
        run_id=run_id, token_budget=100, tool_budget=0, cost_budget_usd=1.0
    )
    guard = BudgetGuard()
    allowance = guard.reserve(reservation, estimated_tokens=10)
    assert allowance.max_completion_tokens == 90
    guard.cancel(run_id, charge_estimate=True)

    with pytest.raises(BudgetExceeded, match="token budget"):
        guard.reserve(reservation, estimated_tokens=1)
