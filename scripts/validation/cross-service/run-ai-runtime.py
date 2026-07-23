"""Windows-compatible launcher for the real AI Runtime cross-service process."""

from __future__ import annotations

import asyncio
import os
import sys

import uvicorn


def main() -> None:
    port = int(os.environ["OPSMIND_CROSS_SERVICE_AI_PORT"])
    if not 1024 <= port <= 65535:
        raise ValueError("cross-service AI Runtime port is invalid")
    config = uvicorn.Config(
        "opsmind_ai_runtime.main:app",
        host="127.0.0.1",
        port=port,
        log_level="warning",
    )
    server = uvicorn.Server(config)
    if sys.platform == "win32":
        asyncio.run(server.serve(), loop_factory=asyncio.SelectorEventLoop)
    else:
        asyncio.run(server.serve())


if __name__ == "__main__":
    main()
