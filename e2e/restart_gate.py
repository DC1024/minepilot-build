#!/usr/bin/env python3
"""Verify a real two-start exact-JAR restart archive.

The verifier is intentionally evidence-only.  It never starts Minecraft,
creates a goal, edits SQLite, or treats a single lifecycle smoke as a restart
pass.  A real run must point ``--run-root`` at an archive whose production
database contains at least two ordered ``runtime_lifecycle_audit`` startup
rows for the same companion UUID and a final stop row.
"""

from __future__ import annotations

import argparse
import datetime as dt
import glob
import json
import sqlite3
from pathlib import Path
from typing import Any


LIFECYCLE_EVENT = "runtime_lifecycle_audit"


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace(
        "+00:00", "Z"
    )


def read_lifecycle_events(database: Path) -> list[dict[str, Any]]:
    # Use a read-only SQL connection with a generous busy timeout.  The
    # product database uses WAL and the Forge launcher may still be releasing
    # its final shared-memory lock after the Java process reports exit.  The
    # previous ``mode=ro`` URI could fail immediately with "unable to open
    # database file" during that tail and turn a valid archive into an empty
    # evidence set.  ``query_only`` prevents writes while allowing SQLite to
    # establish its normal WAL read snapshot.
    connection = sqlite3.connect(
        str(database.resolve()),
        timeout=30.0,
    )
    try:
        connection.execute("PRAGMA query_only=ON")
        connection.execute("PRAGMA busy_timeout=30000")
        rows = connection.execute(
            """
            SELECT sequence, occurred_at, payload_json, world_revision,
                   goal_revision
            FROM event_log
            WHERE event_type = ?
            ORDER BY sequence ASC
            """,
            (LIFECYCLE_EVENT,),
        ).fetchall()
    finally:
        connection.close()
    events: list[dict[str, Any]] = []
    for sequence, occurred_at, payload_json, world_revision, goal_revision in rows:
        try:
            payload = json.loads(payload_json)
        except (TypeError, json.JSONDecodeError):
            payload = {}
        events.append({
            "sequence": int(sequence),
            "atUtc": str(occurred_at),
            "payload": payload if isinstance(payload, dict) else {},
            "worldRevision": int(world_revision),
            "goalRevision": int(goal_revision),
        })
    return events


def verify(run_root: Path) -> dict[str, Any]:
    databases = sorted(
        Path(path)
        for path in glob.glob(
            str(run_root / "instances/server/world-*/data/mcai_companion/memory.db")
        )
    )
    missing: list[str] = []
    if len(databases) != 1:
        missing.append("exactly_one_production_memory_database")
        events: list[dict[str, Any]] = []
    else:
        try:
            events = read_lifecycle_events(databases[0])
        except (OSError, sqlite3.Error):
            missing.append("readable_production_memory_database")
            events = []

    starts = [
        event for event in events
        if event.get("payload", {}).get("phase") == "started"
    ]
    stops = [
        event for event in events
        if event.get("payload", {}).get("phase") == "stopping"
    ]
    if len(starts) < 2:
        missing.append("two_ordered_runtime_start_rows")
    if not stops:
        missing.append("runtime_stop_row")

    uuids = {
        event.get("payload", {}).get("companionUuid")
        for event in starts
        if isinstance(event.get("payload", {}).get("companionUuid"), str)
    }
    if len(uuids) != 1:
        missing.append("stable_companion_uuid_across_starts")

    revisions = [
        event.get("payload", {}).get("goalRevision")
        for event in starts
    ]
    if (
        not revisions
        or any(not isinstance(value, int) or value < 0 for value in revisions)
        or revisions != sorted(revisions)
    ):
        missing.append("monotonic_saved_goal_revision")

    for event in starts:
        payload = event.get("payload", {})
        if not isinstance(payload.get("memorySchemaVersion"), int):
            missing.append("startup_memory_schema_version")
            break
        if any(
            field not in payload
            for field in (
                "bodyEverSpawned",
                "hardcoreDead",
                "evaluationLocked",
                "evaluationContaminated",
            )
        ):
            missing.append("startup_saved_data_state")
            break

    return {
        "schemaVersion": 1,
        "verifiedAtUtc": utc_now(),
        "status": "PASS" if not missing else "FAIL",
        "runRoot": str(run_root),
        "missingEvidence": missing,
        "databaseCount": len(databases),
        "lifecycleEventCount": len(events),
        "startCount": len(starts),
        "stopCount": len(stops),
        "companionUuidCount": len(uuids),
        "startGoalRevisions": revisions,
        "functionalAiClaim": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-root", required=True, type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    result = verify(args.run_root)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
