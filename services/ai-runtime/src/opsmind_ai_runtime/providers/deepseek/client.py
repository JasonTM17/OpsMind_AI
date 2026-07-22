"""Minimal DeepSeek chat-completion client with injectable transport."""

from __future__ import annotations

import asyncio
import json
from typing import Any, Protocol

from opsmind_ai_runtime.application.provider_gateway import (
    InvalidProviderResponse,
    ProviderError,
    ProviderErrorCategory,
)
from opsmind_ai_runtime.providers.deepseek.error_mapping import map_status


class JsonTransport(Protocol):
    async def post_json(
        self,
        url: str,
        *,
        headers: dict[str, str],
        payload: dict[str, Any],
        timeout_seconds: float,
    ) -> tuple[int, dict[str, Any]]: ...


class HttpxTransport:
    """Production HTTP transport; response body is consumed only in memory."""

    _MAX_RESPONSE_BYTES = 1_048_576

    async def post_json(
        self,
        url: str,
        *,
        headers: dict[str, str],
        payload: dict[str, Any],
        timeout_seconds: float,
    ) -> tuple[int, dict[str, Any]]:
        import httpx2

        try:
            async with (
                httpx2.AsyncClient(timeout=timeout_seconds, trust_env=False) as client,
                client.stream("POST", url, headers=headers, json=payload) as response,
            ):
                if response.status_code >= 400:
                    return response.status_code, {}
                chunks: list[bytes] = []
                received = 0
                async for chunk in response.aiter_bytes():
                    received += len(chunk)
                    if received > self._MAX_RESPONSE_BYTES:
                        raise InvalidProviderResponse("provider response exceeded size limit")
                    chunks.append(chunk)
                try:
                    decoded = json.loads(b"".join(chunks))
                except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                    raise InvalidProviderResponse("provider response was not valid JSON") from exc
                if not isinstance(decoded, dict):
                    raise InvalidProviderResponse("provider response envelope must be an object")
                return response.status_code, decoded
        except httpx2.RequestError as exc:
            raise ProviderError(
                ProviderErrorCategory.UNAVAILABLE,
                None,
                False,
                "provider transport unavailable",
            ) from exc


class DeepSeekClient:
    """Provider HTTP wrapper with bounded retry and no raw payload logging."""

    def __init__(
        self,
        *,
        base_url: str,
        credential: str,
        model: str,
        timeout_seconds: float = 20.0,
        max_retries: int = 0,
        transport: JsonTransport | None = None,
    ) -> None:
        if max_retries != 0:
            raise ValueError("provider POST retries require a verified idempotency contract")
        self._base_url = base_url.rstrip("/")
        self._api_key = credential
        self._model = model
        self._timeout = timeout_seconds
        self._transport = transport or HttpxTransport()

    async def complete(
        self,
        *,
        prompt: str,
        thinking: bool,
        user_id: str,
        max_tokens: int,
        timeout_seconds: float | None = None,
    ) -> dict[str, Any]:
        if not 1 <= max_tokens <= 100_000:
            raise ProviderError(
                ProviderErrorCategory.INVALID_REQUEST,
                None,
                False,
                "provider token limit is invalid",
            )
        payload: dict[str, Any] = {
            "model": self._model,
            "messages": [{"role": "user", "content": prompt}],
            "response_format": {"type": "json_object"},
            "stream": False,
            "user": user_id,
            "max_tokens": max_tokens,
        }
        if thinking:
            payload["thinking"] = {"type": "enabled"}
        headers = {"Authorization": f"Bearer {self._api_key}", "Content-Type": "application/json"}
        timeout = (
            min(self._timeout, timeout_seconds) if timeout_seconds is not None else self._timeout
        )
        if timeout <= 0:
            raise _deadline_error()
        try:
            status, body = await asyncio.wait_for(
                self._transport.post_json(
                    f"{self._base_url}/chat/completions",
                    headers=headers,
                    payload=payload,
                    timeout_seconds=timeout,
                ),
                timeout=timeout,
            )
        except TimeoutError as exc:
            raise _deadline_error() from exc
        if status >= 400:
            raise map_status(status)
        return body


def _deadline_error() -> ProviderError:
    return ProviderError(
        ProviderErrorCategory.DEADLINE_EXCEEDED,
        504,
        False,
        "analysis deadline exceeded",
    )
