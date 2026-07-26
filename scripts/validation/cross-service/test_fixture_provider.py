from __future__ import annotations

import importlib.util
import json
import random
import unittest
from pathlib import Path


def load_provider():
    source = Path(__file__).with_name("fixture-provider.py")
    specification = importlib.util.spec_from_file_location("opsmind_fixture_provider", source)
    if specification is None or specification.loader is None:
        raise RuntimeError("fixture provider module cannot be loaded")
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def record(metric: str, evidence_id: str, digest_character: str) -> dict[str, object]:
    return {
        "evidence_id": evidence_id,
        "digest": f"sha256:{digest_character * 64}",
        "canonical_content": json.dumps(
            {"metric": metric, "points": [], "series_count": 1},
            separators=(",", ":"),
        ),
    }


class FixtureProviderSemanticEvidenceTest(unittest.TestCase):
    def test_conflict_roles_do_not_depend_on_randomized_evidence_order(self) -> None:
        provider = load_provider()
        provider._SCENARIO = "C"
        fixture_path = (
            Path(__file__).resolve().parents[3]
            / "evaluation"
            / "scenarios"
            / "conflicting-evidence-regression"
            / "fixture.json"
        )
        fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
        self.assertEqual(
            fixture["signals"],
            [
                {
                    "label": "deployment-correlated-latency",
                    "direction": "supports",
                },
                {
                    "label": "flat-http-error-counts",
                    "direction": "opposes",
                },
            ],
        )
        latency = record(
            "http_request_duration_seconds",
            "10000000-0000-4000-8000-000000000001",
            "a",
        )
        errors = record(
            "http_errors_total",
            "10000000-0000-4000-8000-000000000002",
            "b",
        )
        randomizer = random.Random(73003)

        for _ in range(50):
            evidence = [latency, errors]
            randomizer.shuffle(evidence)
            prompt = "fixture\n" + json.dumps({"metric_evidence": evidence})
            response = provider._analysis_content(prompt)
            self.assertEqual(
                response["citations"][0]["evidence_id"],
                latency["evidence_id"],
            )
            self.assertEqual(
                response["citations"][1]["evidence_id"],
                errors["evidence_id"],
            )
            self.assertEqual(
                response["counter_evidence"],
                ["HTTP error counts remain flat while latency rises."],
            )
            self.assertEqual(
                response["hypotheses"][0]["title"],
                "Synthetic latency regression with flat error counter signal",
            )
            self.assertIn(
                "flat HTTP error counts contradict",
                response["hypotheses"][0]["explanation"],
            )
            self.assertEqual(
                response["citations"][1]["claim"],
                "HTTP error counts stay flat, weakening a broad "
                "deployment-regression diagnosis.",
            )


if __name__ == "__main__":
    unittest.main()
