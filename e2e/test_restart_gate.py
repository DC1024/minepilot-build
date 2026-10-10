import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from e2e.restart_gate import verify


class RestartEvidenceTest(unittest.TestCase):
    @staticmethod
    def write_database(root: Path, phases: list[str], revisions: list[int]) -> None:
        database = (
            root / "instances" / "server" / "world-test" / "data"
            / "mcai_companion" / "memory.db"
        )
        database.parent.mkdir(parents=True)
        connection = sqlite3.connect(database)
        try:
            connection.execute(
                """
                CREATE TABLE event_log(
                    sequence INTEGER PRIMARY KEY,
                    occurred_at TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    source TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    world_revision INTEGER NOT NULL,
                    goal_revision INTEGER NOT NULL
                )
                """
            )
            for sequence, (phase, revision) in enumerate(
                zip(phases, revisions),
                start=1,
            ):
                connection.execute(
                    "INSERT INTO event_log VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (
                        sequence,
                        f"2026-08-09T02:00:{sequence:02d}Z",
                        "runtime_lifecycle_audit",
                        "runtime",
                        json.dumps(
                            {
                                "phase": phase,
                                "companionUuid":
                                    "00000000-0000-0000-0000-000000000001",
                                "goalRevision": revision,
                                "memorySchemaVersion": 1,
                                "bodyEverSpawned": True,
                                "hardcoreDead": False,
                                "evaluationLocked": False,
                                "evaluationContaminated": False,
                            }
                        ),
                        revision,
                        revision,
                    ),
                )
            connection.commit()
        finally:
            connection.close()

    def test_requires_two_starts_and_accepts_stable_persisted_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_database(
                root,
                ["started", "stopping", "started", "stopping"],
                [4, 4, 4, 4],
            )
            result = verify(root)
            self.assertEqual("PASS", result["status"])
            self.assertEqual(2, result["startCount"])
            self.assertEqual(2, result["stopCount"])
            self.assertFalse(result["functionalAiClaim"])

    def test_rejects_uuid_or_revision_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_database(root, ["started", "started", "stopping"], [4, 2, 2])
            database = next(root.glob("instances/server/world-*/data/mcai_companion/memory.db"))
            connection = sqlite3.connect(database)
            try:
                payload = json.loads(
                    connection.execute(
                        "SELECT payload_json FROM event_log WHERE sequence=2"
                    ).fetchone()[0]
                )
                payload["companionUuid"] = "00000000-0000-0000-0000-000000000002"
                connection.execute(
                    "UPDATE event_log SET payload_json=? WHERE sequence=2",
                    (json.dumps(payload),),
                )
                connection.commit()
            finally:
                connection.close()
            result = verify(root)
            self.assertEqual("FAIL", result["status"])
            self.assertIn("stable_companion_uuid_across_starts", result["missingEvidence"])
            self.assertIn("monotonic_saved_goal_revision", result["missingEvidence"])


if __name__ == "__main__":
    unittest.main()
