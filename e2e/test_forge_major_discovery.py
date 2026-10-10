from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "discover-forge-lines.py"
DECLARATION = ROOT / "compat" / "forge-lines.toml"


class ForgeMajorDiscoveryTest(unittest.TestCase):
    def run_discovery(
        self,
        promotions: dict,
        index_html: str | None = None,
        check_patches: bool = False,
    ) -> tuple[int, dict]:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "promotions.json"
            path.write_text(json.dumps(promotions), encoding="utf-8")
            index_path = Path(directory) / "index.html"
            if index_html is not None:
                index_path.write_text(index_html, encoding="utf-8")
            command = [
                sys.executable,
                str(SCRIPT),
                "--declaration",
                str(DECLARATION),
                "--promotions-file",
                str(path),
                "--json",
            ]
            if check_patches:
                command += ["--check-patches", "--index-file", str(index_path)]
            result = subprocess.run(
                command,
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
        return result.returncode, json.loads(result.stdout)

    def test_current_promotions_have_a_declared_adapter(self):
        code, payload = self.run_discovery(
            {"promos": {"26.2-latest": "65.1.0", "26.2-recommended": "65.1.0"}}
        )
        self.assertEqual(code, 0, payload)
        self.assertEqual(payload["status"], "PASS")
        self.assertEqual(payload["declaredForgeMajors"], [65])

    def test_new_major_fails_closed(self):
        code, payload = self.run_discovery(
            {
                "promos": {
                    "26.2-latest": "65.1.0",
                    "26.3-latest": "66.0.0",
                }
            }
        )
        self.assertNotEqual(code, 0)
        self.assertEqual(payload["status"], "FAIL")
        self.assertEqual(payload["missingAdapters"][0]["forgeMajor"], 66)

    def test_old_major_is_ignored(self):
        code, payload = self.run_discovery(
            {"promos": {"26.1.2-latest": "64.1.0"}}
        )
        self.assertEqual(code, 0, payload)
        self.assertEqual(payload["observed"], [])

    def test_official_patch_index_must_match_lock(self):
        index = "".join(
            f'<td class="download-version">{patch}</td>'
            for patch in [
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
                "65.1.2",
            ]
        )
        code, payload = self.run_discovery(
            {"promos": {"26.2-latest": "65.1.0"}},
            index,
            check_patches=True,
        )
        self.assertEqual(code, 0, payload)
        self.assertEqual(payload["missingPatches"], [])
        self.assertEqual(payload["stalePatches"], [])

    def test_new_official_patch_fails_closed(self):
        code, payload = self.run_discovery(
            {"promos": {"26.2-latest": "65.1.0"}},
            '<td class="download-version">65.0.0</td>'
            '<td class="download-version">65.1.0</td>'
            '<td class="download-version">65.2.0</td>',
            check_patches=True,
        )
        self.assertNotEqual(code, 0)
        self.assertEqual(payload["missingPatches"][0]["patch"], "65.2.0")


if __name__ == "__main__":
    unittest.main()
