#!/usr/bin/env python3
"""Deterministic fault-injection gate for the external evidence verifier.

This does not claim real gameplay.  It builds one canonical evidence bundle
using the same verifier exercised by the real Actor/Observer runner, applies
one named failure mutation at a time, and requires the verifier to reject it.
The packet audit is checked through the same production SQLite transport event
that the real functional verifier consumes.
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from e2e.orchestrator import verify_evidence
from e2e.test_orchestrator import OrchestratorEvidenceTest, TEST_NONCE


MUTATIONS = (
    "CHAT_INPUT_DROPPED",
    "AI_REPLY_SUPPRESSED",
    "MODEL_TALK_ONLY",
    "SKILL_START_NOOP",
    "MOVEMENT_NOOP",
    "MOVEMENT_TELEPORT_CHEAT",
    "MENU_CLICK_NOOP",
    "DIRECT_INVENTORY_WRITE",
    "FALSE_COMPLETION_SPEECH",
    "PACKET_LEAK",
)


def read_lines(path: Path) -> list[dict[str, Any]]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def write_lines(path: Path, values: list[dict[str, Any]]) -> None:
    path.write_text(
        "".join(json.dumps(value, ensure_ascii=False) + "\n" for value in values),
        encoding="utf-8",
    )


def mutate_oracle(
    run_root: Path,
    predicate: Callable[[dict[str, Any]], bool],
) -> None:
    path = run_root / "oracle-events.jsonl"
    write_lines(path, [event for event in read_lines(path) if not predicate(event)])


def update_oracle_events(
    run_root: Path,
    updater: Callable[[dict[str, Any]], None],
) -> None:
    path = run_root / "oracle-events.jsonl"
    events = read_lines(path)
    for event in events:
        updater(event)
    write_lines(path, events)


def update_actor_events(
    run_root: Path,
    updater: Callable[[dict[str, Any]], None],
) -> None:
    path = run_root / "actor-client-events.jsonl"
    events = read_lines(path)
    for event in events:
        updater(event)
    write_lines(path, events)


def update_observer_events(
    run_root: Path,
    updater: Callable[[dict[str, Any]], None],
) -> None:
    path = run_root / "observer-client-events.jsonl"
    events = read_lines(path)
    for event in events:
        updater(event)
    write_lines(path, events)


def update_production_events(
    run_root: Path,
    predicate: Callable[[str], bool],
    updater: Callable[[dict[str, Any]], None] | None = None,
) -> None:
    database = next(
        (run_root / "instances" / "server").glob(
            "world-*/data/mcai_companion/memory.db"
        ),
        None,
    )
    if database is None:
        raise AssertionError("mutation bundle has no production database")
    connection = sqlite3.connect(database)
    try:
        rows = connection.execute(
            "SELECT sequence, payload_json FROM event_log"
        ).fetchall()
        for sequence, payload_json in rows:
            payload = json.loads(payload_json)
            event_type = str(
                connection.execute(
                    "SELECT event_type FROM event_log WHERE sequence = ?",
                    (sequence,),
                ).fetchone()[0]
            )
            if not predicate(event_type):
                continue
            if updater is None:
                connection.execute(
                    "DELETE FROM event_log WHERE sequence = ?",
                    (sequence,),
                )
            else:
                updater(payload)
                connection.execute(
                    "UPDATE event_log SET payload_json = ? WHERE sequence = ?",
                    (json.dumps(payload), sequence),
                )
        connection.commit()
    finally:
        connection.close()


def apply_mutation(run_root: Path, name: str) -> None:
    if name == "CHAT_INPUT_DROPPED":
        mutate_oracle(
            run_root,
            lambda event: event.get("type") == "server_chat_received",
        )
        update_actor_events(
            run_root,
            lambda event: event.update({"type": "client_idle"})
            if event.get("type") == "actor_chat_sent"
            else None,
        )
    elif name == "AI_REPLY_SUPPRESSED":
        mutate_oracle(
            run_root,
            lambda event: event.get("type") == "inventory_chat_received",
        )
        update_actor_events(
            run_root,
            lambda event: event.update({"type": "client_idle"})
            if event.get("type") == "ai_chat_followup_received_by_actor"
            else None,
        )
    elif name == "MODEL_TALK_ONLY":
        update_production_events(
            run_root,
            lambda event_type: event_type == "low_level_actions_issued",
        )
    elif name == "SKILL_START_NOOP":
        update_production_events(
            run_root,
            lambda event_type: event_type == "low_level_actions_issued",
            lambda payload: payload.update({"action": "noop", "outcome": "NOOP"}),
        )
    elif name == "MOVEMENT_NOOP":
        update_observer_events(
            run_root,
            lambda event: event["ai"].update({"x": 0.0, "z": 0.0})
            if event.get("type") == "client_world_sample"
            else None,
        )
        update_oracle_events(
            run_root,
            lambda event: event.update({
                "maxDisplacement": 0.0,
                "finalActorDistance": 12.0,
                "maxTickStep": 0.0,
            })
            if event.get("type") == "server_observed_world_delta"
            else None,
        )
    elif name == "MOVEMENT_TELEPORT_CHEAT":
        update_observer_events(
            run_root,
            lambda event: event["ai"].update({"x": 50.0})
            if event.get("type") == "client_world_sample"
            and event.get("sequence") == 2
            else None,
        )
        update_oracle_events(
            run_root,
            lambda event: event.update({"maxTickStep": 50.0})
            if event.get("type") == "server_observed_world_delta"
            else None,
        )
    elif name in {"MENU_CLICK_NOOP", "DIRECT_INVENTORY_WRITE"}:
        mutate_oracle(
            run_root,
            lambda event: event.get("type")
            in {"server_vanilla_item_pickup", "inventory_transaction_oracle_passed"},
        )
        update_oracle_events(
            run_root,
            lambda event: event.pop("vanillaPickupObserved", None)
            or event.pop("vanillaPickupCount", None)
            if event.get("type") == "server_observed_inventory_delta"
            else None,
        )
    elif name == "FALSE_COMPLETION_SPEECH":
        update_production_events(
            run_root,
            lambda event_type: event_type
            in {"decision_revision_accepted", "skill_started", "low_level_actions_issued"},
        )
    elif name == "PACKET_LEAK":
        update_production_events(
            run_root,
            lambda event_type: event_type == "connection_transport_audit",
            lambda payload: payload.update({
                "outboundQueueHighWatermark": 4096,
                "unreleasedOutboundPackets": 2,
                "disconnectHandled": False,
            }),
        )
    else:
        raise AssertionError(f"unknown mutation {name}")


def run() -> dict[str, Any]:
    results: dict[str, dict[str, Any]] = {}
    with tempfile.TemporaryDirectory(prefix="mcai-mutation-") as directory:
        root = Path(directory)
        for name in MUTATIONS:
            run_root = root / name
            run_root.mkdir()
            expected_hash = OrchestratorEvidenceTest.write_functional_bundle(
                run_root
            )
            baseline = verify_evidence(
                run_root,
                expected_hash,
                TEST_NONCE,
            )
            if baseline.get("status") != "PASS":
                raise AssertionError(
                    f"canonical evidence did not pass: {baseline}"
                )
            if name == "PACKET_LEAK":
                apply_mutation(run_root, name)
                mutated = verify_evidence(
                    run_root,
                    expected_hash,
                    TEST_NONCE,
                )
                caught = (
                    mutated.get("status") == "FAIL"
                    and "production-audit:connection-transport-health"
                    in mutated.get("missingEvidence", [])
                )
                results[name] = {
                    "status": "CAUGHT" if caught else "SURVIVED",
                    "detector": "production_transport_audit",
                }
                continue
            apply_mutation(run_root, name)
            mutated = verify_evidence(
                run_root,
                expected_hash,
                TEST_NONCE,
            )
            caught = mutated.get("status") == "FAIL"
            results[name] = {
                "status": "CAUGHT" if caught else "SURVIVED",
                "missingEvidence": mutated.get("missingEvidence", []),
            }
    survived = [name for name, result in results.items()
                if result["status"] != "CAUGHT"]
    return {
        "schemaVersion": 1,
        "status": "PASS" if not survived else "FAIL",
        "mutationCount": len(MUTATIONS),
        "caughtCount": len(MUTATIONS) - len(survived),
        "survivedMutations": survived,
        "mutations": results,
        "functionalAiClaim": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    try:
        result = run()
    except Exception as error:  # pragma: no cover - gate must fail closed
        result = {
            "schemaVersion": 1,
            "status": "FAIL",
            "functionalAiClaim": False,
            "error": type(error).__name__ + ": " + str(error),
        }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result.get("status") == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
