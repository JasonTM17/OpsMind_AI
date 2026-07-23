"""Non-production DeepSeek-compatible provider used by the cross-service gate.

The server is intentionally deterministic and local-only. It exercises the
same AI Runtime HTTP client, capability probe, schema adapter, budgets, and
PostgreSQL invocation state without contacting a model provider.
"""

from __future__ import annotations

import argparse
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock
from typing import Any
from uuid import NAMESPACE_URL, uuid5

MODEL = "deepseek-v4-flash"
_STATS_LOCK = Lock()
_STATS = {"probe_requests": 0, "analysis_requests": 0}


def _usage(prompt: str, completion: int) -> dict[str, int]:
    prompt_tokens = max(1, len(prompt.encode("utf-8")) // 4)
    return {
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion,
        "total_tokens": prompt_tokens + completion,
    }


def _response(content: dict[str, Any], prompt: str, *, reasoning: bool = False) -> dict[str, Any]:
    return {
        "id": "opsmind-fixture-completion",
        "model": MODEL,
        "choices": [
            {
                "message": {
                    "role": "assistant",
                    "content": json.dumps(content, separators=(",", ":")),
                    **({"reasoning_content": "discarded fixture reasoning"} if reasoning else {}),
                },
                "finish_reason": "stop",
            }
        ],
        "usage": _usage(prompt, 32),
    }


def _analysis_content(prompt: str) -> dict[str, Any]:
    envelope = json.loads(prompt[prompt.rfind("\n{") + 1 :])
    evidence = envelope.get("metric_evidence") or []
    if not evidence:
        selectors = envelope.get("allowed_tool_selectors") or []
        if not selectors:
            return {
                "status": "abstain",
                "hypotheses": [],
                "counter_evidence": [],
                "missing_evidence": ["No approved synthetic metric selector is available."],
                "citations": [],
                "confidence": 0.0,
                "requested_tool_calls": [],
            }
        selector = selectors[0]
        return {
            "status": "need_more_evidence",
            "hypotheses": [],
            "counter_evidence": [],
            "missing_evidence": [],
            "citations": [],
            "confidence": 0.0,
            "requested_tool_calls": [
                {
                    "intent_id": str(uuid5(NAMESPACE_URL, selector["arguments_digest"])),
                    "connector": selector["connector"],
                    "operation": selector["operation"],
                    "arguments_digest": selector["arguments_digest"],
                    "rationale": "Synthetic provider requests the first approved read-only selector.",
                }
            ],
        }
    record = evidence[0]
    citation = {
        "evidence_id": record["evidence_id"],
        "digest": record["digest"],
        "claim": "Synthetic Prometheus evidence supports a latency regression hypothesis.",
    }
    return {
        "status": "complete",
        "hypotheses": [
            {
                "title": "Synthetic latency regression",
                "explanation": "The bounded synthetic metric changed after the deployment marker.",
                "confidence": 0.82,
                "citations": [citation],
            }
        ],
        "counter_evidence": [],
        "missing_evidence": [],
        "citations": [citation],
        "confidence": 0.82,
        "requested_tool_calls": [],
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "OpsMindFixtureProvider/1"

    def do_GET(self) -> None:  # noqa: N802
        if self.path != "/__opsmind/status":
            self.send_error(404)
            return
        with _STATS_LOCK:
            document = {
                "schema": "opsmind-fixture-provider-status-v1",
                **_STATS,
                "total_requests": sum(_STATS.values()),
            }
        self._send_json(document)

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v1/chat/completions":
            self.send_error(404)
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(size))
            prompt = payload["messages"][0]["content"]
            if "opsmind_probe" in prompt:
                with _STATS_LOCK:
                    _STATS["probe_requests"] += 1
                content = {"opsmind_probe": "ok"}
                body = _response(content, prompt, reasoning=True)
            else:
                with _STATS_LOCK:
                    _STATS["analysis_requests"] += 1
                body = _response(_analysis_content(prompt), prompt)
        except (KeyError, TypeError, ValueError, json.JSONDecodeError):
            self.send_error(400)
            return
        self._send_json(body)

    def _send_json(self, document: dict[str, Any]) -> None:
        encoded = json.dumps(document, separators=(",", ":")).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, *_: object) -> None:
        return


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=19090)
    parser.add_argument("--opsmind-cross-service-run-id", required=True)
    args = parser.parse_args()
    if re.fullmatch(r"[0-9a-f]{32}", args.opsmind_cross_service_run_id) is None:
        parser.error("cross-service run id is invalid")
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
