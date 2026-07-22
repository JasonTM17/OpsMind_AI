"""ASGI ingress guard that rejects oversized bodies before JSON parsing."""

from __future__ import annotations

import asyncio

from starlette.datastructures import Headers
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from opsmind_ai_runtime.api.problem_details import build_problem_response, resolve_correlation_id


class RequestBodyLimitMiddleware:
    """Bound declared and chunked request bodies before route dependencies run."""

    def __init__(
        self,
        app: ASGIApp,
        *,
        max_bytes: int,
        receive_timeout_seconds: float,
        max_chunks: int = 1_024,
    ) -> None:
        self._app = app
        self._max_bytes = max_bytes
        self._receive_timeout_seconds = receive_timeout_seconds
        self._max_chunks = max_chunks

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope.get("method") not in {
            "POST",
            "PUT",
            "PATCH",
        }:
            await self._app(scope, receive, send)
            return

        headers = Headers(scope=scope)
        correlation_id = resolve_correlation_id(headers.get("x-correlation-id"))
        declared_length = headers.get("content-length")
        if declared_length is not None:
            try:
                if int(declared_length) > self._max_bytes:
                    await self._reject(
                        scope, receive, send, correlation_id, 413, "request.too_large"
                    )
                    return
            except ValueError:
                pass

        received = 0
        chunk_count = 0
        body = bytearray()
        terminal_message: Message | None = None
        try:
            async with asyncio.timeout(self._receive_timeout_seconds):
                while True:
                    message = await receive()
                    if message["type"] != "http.request":
                        terminal_message = message
                        break
                    chunk = message.get("body", b"")
                    received += len(chunk)
                    chunk_count += 1
                    if received > self._max_bytes or chunk_count > self._max_chunks:
                        await self._reject(
                            scope,
                            receive,
                            send,
                            correlation_id,
                            413,
                            "request.too_large",
                        )
                        return
                    body.extend(chunk)
                    if not message.get("more_body", False):
                        break
        except TimeoutError:
            await self._reject(
                scope,
                receive,
                send,
                correlation_id,
                408,
                "request.body_timeout",
            )
            return

        replayed = False

        async def replay_receive() -> Message:
            nonlocal replayed
            if not replayed:
                replayed = True
                if terminal_message is not None:
                    return terminal_message
                return {"type": "http.request", "body": bytes(body), "more_body": False}
            return await receive()

        await self._app(scope, replay_receive, send)

    @staticmethod
    async def _reject(
        scope: Scope,
        receive: Receive,
        send: Send,
        correlation_id: str,
        status_code: int,
        code: str,
    ) -> None:
        response = build_problem_response(status_code, code, correlation_id)
        await response(scope, receive, send)
