import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from e2e.m3_protocol import (
    M3EvidenceError,
    aggregate_summaries,
    validate_summary,
)


def _case(case_id, category, capability, variant):
    return {
        "caseId": case_id,
        "category": category,
        "capability": capability,
        "variantKey": variant,
        "unseenVariant": True,
        "naturalLanguage": True,
        "status": "PASS",
        "realDedicatedServer": True,
        "realClient": True,
        "realModel": True,
        "observerVerified": True,
        "noHumanIntervention": True,
        "noCheatCommands": True,
        "noDirectMutation": True,
        "restartVerified": True,
        "chunkUnloadVerified": True,
        "playerInterruptionVerified": True,
    }


def _summary(cases):
    return {
        "schemaVersion": 1,
        "protocol": "real-m3-companion-v1",
        "phase": "terminal",
        "artifactBinding": {
            "productSha256": "a" * 64,
            "sourceCommit": "b" * 40,
        },
        "cases": cases,
        "waypointCount": 10_000,
        "assetCount": 100_000,
        "soakHours": 100,
        "waypointQueryP95Millis": 50,
        "assetQueryP95Millis": 50,
        "routeQueryP95Millis": 100,
        "restartVerified": True,
        "chunkUnloadVerified": True,
        "playerInterruptionVerified": True,
        "memoryStressVerified": True,
    }


class M3ProtocolTest(unittest.TestCase):
    def test_real_flags_and_private_fields_are_fail_closed(self):
        raw = _summary([
            _case("m3-case-0001", "companion", "follow", "forest-a"),
        ])
        raw["cases"][0]["realModel"] = False
        with self.assertRaises(M3EvidenceError):
            validate_summary(raw)

        raw = _summary([
            _case("m3-case-0001", "companion", "follow", "forest-a"),
        ])
        raw["apiKey"] = "must-never-be-published"
        with self.assertRaises(M3EvidenceError):
            validate_summary(raw)

    def test_partial_matrix_does_not_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "summary.json"
            path.write_text(
                json.dumps(_summary([
                    _case("m3-case-0001", "companion", "follow", "forest-a"),
                    _case("m3-case-0002", "building", "shelter", "plain-a"),
                    _case("m3-case-0003", "farm", "wheat", "soil-a"),
                    _case("m3-case-0004", "machine", "item_sorter", "chest-a"),
                ])),
                encoding="utf-8",
            )
            result = aggregate_summaries([path])
            self.assertEqual("FAIL", result["status"])
            self.assertIn("wheat", result["missingFarmCapabilities"])
            self.assertIn("item_sorter", result["missingMachineCapabilities"])

    def test_complete_small_fixture_proves_protocol_wiring_only(self):
        # Reduce thresholds only for this protocol unit test.  The production
        # constants remain the full M3 acceptance matrix.
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "summary.json"
            path.write_text(
                json.dumps(_summary([
                    _case("m3-case-0001", "companion", "follow", "forest-a"),
                    _case("m3-case-0002", "building", "shelter", "plain-a"),
                    _case("m3-case-0003", "farm", "wheat", "soil-a"),
                    _case("m3-case-0004", "machine", "item_sorter", "chest-a"),
                ])),
                encoding="utf-8",
            )
            with patch("e2e.m3_protocol.MIN_COMPANION_CASES", 1), \
                    patch("e2e.m3_protocol.MIN_BUILDING_CASES", 1), \
                    patch("e2e.m3_protocol.MIN_VARIANTS_PER_CAPABILITY", 1), \
                    patch("e2e.m3_protocol.REQUIRED_FARM_CAPABILITIES", {"wheat"}), \
                    patch("e2e.m3_protocol.REQUIRED_MACHINE_CAPABILITIES", {"item_sorter"}):
                result = aggregate_summaries([path])
            self.assertEqual("PASS", result["status"])
            self.assertEqual(4, result["caseCount"])


if __name__ == "__main__":
    unittest.main()
