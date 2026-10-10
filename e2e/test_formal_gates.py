import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from e2e.formal_gates import (
    FUNCTIONAL_SLICE_GATES,
    GATE_NAMES,
    run_gate,
)


class FormalGateEntryPointTest(unittest.TestCase):
    def test_plan_names_are_all_exposed(self):
        expected = {
            "e2eFunctional",
            "e2eRendered",
            "e2eChat",
            "e2eMovement",
            "e2eInventory",
            "e2eRestart",
            "e2eXaero",
            "e2eM1",
            "e2eM2",
            "e2eM3",
            "e2eM4Shard",
            "aggregateHiddenSeeds",
            "soak24h",
            "soak100h",
            "recordHumanBaseline",
            "naturalnessReport",
            "mutationGate",
        }
        self.assertEqual(expected, set(GATE_NAMES))
        self.assertTrue(FUNCTIONAL_SLICE_GATES <= set(GATE_NAMES))

    def test_mutation_gate_catches_required_failure_variants(self):
        status, record = run_gate(
            "mutationGate",
            "65.1.0",
            "formal-unit-test",
        )
        self.assertEqual(0, status)
        self.assertEqual("PASS", record["status"])
        self.assertFalse(record["functionalAiClaim"])
        self.assertEqual(10, record["mutationCount"])
        self.assertEqual(10, record["caughtCount"])
        self.assertEqual([], record["survivedMutations"])
        self.assertEqual(
            "DETERMINISTIC_FAULT_INJECTION",
            record["evidenceClass"],
        )
        self.assertNotIn("apiKey", json.dumps(record))

    def test_restart_gate_requires_real_archive_instead_of_single_smoke(self):
        status, record = run_gate(
            "e2eRestart",
            "65.1.0",
            "formal-restart-preflight",
        )
        self.assertEqual(2, status)
        self.assertEqual("NOT_RUN", record["status"])
        self.assertEqual("restart_archive_required", record["reason"])
        self.assertFalse(record["functionalAiClaim"])

    def test_hidden_seed_aggregate_requires_public_shards(self):
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("MCAI_HIDDEN_SEED_SUMMARIES", None)
            status, record = run_gate(
                "aggregateHiddenSeeds",
                "65.1.0",
                "formal-hidden-preflight",
            )
        self.assertEqual(2, status)
        self.assertEqual("NOT_RUN", record["status"])
        self.assertEqual(
            "hidden_seed_summaries_required",
            record["reason"],
        )
        self.assertFalse(record["functionalAiClaim"])

    def test_m1_and_m2_statistics_have_explicit_preflight_boundaries(self):
        for gate, variable, minimum in (
            ("e2eM1", "MCAI_M1_FOUNDATION_SUMMARIES", 100),
            ("e2eM2", "MCAI_M2_COMPLETION_SUMMARIES", 200),
        ):
            with self.subTest(gate=gate), patch.dict(os.environ, {}, clear=False):
                os.environ.pop(variable, None)
                status, record = run_gate(gate, "65.1.0", f"{gate}-preflight")
            self.assertEqual(2, status)
            self.assertEqual("NOT_RUN", record["status"])
            self.assertEqual("hidden_seed_summaries_required", record["reason"])
            self.assertIn(str(minimum), record["requiredEvidence"])

    def test_m3_requires_real_summary_shards(self):
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("MCAI_M3_SUMMARIES", None)
            status, record = run_gate(
                "e2eM3",
                "65.1.0",
                "formal-m3-preflight",
            )
        self.assertEqual(2, status)
        self.assertEqual("NOT_RUN", record["status"])
        self.assertEqual("m3_summaries_required", record["reason"])
        self.assertFalse(record["functionalAiClaim"])

    def test_functional_preflight_missing_resources_are_archived(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root = Path(directory) / "run"
            run_root.mkdir()
            (run_root / "manifest.json").write_text(
                json.dumps({
                    "status": "NOT_RUN",
                    "preflight": {
                        "missing": [
                            "linux_host",
                            "Xvfb",
                            "MCAI_BASE_URL",
                        ],
                    },
                }),
                encoding="utf-8",
            )
            record_file = Path(directory) / "record.json"
            fake_process = type(
                "ProcessResult",
                (),
                {"returncode": 2, "stdout": str(run_root) + "\n"},
            )()
            with patch(
                "e2e.formal_gates.source_label",
                return_value=("no-commit-dirty", False),
            ), patch(
                "e2e.formal_gates.newest_run_root",
                return_value=run_root,
            ), patch(
                "e2e.formal_gates.subprocess.run",
                return_value=fake_process,
            ), patch(
                "e2e.formal_gates.record_path",
                return_value=record_file,
            ):
                status, record = run_gate(
                    "e2eChat",
                    "65.1.1",
                    "formal-preflight-evidence",
                )
            self.assertEqual(2, status)
            self.assertEqual("NOT_RUN", record["status"])
            self.assertEqual(
                ["linux_host", "Xvfb", "MCAI_BASE_URL"],
                record["preflightMissing"],
            )
            self.assertEqual(
                [
                    "preflight:linux_host",
                    "preflight:Xvfb",
                    "preflight:MCAI_BASE_URL",
                ],
                record["missingEvidence"],
            )
            self.assertEqual(
                "functional_preflight_missing:linux_host,Xvfb,MCAI_BASE_URL",
                record["reason"],
            )
            self.assertFalse(record["functionalAiClaim"])


if __name__ == "__main__":
    unittest.main()
