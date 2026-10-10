import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from e2e.ci_evidence_summary import FUNCTIONAL_SCENARIO, render


class CiEvidenceSummaryTest(unittest.TestCase):
    def test_server_smoke_cannot_be_reported_as_functional_pass(self) -> None:
        with TemporaryDirectory() as directory:
            results = Path(directory)
            run = results / "commit" / "smoke"
            run.mkdir(parents=True)
            self.write_json(
                run / "manifest.json",
                {
                    "runId": "smoke",
                    "status": "PASS",
                    "scenario": "dedicated_server_exact_jar_lifecycle_only",
                },
            )

            summary, status = render(results)

            self.assertEqual(1, status)
            self.assertIn("No evidence manifest was produced", summary)
            self.assertNotIn("**PASS**", summary)

    def test_only_verified_functional_verdict_can_pass(self) -> None:
        with TemporaryDirectory() as directory:
            results = Path(directory)
            run = results / "commit" / "functional"
            run.mkdir(parents=True)
            self.write_json(
                run / "manifest.json",
                {
                    "runId": "functional",
                    "status": "RUNNING",
                    "scenario": FUNCTIONAL_SCENARIO,
                    "evidenceClass": "RELEASE_CANDIDATE",
                    "platform": {"forge": "65.1.0"},
                    "artifacts": {
                        "productionJar": {"sha256": "abc123"}
                    },
                    "model": {
                        "model": "test-model",
                        "baseUrlHost": "provider.example",
                        "credentialPresent": True,
                        "credentialRunSaltedFingerprint": "not-for-summary",
                    },
                },
            )
            self.write_json(
                run / "e2e-verdict.json",
                {
                    "status": "PASS",
                    "observerAiDisplacement": 4.25,
                    "causalModelTrace": {"requestId": "brain-2-1"},
                    "inventoryCausalModelTrace": {
                        "requestId": "brain-3-1"
                    },
                },
            )

            summary, status = render(results)

            self.assertEqual(0, status)
            self.assertIn("**PASS**", summary)
            self.assertIn("brain-2-1", summary)
            self.assertIn("brain-3-1", summary)
            self.assertIn("abc123", summary)
            self.assertNotIn("not-for-summary", summary)

    def test_infrastructure_error_cannot_inherit_running_as_pass(self) -> None:
        with TemporaryDirectory() as directory:
            results = Path(directory)
            run = results / "commit" / "functional"
            run.mkdir(parents=True)
            self.write_json(
                run / "manifest.json",
                {
                    "runId": "functional",
                    "status": "RUNNING",
                    "scenario": FUNCTIONAL_SCENARIO,
                },
            )
            self.write_json(
                run / "infrastructure-error.json",
                {"type": "RuntimeError", "message": "client exited"},
            )

            summary, status = render(results)

            self.assertEqual(1, status)
            self.assertIn("**ERROR**", summary)
            self.assertIn("client exited", summary)

    @staticmethod
    def write_json(path: Path, value: object) -> None:
        path.write_text(
            json.dumps(value, ensure_ascii=False),
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
