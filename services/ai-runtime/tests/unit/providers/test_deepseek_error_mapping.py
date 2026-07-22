import pytest

from opsmind_ai_runtime.application.provider_gateway import ProviderErrorCategory
from opsmind_ai_runtime.providers.deepseek.error_mapping import map_status


@pytest.mark.parametrize(
    ("status", "category", "retryable"),
    [
        (400, ProviderErrorCategory.INVALID_REQUEST, False),
        (401, ProviderErrorCategory.UNAUTHORIZED, False),
        (402, ProviderErrorCategory.INSUFFICIENT_BALANCE, False),
        (422, ProviderErrorCategory.INVALID_RESPONSE, False),
        (429, ProviderErrorCategory.RATE_LIMITED, True),
        (500, ProviderErrorCategory.UNAVAILABLE, True),
        (503, ProviderErrorCategory.UNAVAILABLE, True),
    ],
)
def test_provider_status_matrix(
    status: int, category: ProviderErrorCategory, retryable: bool
) -> None:
    error = map_status(status)
    assert error.category == category
    assert error.retryable is retryable
