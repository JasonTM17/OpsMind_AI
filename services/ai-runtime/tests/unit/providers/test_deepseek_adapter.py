import asyncio
import json
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

from opsmind_ai_runtime.application.provider_gateway import (
    AnalysisAdapterContext,
    InvalidProviderResponse,
)
from opsmind_ai_runtime.providers.deepseek.adapter import (
    DeepSeekAdapter,
)
from opsmind_ai_runtime.providers.deepseek.client import DeepSeekClient


class FakeTransport:
    def __init__(self, body: dict[str, object]) -> None:
        self.body = body

    async def post_json(
        self,
        url: str,
        *,
        headers: dict[str, str],
        payload: dict[str, object],
        timeout_seconds: float,
    ):
        assert url.endswith("/chat/completions")
        assert headers["Authorization"].startswith("Bearer ")
        assert payload["response_format"] == {"type": "json_object"}
        assert payload["max_tokens"] == 100
        return 200, self.body


def _context() -> AnalysisAdapterContext:
    return AnalysisAdapterContext(
        run_id=uuid4(),
        prompt_version="prompt-incident-v1",
        schema_version="analysis-v1",
        model_id="deepseek-v4-flash",
        deadline_at=datetime.now(UTC) + timedelta(minutes=1),
        max_completion_tokens=100,
        input_cost_per_million_usd=1.0,
        output_cost_per_million_usd=2.0,
    )


def test_adapter_normalizes_structured_response_and_drops_reasoning() -> None:
    citation = {
        "evidence_id": str(uuid4()),
        "digest": "sha256:" + "a" * 64,
        "claim": "The latency metric changed after the deploy.",
    }
    content = {
        "status": "complete",
        "hypotheses": [
            {
                "title": "Latency regression",
                "explanation": "A deploy changed a timeout.",
                "confidence": 0.8,
                "citations": [citation],
            }
        ],
        "counter_evidence": [],
        "missing_evidence": [],
        "citations": [citation],
        "confidence": 0.8,
    }
    body = {
        "model": "deepseek-v4-flash",
        "choices": [
            {
                "message": {
                    "content": json.dumps(content),
                    "reasoning_content": "never persist",
                },
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
    }
    adapter = DeepSeekAdapter(
        DeepSeekClient(
            base_url="https://provider.example/v1",
            credential="placeholder",
            model="deepseek-v4-flash",
            transport=FakeTransport(body),
        )
    )
    response = asyncio.run(
        adapter.analyze(prompt="redacted", thinking=True, user_id="run:test", context=_context())
    )
    assert response.status == "complete"
    assert response.usage.total_tokens == 15
    assert response.cost_estimate.amount == pytest.approx(0.00002)


def test_adapter_rejects_empty_content() -> None:
    body = {
        "model": "deepseek-v4-flash",
        "choices": [{"message": {"content": ""}, "finish_reason": "stop"}],
        "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
    }
    adapter = DeepSeekAdapter(
        DeepSeekClient(
            base_url="https://provider.example/v1",
            credential="placeholder",
            model="deepseek-v4-flash",
            transport=FakeTransport(body),
        )
    )
    with pytest.raises(InvalidProviderResponse):
        asyncio.run(
            adapter.analyze(
                prompt="redacted", thinking=False, user_id="run:test", context=_context()
            )
        )


def test_adapter_rejects_uncited_complete_hypothesis() -> None:
    body = {
        "model": "deepseek-v4-flash",
        "choices": [
            {
                "message": {
                    "content": json.dumps(
                        {
                            "status": "complete",
                            "hypotheses": [
                                {
                                    "title": "Unsupported",
                                    "explanation": "No evidence.",
                                    "confidence": 0.2,
                                }
                            ],
                            "counter_evidence": [],
                            "missing_evidence": [],
                            "citations": [],
                            "confidence": 0.2,
                        }
                    )
                },
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
    }
    adapter = DeepSeekAdapter(
        DeepSeekClient(
            base_url="https://provider.example/v1",
            credential="placeholder",
            model="deepseek-v4-flash",
            transport=FakeTransport(body),
        )
    )
    with pytest.raises(InvalidProviderResponse):
        asyncio.run(
            adapter.analyze(
                prompt="redacted", thinking=False, user_id="run:test", context=_context()
            )
        )


def test_adapter_rejects_unknown_structured_output_fields() -> None:
    body = {
        "model": "deepseek-v4-flash",
        "choices": [
            {
                "message": {
                    "content": json.dumps(
                        {
                            "status": "abstain",
                            "hypotheses": [],
                            "counter_evidence": [],
                            "missing_evidence": [],
                            "citations": [],
                            "confidence": 0.0,
                            "unexpected": "schema drift",
                        }
                    )
                },
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
    }
    adapter = DeepSeekAdapter(
        DeepSeekClient(
            base_url="https://provider.example/v1",
            credential="placeholder",
            model="deepseek-v4-flash",
            transport=FakeTransport(body),
        )
    )

    with pytest.raises(InvalidProviderResponse, match="schema validation"):
        asyncio.run(
            adapter.analyze(
                prompt="redacted", thinking=False, user_id="run:test", context=_context()
            )
        )


def test_adapter_rejects_model_claimed_runtime_failure_status() -> None:
    body = {
        "model": "deepseek-v4-flash",
        "choices": [
            {
                "message": {
                    "content": json.dumps(
                        {
                            "status": "budget_exceeded",
                            "hypotheses": [],
                            "counter_evidence": [],
                            "missing_evidence": [],
                            "citations": [],
                            "confidence": 0.0,
                        }
                    )
                },
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
    }
    adapter = DeepSeekAdapter(
        DeepSeekClient(
            base_url="https://provider.example/v1",
            credential="placeholder",
            model="deepseek-v4-flash",
            transport=FakeTransport(body),
        )
    )

    with pytest.raises(InvalidProviderResponse):
        asyncio.run(
            adapter.analyze(
                prompt="redacted", thinking=False, user_id="run:test", context=_context()
            )
        )
