import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from e2e.hidden_seed_protocol import (
    HiddenSeedEvidenceError,
    aggregate_summaries,
    validate_public_summary,
)


def terminal(*, elapsed: int = 100, outcome: str = "COMPLETED") -> dict:
    return {
        "schemaVersion": 2,
        "routeProfile": "COMPLETION",
        "outcome": outcome,
        "detailCode": "dragon_returned" if outcome == "COMPLETED" else "timeout",
        "goalRevision": 1,
        "hardcore": True,
        "evaluationLocked": True,
        "contaminated": False,
        "hardcoreDead": False,
        "foundationVerified": False,
        "dragonKilled": outcome == "COMPLETED",
        "returnedFromEnd": outcome == "COMPLETED",
        "startedGameTick": 0,
        "finishedGameTick": elapsed,
        "elapsedTicks": elapsed,
        "observedGameTick": elapsed,
    }


def summary(count: int = 1, *, elapsed: int = 100) -> dict:
    results = [
        {
            "caseId": f"case-{index:04d}",
            "seedCommitment": f"{index + 1:064x}",
            "phase": "terminal",
            "wallSeconds": 1.0,
            "terminal": terminal(elapsed=elapsed),
        }
        for index in range(count)
    ]
    return {
        "schemaVersion": 2,
        "protocol": "fresh-hidden-random-seed-hardcore-v2",
        "route": "completion",
        "artifactBinding": {
            "productSha256": "a" * 64,
            "sourceCommit": "b" * 40,
        },
        "cases": count,
        "terminalCases": count,
        "completedCases": count,
        "completedWithinOneHour": count if elapsed <= 72_000 else 0,
        "completedWithinTwoHours": count if elapsed <= 144_000 else 0,
        "completedWithinSixHours": count if elapsed <= 432_000 else 0,
        "oneHourRate": 1.0 if elapsed <= 72_000 else 0.0,
        "twoHourRate": 1.0 if elapsed <= 144_000 else 0.0,
        "sixHourRate": 1.0 if elapsed <= 432_000 else 0.0,
        "results": results,
    }


def failed_summary() -> dict:
    value = summary()
    failed = terminal(elapsed=12_000, outcome="HARDCORE_DEAD")
    failed["hardcoreDead"] = True
    value["results"][0]["terminal"] = failed
    value["completedCases"] = 0
    value["completedWithinOneHour"] = 0
    value["completedWithinTwoHours"] = 0
    value["completedWithinSixHours"] = 0
    value["oneHourRate"] = 0.0
    value["twoHourRate"] = 0.0
    value["sixHourRate"] = 0.0
    return value


class HiddenSeedAggregateTest(unittest.TestCase):
    def test_recomputes_counters_and_rejects_raw_seed(self):
        value = summary()
        self.assertEqual(1, validate_public_summary(value, "completion")["cases"])
        value.pop("artifactBinding")
        with self.assertRaises(HiddenSeedEvidenceError):
            validate_public_summary(value, "completion")
        value = summary()
        value["twoHourRate"] = 0.0
        with self.assertRaises(HiddenSeedEvidenceError):
            validate_public_summary(value, "completion")

    def test_hardcore_death_is_a_valid_executed_failure_in_denominator(self):
        value = failed_summary()
        checked = validate_public_summary(value, "completion")
        self.assertEqual(1, checked["cases"])
        self.assertEqual(0, checked["completedCases"])
        value = summary()
        value["privateSeeds"] = [{"seed": 123}]
        with self.assertRaises(HiddenSeedEvidenceError):
            validate_public_summary(value, "completion")

    def test_m4_aggregate_requires_all_cases_and_unique_commitments(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "shard.json"
            path.write_text(json.dumps(summary(1000)), encoding="utf-8")
            result = aggregate_summaries([path], "completion", minimum_cases=1000)
            self.assertEqual("PASS", result["status"])
            self.assertEqual(1000, result["cases"])
            self.assertEqual(950, result["requiredWithinTwoHours"])
            self.assertEqual(990, result["requiredWithinSixHours"])
            bound = aggregate_summaries(
                [path],
                "completion",
                minimum_cases=1000,
                expected_product_sha256="a" * 64,
                expected_source_commit="b" * 40,
            )
            self.assertEqual("PASS", bound["status"])
            with self.assertRaises(HiddenSeedEvidenceError):
                aggregate_summaries(
                    [path],
                    "completion",
                    minimum_cases=1000,
                    expected_product_sha256="d" * 64,
                )

            duplicate = root / "duplicate.json"
            duplicate.write_text(json.dumps(summary(1000)), encoding="utf-8")
            with self.assertRaises(HiddenSeedEvidenceError):
                aggregate_summaries([path, duplicate], "completion", minimum_cases=1000)

            different_binding = summary(1000)
            different_binding["artifactBinding"]["productSha256"] = "c" * 64
            different = root / "different-binding.json"
            different.write_text(json.dumps(different_binding), encoding="utf-8")
            with self.assertRaises(HiddenSeedEvidenceError):
                aggregate_summaries([path, different], "completion", minimum_cases=1000)

    def test_slow_completion_fails_two_hour_m4_threshold(self):
        value = summary(1000, elapsed=2 * 60 * 60 * 20 + 1)
        with TemporaryDirectory() as directory:
            path = Path(directory) / "slow.json"
            path.write_text(json.dumps(value), encoding="utf-8")
            result = aggregate_summaries([path], "completion", minimum_cases=1000)
        self.assertEqual("FAIL", result["status"])
        self.assertEqual(0, result["completedWithinTwoHours"])


if __name__ == "__main__":
    unittest.main()
