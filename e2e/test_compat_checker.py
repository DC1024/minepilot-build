import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "scripts" / "validate-compat.py"
DECLARATION = ROOT / "compat" / "forge-lines.toml"


class CompatibilityCheckerTest(unittest.TestCase):
    def test_checked_in_declaration_is_machine_valid(self):
        result = subprocess.run(
            [sys.executable, str(CHECKER), "--file", str(DECLARATION), "--json"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        payload = json.loads(result.stdout)
        self.assertEqual(payload["status"], "PASS")
        self.assertFalse(payload["lines"][0]["formalMatrixComplete"])

    def test_legacy_shape_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "legacy.toml"
            path.write_text(
                'schemaVersion = 1\n'
                '[[lines]]\n'
                'forge_major = 65\n',
                encoding="utf-8",
            )
            result = subprocess.run(
                [sys.executable, str(CHECKER), "--file", str(path), "--json"],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
        self.assertNotEqual(result.returncode, 0)
        payload = json.loads(result.stdout)
        self.assertEqual(payload["status"], "FAIL")
        self.assertTrue(
            any("legacy [[lines]]" in error for error in payload["errors"])
        )


if __name__ == "__main__":
    unittest.main()
