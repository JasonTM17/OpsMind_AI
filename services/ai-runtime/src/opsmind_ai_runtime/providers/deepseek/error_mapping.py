"""Stable provider error taxonomy and retry classification."""

from __future__ import annotations

from opsmind_ai_runtime.application.provider_gateway import ProviderError, ProviderErrorCategory


def map_status(status_code: int) -> ProviderError:
    """Map all supported provider statuses to deterministic categories."""

    if status_code == 400:
        category, retryable, message = (
            ProviderErrorCategory.INVALID_REQUEST,
            False,
            "provider rejected request",
        )
    elif status_code == 401:
        category, retryable, message = (
            ProviderErrorCategory.UNAUTHORIZED,
            False,
            "provider authorization failed",
        )
    elif status_code == 402:
        category, retryable, message = (
            ProviderErrorCategory.INSUFFICIENT_BALANCE,
            False,
            "provider balance unavailable",
        )
    elif status_code == 422:
        category, retryable, message = (
            ProviderErrorCategory.INVALID_RESPONSE,
            False,
            "provider rejected response contract",
        )
    elif status_code == 429:
        category, retryable, message = (
            ProviderErrorCategory.RATE_LIMITED,
            True,
            "provider rate limited request",
        )
    elif status_code in {500, 503}:
        category, retryable, message = (
            ProviderErrorCategory.UNAVAILABLE,
            True,
            "provider temporarily unavailable",
        )
    else:
        category, retryable, message = (
            ProviderErrorCategory.INTERNAL,
            False,
            "provider request failed",
        )
    return ProviderError(category, status_code, retryable, message)
