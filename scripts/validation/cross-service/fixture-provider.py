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
SCENARIOS = ("A", "B", "C")
LATENCY_SELECTOR_DIGEST = (
    "sha256:51ef8c2e4e2926e103ddd877490c64d604b1df593ca23cffca8d1b2fac5d8700"
)
ERROR_SELECTOR_DIGEST = (
    "sha256:3d2800b26ccf73b0a160a248947334aafbb08514fc8b4b2c63484ea02bd66954"
)
_STATS_LOCK = Lock()
_STATS = {"probe_requests": 0, "analysis_requests": 0}
_SCENARIO = "A"


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


def _evidence_by_metric(evidence: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    indexed: dict[str, dict[str, Any]] = {}
    for record in evidence:
        canonical = record.get("canonical_content")
        content = json.loads(canonical) if isinstance(canonical, str) else canonical
        if not isinstance(content, dict):
            raise ValueError("evidence canonical content is invalid")
        metric = content.get("metric")
        if not isinstance(metric, str) or metric in indexed:
            raise ValueError("evidence metric identity is missing or duplicated")
        indexed[metric] = record
    return indexed


def _analysis_content(prompt: str) -> dict[str, Any]:
    envelope = json.loads(prompt[prompt.rfind("\n{") + 1 :])
    evidence = envelope.get("metric_evidence") or []
    if _SCENARIO == "B":
        return {
            "status": "abstain",
            "hypotheses": [],
            "counter_evidence": [],
            "missing_evidence": [
                "Approved synthetic evidence is insufficient to identify a supported cause."
            ],
            "citations": [],
            "confidence": 0.1,
            "requested_tool_calls": [],
        }
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
        selectors_by_digest = {
            selector.get("arguments_digest"): selector for selector in selectors
        }
        allowed_digests = {LATENCY_SELECTOR_DIGEST, ERROR_SELECTOR_DIGEST}
        if (
            len(selectors_by_digest) != len(selectors)
            or set(selectors_by_digest) != allowed_digests
        ):
            raise ValueError("approved selector set does not match the scenario")
        selected = [
            selectors_by_digest[digest]
            for digest in (
                [LATENCY_SELECTOR_DIGEST, ERROR_SELECTOR_DIGEST]
                if _SCENARIO == "C"
                else [LATENCY_SELECTOR_DIGEST]
            )
        ]
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
                    "rationale": (
                        "Synthetic provider requests an exact approved read-only selector."
                    ),
                }
                for selector in selected
            ],
        }
    evidence_by_metric = _evidence_by_metric(evidence)
    record = evidence_by_metric.get("http_request_duration_seconds")
    if record is None:
        raise ValueError("latency evidence is missing")
    citation = {
        "evidence_id": record["evidence_id"],
        "digest": record["digest"],
        "claim": "Synthetic Prometheus evidence supports a latency regression hypothesis.",
    }
    if _SCENARIO == "C":
        counter_record = evidence_by_metric.get("http_errors_total")
        if len(evidence_by_metric) != 2 or counter_record is None:
            raise ValueError("conflict scenario requires two persisted evidence records")
        counter_citation = {
            "evidence_id": counter_record["evidence_id"],
            "digest": counter_record["digest"],
            "claim": (
                "HTTP error counts stay flat, weakening a broad "
                "deployment-regression diagnosis."
            ),
        }
        return {
            "status": "complete",
            "hypotheses": [
                {
                    "title": (
                        "Synthetic latency regression with flat error counter signal"
                    ),
                    "explanation": (
                        "Latency rises after the deployment, but flat HTTP error counts "
                        "contradict a broad service-degradation signature, so the causal "
                        "diagnosis remains cautious."
                    ),
                    "confidence": 0.55,
                    "citations": [citation, counter_citation],
                }
            ],
            "counter_evidence": [
                "HTTP error counts remain flat while latency rises."
            ],
            "missing_evidence": [],
            "citations": [citation, counter_citation],
            "confidence": 0.55,
            "requested_tool_calls": [],
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
                "scenario": _SCENARIO,
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
    global _SCENARIO
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=19090)
    parser.add_argument("--scenario", choices=SCENARIOS, default="A")
    parser.add_argument("--opsmind-cross-service-run-id", required=True)
    args = parser.parse_args()
    if re.fullmatch(r"[0-9a-f]{32}", args.opsmind_cross_service_run_id) is None:
        parser.error("cross-service run id is invalid")
    _SCENARIO = args.scenario
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
