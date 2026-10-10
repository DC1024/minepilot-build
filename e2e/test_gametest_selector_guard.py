import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILD_GRADLE = ROOT / "build.gradle"


class GameTestSelectorGuardTest(unittest.TestCase):
    """Keep the live-model Gradle guard from swallowing offline regressions."""

    def test_offline_golden_apple_is_excluded_from_live_model_heuristic(self):
        source = BUILD_GRADLE.read_text(encoding="utf-8")
        self.assertIn(
            "selector.contains('critical_golden_apple')",
            source,
        )
        self.assertIn(
            "!selector.contains('offline_critical_golden_apple')",
            source,
        )


if __name__ == "__main__":
    unittest.main()
