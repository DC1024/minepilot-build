#!/usr/bin/env python3
"""Run one provider-neutral real-client worker job.

The job manifest is public metadata only.  The model credential must be
injected into this process by the worker environment or an external secret
file; it is never copied into the job/result bundle.  A missing Linux/Xvfb
runtime is recorded as BLOCKED_INFRA and never rewritten as a gameplay pass.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from e2e.orchestrator import functional_preflight
from e2e.worker_protocol import (
    WorkerProtocolError,
    assert_no_secret_bytes,
    build_result_manifest,
    model_binding_errors,
    validate_job_manifest,
    validate_result_manifest,
    worker_scenario_errors,
)


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def read_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise WorkerProtocolError(f"invalid JSON: {path}") from exception
    if not isinstance(value, dict):
        raise WorkerProtocolError(f"JSON document is not an object: {path}")
    return value


def atomic_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def latest_manifest(root: Path) -> Path | None:
    candidates = sorted(root.glob("**/manifest.json"), key=lambda p: p.stat().st_mtime_ns)
    return candidates[-1] if candidates else None


def copy_tree_contents(source: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=False)
    for child in source.iterdir():
        target = destination / child.name
        if child.is_dir() and not child.is_symlink():
            shutil.copytree(child, target, symlinks=False)
        elif child.is_file() and not child.is_symlink():
            shutil.copy2(child, target)


def command_validate(args: argparse.Namespace) -> int:
    job = validate_job_manifest(read_json(Path(args.manifest)))
    print(json.dumps({"status": "PASS", "manifestSha256": job["manifestSha256"]}, ensure_ascii=False))
    return 0


def command_run(args: argparse.Namespace) -> int:
    job_path = Path(args.manifest).resolve()
    job = validate_job_manifest(read_json(job_path))
    output = Path(args.output).resolve()
    if output.exists():
        raise WorkerProtocolError(f"refusing to reuse worker output: {output}")
    output.mkdir(parents=True)
    staging = output / "orchestrator-results"
    staging.mkdir()
    shutil.copy2(job_path, output / "job-manifest.json")

    environment = os.environ.copy()
    preflight = functional_preflight(environment, job["minecraft"]["forgeVersion"])
    model_mismatches = model_binding_errors(
        job["model"],
        preflight.get("model", {}),
    )
    preflight["jobModelBinding"] = {
        "matches": not model_mismatches,
        "mismatchedFields": model_mismatches,
    }
    atomic_json(output / "functional-preflight.json", preflight)
    started = utc_now()
    exit_code: int | None = None
    status = "BLOCKED_INFRA"
    reason = "worker_preflight_not_ready"
    functional_claim = False
    infrastructure_missing = set(preflight.get("missing", [])) - {
        "MCAI_BASE_URL",
        "MCAI_MODEL",
        "MCAI_API_KEY_or_MCAI_API_KEY_FILE",
    }
    scenario_errors = worker_scenario_errors(job["scenario"])
    if scenario_errors:
        # Do not execute a different scenario under a shard's public label.
        # This is a terminal, auditable NOT_RUN until the worker image gains
        # the requested scenario implementation.
        status = "NOT_RUN"
        reason = "unsupported_worker_scenario_" + "_".join(scenario_errors)
        preflight["scenarioReady"] = False
        preflight["scenarioErrors"] = scenario_errors
        atomic_json(output / "functional-preflight.json", preflight)
    elif infrastructure_missing:
        status = "BLOCKED_INFRA"
        reason = "worker_preflight_not_ready"
    elif model_mismatches:
        status = "BLOCKED_CREDENTIAL"
        reason = "worker_model_binding_mismatch_" + "_".join(model_mismatches)
    elif not preflight["ready"]:
        missing = set(preflight.get("missing", []))
        credential_missing = {
            "MCAI_BASE_URL",
            "MCAI_MODEL",
            "MCAI_API_KEY_or_MCAI_API_KEY_FILE",
        }
        if missing and missing.issubset(credential_missing):
            status = "BLOCKED_CREDENTIAL"
    else:
        environment["MCAI_E2E_RESULTS"] = str(staging)
        command = [
            sys.executable,
            str(ROOT / "e2e" / "orchestrator.py"),
            "functional",
            "--forge-version",
            job["minecraft"]["forgeVersion"],
            "--nonce",
            job["jobId"],
        ]
        completed = subprocess.run(
            command,
            cwd=ROOT,
            env=environment,
            check=False,
        )
        exit_code = completed.returncode
        run_manifest = latest_manifest(staging)
        if run_manifest is None:
            status = "FAIL"
            reason = "worker_orchestrator_did_not_write_manifest"
        else:
            source_run = run_manifest.parent
            copy_tree_contents(source_run, output / "run")
            staged = read_json(output / "run" / "manifest.json")
            staged_source = staged.get("source", {})
            staged_artifacts = staged.get("artifacts", {})
            staged_product = staged_artifacts.get("productionJar", {})
            staged_platform = staged.get("platform", {})
            staged_model = staged.get("model", {})
            staged_model_mismatches = model_binding_errors(
                job["model"],
                staged_model,
            )
            binding_matches = (
                isinstance(staged_source, dict)
                and staged_source.get("commit") == job["source"]["commit"]
                and staged_source.get("dirty") == job["source"]["dirty"]
                and isinstance(staged_product, dict)
                and staged_product.get("sha256") == job["product"]["sha256"]
                and isinstance(staged_platform, dict)
                and staged_platform.get("forge") == job["minecraft"]["forgeVersion"]
                and staged_platform.get("minecraft") == job["minecraft"]["version"]
                and not staged_model_mismatches
            )
            if not binding_matches:
                status = "FAIL"
                reason = "worker_artifact_or_source_or_model_binding_mismatch"
            else:
                status = staged.get("status", "FAIL")
                if status not in {"PASS", "FAIL", "NOT_RUN"}:
                    status = "FAIL"
                functional_claim = bool(staged.get("functionalAiClaim", False))
                reason = "orchestrator_result_" + str(status).lower()

    secrets = [environment.get("MCAI_API_KEY", "")]
    key_file = environment.get("MCAI_API_KEY_FILE", "").strip()
    if key_file:
        try:
            secrets.append(Path(key_file).read_text(encoding="utf-8").strip())
        except OSError:
            pass
    assert_no_secret_bytes(output, secrets)
    result = build_result_manifest(
        job,
        status=status,
        exit_code=exit_code,
        started_at_utc=started,
        finished_at_utc=utc_now(),
        root=output,
        functional_ai_claim=functional_claim,
        reason=reason,
    )
    atomic_json(output / "worker-result.json", result)
    validate_result_manifest(result, output, job)
    print(json.dumps({"status": status, "resultSha256": result["resultSha256"]}, ensure_ascii=False))
    return 0 if status == "PASS" else 2 if status in {"NOT_RUN", "BLOCKED_INFRA", "BLOCKED_CREDENTIAL", "BLOCKED_BUDGET"} else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    validate = subparsers.add_parser("validate-job")
    validate.add_argument("--manifest", required=True)
    validate.set_defaults(handler=command_validate)
    run = subparsers.add_parser("run")
    run.add_argument("--manifest", required=True)
    run.add_argument("--output", required=True)
    run.set_defaults(handler=command_run)
    args = parser.parse_args()
    try:
        return int(args.handler(args))
    except WorkerProtocolError as exception:
        print(f"worker protocol error: {exception}", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
