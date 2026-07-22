import asyncio

import pytest

from opsmind_ai_runtime.application.provider_gateway import ProviderError
from opsmind_ai_runtime.providers.deepseek.client import DeepSeekClient


class SequenceTransport:
    def __init__(self, responses: list[tuple[int, dict[str, object]]]) -> None:
        self.responses = responses
        self.calls = 0
        self.payloads: list[dict[str, object]] = []

    async def post_json(
        self,
        url: str,
        *,
        headers: dict[str, str],
        payload: dict[str, object],
        timeout_seconds: float,
    ):
        self.calls += 1
        self.payloads.append(payload)
        return self.responses.pop(0)


class SlowTransport:
    def __init__(self) -> None:
        self.calls = 0

    async def post_json(self, *args: object, **kwargs: object):
        self.calls += 1
        await asyncio.sleep(0.05)
        return 200, {"choices": []}


def test_client_does_not_retry_rate_limit_without_idempotency_contract() -> None:
    transport = SequenceTransport([(429, {}), (200, {"choices": []})])
    client = DeepSeekClient(
        base_url="https://provider.example/v1",
        credential="placeholder",
        model="deepseek-v4-flash",
        transport=transport,
    )
    with pytest.raises(ProviderError) as error:
        asyncio.run(
            client.complete(prompt="redacted", thinking=False, user_id="run:test", max_tokens=100)
        )
    assert error.value.category == "provider.rate_limited"
    assert transport.calls == 1
    assert transport.payloads[0]["max_tokens"] == 100


def test_client_does_not_retry_unauthorized() -> None:
    transport = SequenceTransport([(401, {})])
    client = DeepSeekClient(
        base_url="https://provider.example/v1",
        credential="placeholder",
        model="deepseek-v4-flash",
        transport=transport,
    )
    with pytest.raises(ProviderError) as error:
        asyncio.run(
            client.complete(prompt="redacted", thinking=False, user_id="run:test", max_tokens=100)
        )
    assert error.value.category == "provider.unauthorized"
    assert transport.calls == 1


def test_client_rejects_expired_deadline_before_transport() -> None:
    transport = SequenceTransport([])
    client = DeepSeekClient(
        base_url="https://provider.example/v1",
        credential="placeholder",
        model="deepseek-v4-flash",
        transport=transport,
    )
    with pytest.raises(ProviderError, match="deadline"):
        asyncio.run(
            client.complete(
                prompt="redacted",
                thinking=False,
                user_id="run:test",
                max_tokens=100,
                timeout_seconds=0,
            )
        )
    assert transport.calls == 0


def test_client_enforces_one_attempt_deadline() -> None:
    transport = SlowTransport()
    client = DeepSeekClient(
        base_url="https://provider.example/v1",
        credential="placeholder",
        model="deepseek-v4-flash",
        transport=transport,
    )

    with pytest.raises(ProviderError) as error:
        asyncio.run(
            client.complete(
                prompt="redacted",
                thinking=False,
                user_id="run:test",
                max_tokens=100,
                timeout_seconds=0.01,
            )
        )

    assert error.value.category == "provider.deadline_exceeded"
    assert transport.calls == 1


def test_client_rejects_retry_configuration_without_idempotency_contract() -> None:
    with pytest.raises(ValueError, match="idempotency"):
        DeepSeekClient(
            base_url="https://provider.example/v1",
            credential="placeholder",
            model="deepseek-v4-flash",
            max_retries=1,
        )


def test_client_rejects_invalid_provider_token_limit_before_transport() -> None:
    transport = SequenceTransport([])
    client = DeepSeekClient(
        base_url="https://provider.example/v1",
        credential="placeholder",
        model="deepseek-v4-flash",
        transport=transport,
    )

    with pytest.raises(ProviderError) as error:
        asyncio.run(
            client.complete(
                prompt="redacted",
                thinking=False,
                user_id="run:test",
                max_tokens=0,
            )
        )

    assert error.value.category == "provider.invalid_request"
    assert transport.calls == 0
