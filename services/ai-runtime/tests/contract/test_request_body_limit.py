import asyncio

from opsmind_ai_runtime.main import create_app


def test_chunked_body_is_rejected_before_json_parsing() -> None:
    async def scenario() -> tuple[int, bytes]:
        app = create_app(max_request_body_bytes=1_024)
        chunks = [
            {"type": "http.request", "body": b"{" + b"x" * 700, "more_body": True},
            {"type": "http.request", "body": b"y" * 700, "more_body": False},
        ]
        sent: list[dict[str, object]] = []

        async def receive() -> dict[str, object]:
            return chunks.pop(0) if chunks else {"type": "http.disconnect"}

        async def send(message: dict[str, object]) -> None:
            sent.append(message)

        scope = {
            "type": "http",
            "asgi": {"version": "3.0"},
            "http_version": "1.1",
            "method": "POST",
            "scheme": "http",
            "path": "/api/v1/analysis",
            "raw_path": b"/api/v1/analysis",
            "query_string": b"",
            "headers": [
                (b"content-type", b"application/json"),
                (b"x-correlation-id", b"corr-chunked-1"),
            ],
            "client": ("test", 1),
            "server": ("test", 80),
            "root_path": "",
            "state": {},
        }
        await app(scope, receive, send)  # type: ignore[arg-type]
        start = next(message for message in sent if message["type"] == "http.response.start")
        body = b"".join(
            message.get("body", b"") for message in sent if message["type"] == "http.response.body"
        )
        return int(start["status"]), body

    status, body = asyncio.run(scenario())

    assert status == 413
    assert b'"code":"request.too_large"' in body


def test_slow_chunked_body_is_bounded_before_authentication() -> None:
    async def scenario() -> tuple[int, bytes]:
        app = create_app(
            max_request_body_bytes=1_024,
            request_body_timeout_seconds=0.01,
        )
        sent: list[dict[str, object]] = []

        async def receive() -> dict[str, object]:
            await asyncio.sleep(0.05)
            return {"type": "http.request", "body": b"{}", "more_body": False}

        async def send(message: dict[str, object]) -> None:
            sent.append(message)

        scope = {
            "type": "http",
            "asgi": {"version": "3.0"},
            "http_version": "1.1",
            "method": "POST",
            "scheme": "http",
            "path": "/api/v1/analysis",
            "raw_path": b"/api/v1/analysis",
            "query_string": b"",
            "headers": [(b"content-type", b"application/json")],
            "client": ("test", 1),
            "server": ("test", 80),
            "root_path": "",
            "state": {},
        }
        await app(scope, receive, send)  # type: ignore[arg-type]
        start = next(message for message in sent if message["type"] == "http.response.start")
        body = b"".join(
            message.get("body", b"") for message in sent if message["type"] == "http.response.body"
        )
        return int(start["status"]), body

    status, body = asyncio.run(scenario())

    assert status == 408
    assert b'"code":"request.body_timeout"' in body


def test_tiny_chunk_amplification_is_bounded_before_json_parsing() -> None:
    async def scenario() -> int:
        app = create_app(max_request_body_bytes=1_024)
        chunks = [{"type": "http.request", "body": b"", "more_body": True} for _ in range(1_025)]
        sent: list[dict[str, object]] = []

        async def receive() -> dict[str, object]:
            return chunks.pop() if chunks else {"type": "http.disconnect"}

        async def send(message: dict[str, object]) -> None:
            sent.append(message)

        scope = {
            "type": "http",
            "asgi": {"version": "3.0"},
            "http_version": "1.1",
            "method": "POST",
            "scheme": "http",
            "path": "/api/v1/analysis",
            "raw_path": b"/api/v1/analysis",
            "query_string": b"",
            "headers": [(b"content-type", b"application/json")],
            "client": ("test", 1),
            "server": ("test", 80),
            "root_path": "",
            "state": {},
        }
        await app(scope, receive, send)  # type: ignore[arg-type]
        start = next(message for message in sent if message["type"] == "http.response.start")
        return int(start["status"])

    assert asyncio.run(scenario()) == 413
