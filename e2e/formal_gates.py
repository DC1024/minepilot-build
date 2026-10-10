#!/usr/bin/env python3
"""Explicit formal-gate entry points.

The project plan names more gates than the current real-client slice can
execute.  This module gives every named gate a stable command and an archived
status record.  A missing Linux/client/model prerequisite, a dirty source tree,
or an unimplemented scenario is recorded as ``NOT_RUN``; none of those states
can be promoted to PASS by this helper.  Restart is a separate evidence
boundary: it accepts only an archived two-boot production database, never a
single lifecycle smoke or a synthetic sidecar.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "e2e" / "results" / "formal-gates"
SAFE_FORGE_VERSION = re.compile(r"65\.\d{1,3}\.\d{1,3}")

if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from e2e.m3_protocol import (  # noqa: E402
    M3EvidenceError,
    aggregate_summaries as aggregate_m3_summaries,
)

GATE_NAMES = (
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
)

# The current external slice proves these dimensions in one causal run.  The
# restart archive and mutation runner have separate verifiers; the remaining
# gates need their own scenarios/statistical protocol and therefore stay
# explicit NOT_RUN until implemented.
FUNCTIONAL_SLICE_GATES = {
    "e2eFunctional",
    "e2eRendered",
    "e2eChat",
    "e2eMovement",
    "e2eInventory",
}


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace(
        "+00:00",
        "Z",
    )


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def source_label() -> tuple[str, bool]:
    commit = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
    ).stdout.strip()
    if len(commit) != 40 or any(
        character not in "0123456789abcdef" for character in commit
    ):
        commit = "no-commit"
    dirty = bool(
        subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        ).stdout.strip()
    )
    label = commit[:12] + ("-dirty" if dirty else "")
    return label, not dirty and commit != "no-commit"


def newest_run_root(output: str) -> Path | None:
    for line in reversed(output.splitlines()):
        candidate = Path(line.strip())
        if candidate.is_dir() and (candidate / "manifest.json").is_file():
            return candidate
    return None


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def record_path(label: str, gate: str) -> Path:
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return RESULTS / label / f"{stamp}-{gate}.json"


def run_gate(gate: str, forge_version: str, nonce: str) -> tuple[int, dict[str, Any]]:
    label, release_eligible = source_label()
    record: dict[str, Any] = {
        "schemaVersion": 1,
        "gate": gate,
        "createdAtUtc": utc_now(),
        "status": "NOT_RUN",
        "releaseEligibleSource": release_eligible,
        "sourceLabel": label,
        "forgeVersion": forge_version,
        "functionalAiClaim": False,
    }

    if gate not in GATE_NAMES:
        record.update({
            "status": "NOT_RUN",
            "reason": "unknown_gate",
        })
        target = record_path(label, gate.replace("/", "_"))
        atomic_json(target, record)
        return 2, record

    if gate == "mutationGate":
        process = subprocess.run(
            [
                sys.executable,
                str(ROOT / "e2e" / "mutation_gate.py"),
                "--json",
            ],
            cwd=ROOT,
            env=os.environ.copy(),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        record["underlyingReturnCode"] = process.returncode
        try:
            mutation = json.loads(process.stdout)
        except json.JSONDecodeError:
            mutation = {
                "status": "FAIL",
                "error": "mutation_runner_invalid_json",
            }
        record.update(mutation)
        record["evidenceClass"] = "DETERMINISTIC_FAULT_INJECTION"
        record["functionalAiClaim"] = False
        target = record_path(label, gate)
        atomic_json(target, record)
        return (
            0 if record.get("status") == "PASS" else 1,
            record,
        )

    if gate == "e2eRestart":
        archive = os.environ.get("MCAI_RESTART_RUN_ROOT", "").strip()
        if not archive:
            record.update({
                "reason": "restart_archive_required",
                "requiredEvidence": (
                    "one archived exact-JAR run directory containing two "
                    "ordered runtime_lifecycle_audit startup rows"
                ),
                "evidenceClass": "INFRASTRUCTURE_PRECHECK",
            })
            target = record_path(label, gate)
            atomic_json(target, record)
            return 2, record
        process = subprocess.run(
            [
                sys.executable,
                str(ROOT / "e2e" / "restart_gate.py"),
                "--run-root",
                archive,
                "--json",
            ],
            cwd=ROOT,
            env=os.environ.copy(),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        record["underlyingReturnCode"] = process.returncode
        try:
            restart = json.loads(process.stdout)
        except json.JSONDecodeError:
            restart = {
                "status": "FAIL",
                "missingEvidence": ["restart_verifier_invalid_json"],
            }
        record.update({
            "underlying": restart,
            "evidenceClass": "RESTART_ARCHIVE",
        })
        if restart.get("status") == "PASS" and release_eligible:
            record["status"] = "PASS"
        elif restart.get("status") == "FAIL":
            record["status"] = "FAIL"
            record["reason"] = "restart_archive_verdict_failed"
        else:
            record["reason"] = "restart_archive_passed_on_non_release_source"
        target = record_path(label, gate)
        atomic_json(target, record)
        return (
            0 if record["status"] == "PASS" else
            1 if record["status"] == "FAIL" else 2,
            record,
        )

    hidden_summary_specs = {
        "e2eM1": (
            "MCAI_M1_FOUNDATION_SUMMARIES",
            "foundation",
            100,
        ),
        "e2eM2": (
            "MCAI_M2_COMPLETION_SUMMARIES",
            "completion",
            200,
        ),
        "aggregateHiddenSeeds": (
            "MCAI_HIDDEN_SEED_SUMMARIES",
            "completion",
            1000,
        ),
    }
    if gate in hidden_summary_specs:
        environment_name, route, minimum_cases = hidden_summary_specs[gate]
        configured = os.environ.get(environment_name, "")
        summary_paths = [
            Path(value)
            for value in configured.split(os.pathsep)
            if value.strip()
        ]
        if not summary_paths:
            record.update({
                "reason": "hidden_seed_summaries_required",
                "requiredEvidence": (
                    "one or more executed public summary.json files in "
                    f"{environment_name}, with at least {minimum_cases} "
                    f"unique Hardcore {route} cases"
                ),
                "evidenceClass": "INFRASTRUCTURE_PRECHECK",
            })
            target = record_path(label, gate)
            atomic_json(target, record)
            return 2, record
        expected_product = os.environ.get(
            "MCAI_EXPECTED_PRODUCT_SHA256",
            "",
        ).strip().lower()
        expected_source = None
        if release_eligible:
            expected_source = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=ROOT,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=False,
            ).stdout.strip().lower()
            if not re.fullmatch(r"[0-9a-f]{40}", expected_source):
                expected_source = None
            if not re.fullmatch(r"[0-9a-f]{64}", expected_product):
                record.update({
                    "reason": "expected_product_sha256_required",
                    "requiredEvidence": (
                        "MCAI_EXPECTED_PRODUCT_SHA256 must bind the exact "
                        "release product JAR before a statistical pass"
                    ),
                    "evidenceClass": "INFRASTRUCTURE_PRECHECK",
                })
                target = record_path(label, gate)
                atomic_json(target, record)
                return 2, record
        process = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "aggregate-hidden-seed-summaries.py"),
                "--route",
                route,
                "--minimum-cases",
                str(minimum_cases),
                *([
                    "--expected-product-sha256",
                    expected_product,
                ] if expected_product else []),
                *([
                    "--expected-source-commit",
                    expected_source,
                ] if expected_source else []),
                "--json",
                *(str(path) for path in summary_paths),
            ],
            cwd=ROOT,
            env=os.environ.copy(),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        record["underlyingReturnCode"] = process.returncode
        try:
            aggregate = json.loads(process.stdout)
        except json.JSONDecodeError:
            aggregate = {
                "status": "FAIL",
                "error": "hidden_seed_aggregator_invalid_json",
            }
        record.update({
            "underlying": aggregate,
            "evidenceClass": "HIDDEN_SEED_STATISTICAL",
            "functionalAiClaim": False,
        })
        if aggregate.get("status") == "FAIL":
            record["status"] = "FAIL"
            record["reason"] = "hidden_seed_aggregate_failed"
        elif aggregate.get("status") == "PASS" and release_eligible:
            record["status"] = "PASS"
            record["functionalAiClaim"] = True
        else:
            record["reason"] = "hidden_seed_passed_on_non_release_source"
        target = record_path(label, gate)
        atomic_json(target, record)
        return (
            0 if record["status"] == "PASS" else
            1 if record["status"] == "FAIL" else 2,
            record,
        )

    if gate == "e2eM3":
        configured = os.environ.get("MCAI_M3_SUMMARIES", "")
        summary_paths = [
            Path(value)
            for value in configured.split(os.pathsep)
            if value.strip()
        ]
        if not summary_paths:
            record.update({
                "reason": "m3_summaries_required",
                "requiredEvidence": (
                    "one or more executed public M3 summary.json files in "
                    "MCAI_M3_SUMMARIES; each case must bind the exact "
                    "release JAR and real dedicated-server/client/model "
                    "evidence"
                ),
                "evidenceClass": "INFRASTRUCTURE_PRECHECK",
            })
            target = record_path(label, gate)
            atomic_json(target, record)
            return 2, record
        expected_product = os.environ.get(
            "MCAI_EXPECTED_PRODUCT_SHA256",
            "",
        ).strip().lower()
        expected_source = None
        if release_eligible:
            expected_source = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=ROOT,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=False,
            ).stdout.strip().lower()
            if not re.fullmatch(r"[0-9a-f]{40}", expected_source):
                expected_source = None
            if not re.fullmatch(r"[0-9a-f]{64}", expected_product):
                record.update({
                    "reason": "expected_product_sha256_required",
                    "requiredEvidence": (
                        "MCAI_EXPECTED_PRODUCT_SHA256 must bind the exact "
                        "release product JAR before M3 can pass"
                    ),
                    "evidenceClass": "INFRASTRUCTURE_PRECHECK",
                })
                target = record_path(label, gate)
                atomic_json(target, record)
                return 2, record
        try:
            aggregate = aggregate_m3_summaries(
                summary_paths,
                expected_product_sha256=expected_product or None,
                expected_source_commit=expected_source,
            )
        except (M3EvidenceError, OSError, ValueError) as exception:
            aggregate = {
                "status": "FAIL",
                "error": type(exception).__name__ + ": " + str(exception)[:512],
            }
        record.update({
            "underlying": aggregate,
            "evidenceClass": "M3_REAL_COMPANION_STATISTICAL",
            "functionalAiClaim": False,
        })
        if aggregate.get("status") == "FAIL":
            record["status"] = "FAIL"
            record["reason"] = "m3_summary_aggregate_failed"
        elif aggregate.get("status") == "PASS" and release_eligible:
            record["status"] = "PASS"
            record["functionalAiClaim"] = True
        else:
            record["reason"] = "m3_summary_passed_on_non_release_source"
        target = record_path(label, gate)
        atomic_json(target, record)
        return (
            0 if record["status"] == "PASS" else
            1 if record["status"] == "FAIL" else 2,
            record,
        )

    if gate not in FUNCTIONAL_SLICE_GATES:
        record.update({
            "reason": "scenario_runner_not_implemented",
            "requiredEvidence": (
                "real dedicated-server clients, exact release JAR, "
                "independent Oracle and gate-specific archived evidence"
            ),
        })
        target = record_path(label, gate)
        atomic_json(target, record)
        return 2, record

    command = [
        sys.executable,
        str(ROOT / "e2e" / "orchestrator.py"),
        "functional",
        "--forge-version",
        forge_version,
        "--nonce",
        nonce,
    ]
    process = subprocess.run(
        command,
        cwd=ROOT,
        env=os.environ.copy(),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    run_root = newest_run_root(process.stdout)
    record["underlyingReturnCode"] = process.returncode
    if run_root is not None:
        record["underlyingRunRoot"] = str(run_root)
        manifest = read_json(run_root / "manifest.json")
        verdict = read_json(run_root / "e2e-verdict.json")
        record["underlyingManifestStatus"] = manifest.get("status")
        missing_evidence = verdict.get("missingEvidence", [])
        preflight = manifest.get("preflight", {})
        if isinstance(preflight, dict):
            preflight_missing = preflight.get("missing", [])
            if isinstance(preflight_missing, list):
                record["preflightMissing"] = list(preflight_missing)
                if not missing_evidence and preflight_missing:
                    missing_evidence = [
                        "preflight:" + str(item)
                        for item in preflight_missing
                    ]
        record["missingEvidence"] = missing_evidence
        # A real functional pass on a dirty checkout is useful inner-loop
        # evidence but cannot promote a formal release gate.
        if (
            release_eligible
            and manifest.get("evidenceClass") == "RELEASE_CANDIDATE"
            and verdict.get("status") == "PASS"
        ):
            record["status"] = "PASS"
            record["functionalAiClaim"] = True
        elif verdict.get("status") == "FAIL":
            record["status"] = "FAIL"
            record["reason"] = "underlying_functional_verdict_failed"
        else:
            if record.get("preflightMissing"):
                record["reason"] = (
                    "functional_preflight_missing:"
                    + ",".join(record["preflightMissing"])
                )
            else:
                record["reason"] = "functional_preflight_or_launch_not_run"
    else:
        record["reason"] = "functional_runner_did_not_emit_run_root"
    target = record_path(label, gate)
    atomic_json(target, record)
    return (
        0 if record["status"] == "PASS" else
        1 if record["status"] == "FAIL" else 2,
        record,
    )


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument("--gate", required=True, choices=GATE_NAMES)
    value.add_argument("--forge-version", default="65.1.0")
    value.add_argument("--nonce", default=None)
    return value


def main() -> int:
    args = parser().parse_args()
    if not SAFE_FORGE_VERSION.fullmatch(args.forge_version):
        print("Forge runtime must be one 65.x.y patch", file=sys.stderr)
        return 2
    nonce = args.nonce or (
        "FORMAL"
        + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M%S")
    )
    status, record = run_gate(args.gate, args.forge_version, nonce)
    print(json.dumps(record, ensure_ascii=False, indent=2))
    return status


if __name__ == "__main__":
    raise SystemExit(main())
