import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_PATCHES = [
    "65.0.0",
    "65.0.1",
    "65.0.2",
    "65.0.3",
    "65.0.4",
    "65.0.5",
    "65.0.6",
    "65.0.7",
    "65.0.8",
    "65.0.9",
    "65.1.0",
    "65.1.1",
]


class WorkflowMatrixTest(unittest.TestCase):
    def test_matrix_lists_every_locked_forge_65_patch_once(self):
        workflow = (
            ROOT / ".github" / "workflows" /
            "real-client-functional-e2e-matrix.yml"
        ).read_text(encoding="utf-8")
        listed = [
            patch
            for patch in EXPECTED_PATCHES
            if f"          - {patch}" in workflow
        ]
        self.assertEqual(EXPECTED_PATCHES, listed)
        self.assertEqual(
            len(EXPECTED_PATCHES),
            sum(workflow.count(f"          - {patch}") for patch in EXPECTED_PATCHES),
        )

    def test_reusable_workflow_choice_lists_every_locked_patch_once(self):
        workflow = (
            ROOT / ".github" / "workflows" /
            "real-client-functional-e2e.yml"
        ).read_text(encoding="utf-8")
        listed = [
            patch
            for patch in EXPECTED_PATCHES
            if f"          - {patch}" in workflow
        ]
        self.assertEqual(EXPECTED_PATCHES, listed)
        self.assertEqual(
            len(EXPECTED_PATCHES),
            sum(workflow.count(f"          - {patch}") for patch in EXPECTED_PATCHES),
        )

    def test_model_secret_is_step_scoped_not_job_scoped(self):
        workflow = (
            ROOT / ".github" / "workflows" /
            "real-client-functional-e2e.yml"
        ).read_text(encoding="utf-8")
        self.assertNotIn(
            "    env:\n      MCAI_API_KEY: ${{ secrets.MCAI_API_KEY }}",
            workflow,
        )
        self.assertEqual(
            3,
            workflow.count("          MCAI_API_KEY: ${{ secrets.MCAI_API_KEY }}"),
        )


if __name__ == "__main__":
    unittest.main()
