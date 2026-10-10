#!/usr/bin/env python3
"""External Hardcore hidden-seed evaluation harness.

This tool prepares fresh dedicated-server directories and, when a server
command is supplied, drives only model preflight plus the single locked
evaluation start command. It never passes a seed to the mod or model.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from e2e.hidden_seed_protocol import (  # noqa: E402
    HiddenSeedEvidenceError,
    validate_terminal_result as validate_terminal_result_protocol,
)


RESULT_RELATIVE = Path(
    "world/data/mcai_companion/evaluation-result.json"
)
TWO_HOUR_TICKS = 2 * 60 * 60 * 20
SIX_HOUR_TICKS = 6 * 60 * 60 * 20
ONE_HOUR_TICKS = 60 * 60 * 20
HEX64 = re.compile(r"^[0-9a-fA-F]{64}$")
HEX40 = re.compile(r"^[0-9a-fA-F]{40}$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Prepare or run fresh, hidden random-seed Hardcore evaluation "
            "cases. The template must be a clean dedicated-server directory."
        )
    )
    parser.add_argument("--template-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument(
        "--product-sha256",
        default=os.environ.get("MCAI_PRODUCT_SHA256", ""),
        help="exact installed product JAR SHA-256 for statistical binding",
    )
    parser.add_argument(
        "--source-commit",
        default=os.environ.get("MCAI_SOURCE_COMMIT", ""),
        help="40-hex release commit for statistical binding",
    )
    parser.add_argument("--cases", type=int, default=1)
    parser.add_argument(
        "--route",
        choices=("completion", "foundation"),
        default="completion",
        help=(
            "completion runs the dragon route; foundation runs the M1 "
            "safe-base-and-second-day route"
        ),
    )
    parser.add_argument(
        "--prepare-only",
        action="store_true",
        help="Create and validate cases without launching Minecraft.",
    )
    parser.add_argument(
        "--startup-timeout-seconds",
        type=int,
        default=300,
    )
    parser.add_argument(
        "--probe-timeout-seconds",
        type=int,
        default=120,
    )
    parser.add_argument(
        "--case-timeout-seconds",
        type=int,
        default=6 * 60 * 60 + 300,
    )
    parser.add_argument(
        "server_command",
        nargs=argparse.REMAINDER,
        help=(
            "Dedicated-server argv after '--', for example: "
            "-- java -Xmx4G -jar forge-server.jar nogui"
        ),
    )
    args = parser.parse_args()
    if args.cases < 1 or args.cases > 10_000:
        parser.error("--cases must be between 1 and 10000")
    for name in (
        "startup_timeout_seconds",
        "probe_timeout_seconds",
        "case_timeout_seconds",
    ):
        if getattr(args, name) < 1:
            parser.error(f"--{name.replace('_', '-')} must be positive")
    if args.server_command and args.server_command[0] == "--":
        args.server_command = args.server_command[1:]
    if not args.prepare_only and not args.server_command:
        parser.error("server command is required unless --prepare-only is set")
    return args


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def discover_product_sha256(template: Path) -> str | None:
    candidates = sorted(
        path
        for path in (template / "mods").glob("mcai_companion-*.jar")
        if path.is_file()
    )
    if len(candidates) != 1:
        return None
    return file_sha256(candidates[0])


def artifact_binding(template: Path, args: argparse.Namespace) -> dict[str, str | None]:
    product = str(args.product_sha256 or "").strip().lower()
    if not product:
        product = discover_product_sha256(template)
    if not product or not HEX64.fullmatch(product):
        product = None
    commit = str(args.source_commit or "").strip().lower()
    if not commit:
        try:
            commit = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=ROOT,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=False,
            ).stdout.strip().lower()
        except OSError:
            commit = ""
    if not HEX40.fullmatch(commit):
        commit = None
    return {"productSha256": product, "sourceCommit": commit}


def load_properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    if not path.exists():
        return result
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def write_properties(path: Path, properties: dict[str, str]) -> None:
    content = "\n".join(
        f"{key}={properties[key]}" for key in sorted(properties)
    )
    path.write_text(content + "\n", encoding="utf-8")


def signed_seed() -> int:
    value = secrets.randbits(64)
    return value - (1 << 64) if value >= (1 << 63) else value


def commitment(salt: bytes, seed: int) -> str:
    material = salt + seed.to_bytes(8, "big", signed=True)
    return hashlib.sha256(material).hexdigest()


def prepare_case(
    template: Path,
    suite: Path,
    index: int,
    seed: int,
    salt: bytes,
) -> dict[str, Any]:
    case_id = f"case-{index:04d}"
    case_dir = suite / case_id
    if case_dir.exists():
        raise RuntimeError(f"case directory already exists: {case_dir}")
    shutil.copytree(template, case_dir)

    properties_path = case_dir / "server.properties"
    properties = load_properties(properties_path)
    level_name = "world"
    if (
        (case_dir / level_name).exists()
        or (case_dir / level_name).is_symlink()
    ):
        raise RuntimeError(
            f"template is not fresh: {case_dir / level_name} exists"
        )
    properties.update(
        {
            "allow-flight": "false",
            "difficulty": "hard",
            "enable-command-block": "false",
            "enable-rcon": "false",
            "force-gamemode": "true",
            "gamemode": "survival",
            "generate-structures": "true",
            "hardcore": "true",
            "level-name": level_name,
            "level-seed": str(seed),
        }
    )
    write_properties(properties_path, properties)
    verify_case_properties(properties_path)
    return {
        "caseId": case_id,
        "caseDir": str(case_dir),
        "seedCommitment": commitment(salt, seed),
    }


def verify_case_properties(path: Path) -> None:
    properties = load_properties(path)
    required = {
        "allow-flight": "false",
        "difficulty": "hard",
        "enable-command-block": "false",
        "enable-rcon": "false",
        "force-gamemode": "true",
        "gamemode": "survival",
        "generate-structures": "true",
        "hardcore": "true",
        "level-name": "world",
    }
    for key, expected in required.items():
        if properties.get(key, "").lower() != expected:
            raise RuntimeError(
                f"invalid generated server property {key!r}"
            )
    try:
        int(properties["level-seed"])
    except (KeyError, ValueError) as exception:
        raise RuntimeError("generated level-seed is invalid") from exception


def pump_output(
    process: subprocess.Popen[str],
    log_path: Path,
    lines: queue.Queue[str],
) -> None:
    assert process.stdout is not None
    with log_path.open("w", encoding="utf-8") as log:
        for line in process.stdout:
            log.write(line)
            log.flush()
            lines.put(line)


def send_console(process: subprocess.Popen[str], command: str) -> None:
    if process.stdin is None or process.poll() is not None:
        raise RuntimeError("server process is not accepting console input")
    process.stdin.write(command + "\n")
    process.stdin.flush()


def stop_process(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    try:
        send_console(process, "stop")
        process.wait(timeout=30)
        return
    except (RuntimeError, subprocess.TimeoutExpired):
        process.terminate()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=15)


def wait_for_log(
    process: subprocess.Popen[str],
    lines: queue.Queue[str],
    success: tuple[str, ...],
    failure: tuple[str, ...],
    timeout: int,
) -> str:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(
                f"server exited before expected log marker: {process.returncode}"
            )
        try:
            line = lines.get(timeout=0.25)
        except queue.Empty:
            continue
        if any(marker in line for marker in failure):
            raise RuntimeError(line.strip())
        if any(marker in line for marker in success):
            return line.strip()
    raise TimeoutError(f"timed out waiting for {success!r}")


def validate_terminal_result(raw: Any) -> dict[str, Any]:
    route = (
        "completion"
        if isinstance(raw, dict) and raw.get("routeProfile") == "COMPLETION"
        else "foundation"
    )
    try:
        return validate_terminal_result_protocol(raw, route)
    except HiddenSeedEvidenceError as exception:
        raise RuntimeError(str(exception)) from exception


def run_case(
    case: dict[str, Any],
    command: list[str],
    startup_timeout: int,
    probe_timeout: int,
    case_timeout: int,
    route: str,
) -> dict[str, Any]:
    case_dir = Path(case["caseDir"])
    eula = load_properties(case_dir / "eula.txt")
    if eula.get("eula", "").lower() != "true":
        raise RuntimeError(
            "template eula.txt must contain eula=true; the harness never "
            "accepts the EULA on the user's behalf"
        )
    result_path = case_dir / RESULT_RELATIVE
    result_path.unlink(missing_ok=True)
    lines: queue.Queue[str] = queue.Queue()
    started = time.monotonic()
    process = subprocess.Popen(
        command,
        cwd=case_dir,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
        shell=False,
    )
    reader = threading.Thread(
        target=pump_output,
        args=(process, case_dir / "console.log", lines),
        daemon=True,
    )
    reader.start()
    phase = "startup"
    try:
        wait_for_log(
            process,
            lines,
            ("Done (",),
            ("Failed to start", "Encountered an unexpected exception"),
            startup_timeout,
        )
        phase = "model_probe"
        send_console(process, "mcai model probe")
        wait_for_log(
            process,
            lines,
            ("Capability probe verified",),
            (
                "Capability probe stopped safely",
                "Model runtime is unavailable",
            ),
            probe_timeout,
        )
        phase = "evaluation_start"
        send_console(process, f"mcai evaluation start {route}")
        wait_for_log(
            process,
            lines,
            ("Hardcore evaluation started",),
            ("Evaluation requires", "Evaluation model could not be frozen"),
            30,
        )
        phase = "evaluation_running"
        deadline = time.monotonic() + case_timeout
        while time.monotonic() < deadline:
            if result_path.exists():
                raw = json.loads(result_path.read_text(encoding="utf-8"))
                terminal = validate_terminal_result(raw)
                if terminal["routeProfile"] != route.upper():
                    raise RuntimeError(
                        "terminal result route does not match the suite"
                    )
                return {
                    **case,
                    "phase": "terminal",
                    "wallSeconds": time.monotonic() - started,
                    "terminal": terminal,
                }
            if process.poll() is not None:
                raise RuntimeError(
                    f"server exited during evaluation: {process.returncode}"
                )
            time.sleep(0.25)
        raise TimeoutError("case exceeded external wall-clock deadline")
    except Exception as exception:
        return {
            **case,
            "phase": phase,
            "wallSeconds": time.monotonic() - started,
            "harnessFailure": type(exception).__name__,
            "harnessDetail": str(exception)[:512],
        }
    finally:
        stop_process(process)
        reader.join(timeout=5)


def public_summary(
    results: list[dict[str, Any]],
    route: str,
    artifact_binding_value: dict[str, str | None],
) -> dict[str, Any]:
    terminal = [item["terminal"] for item in results if "terminal" in item]
    completed = [item for item in terminal if item["outcome"] == "COMPLETED"]
    within_two = [
        item for item in completed if int(item["elapsedTicks"]) <= TWO_HOUR_TICKS
    ]
    within_one = [
        item for item in completed if int(item["elapsedTicks"]) <= ONE_HOUR_TICKS
    ]
    within_six = [
        item for item in completed if int(item["elapsedTicks"]) <= SIX_HOUR_TICKS
    ]
    total = len(results)
    public_results = [
        {
            key: value
            for key, value in item.items()
            if key != "caseDir"
        }
        for item in results
    ]
    return {
        "schemaVersion": 2,
        "protocol": "fresh-hidden-random-seed-hardcore-v2",
        "route": route,
        "artifactBinding": artifact_binding_value,
        "cases": total,
        "terminalCases": len(terminal),
        "completedCases": len(completed),
        "completedWithinOneHour": len(within_one),
        "completedWithinTwoHours": len(within_two),
        "completedWithinSixHours": len(within_six),
        "oneHourRate": len(within_one) / total if total else 0.0,
        "twoHourRate": len(within_two) / total if total else 0.0,
        "sixHourRate": len(within_six) / total if total else 0.0,
        "results": public_results,
        "claimBoundary": (
            "This file reports only executed cases; preparation or controlled "
            "GameTests are not natural-seed completion evidence."
        ),
    }


def main() -> int:
    args = parse_args()
    template = args.template_dir.resolve()
    output = args.output_dir.resolve()
    if not template.is_dir():
        raise RuntimeError("--template-dir must be a directory")
    if output.exists():
        raise RuntimeError("--output-dir must not already exist")
    output.mkdir(parents=True)
    binding = artifact_binding(template, args)
    salt = secrets.token_bytes(32)
    seeds = [signed_seed() for _ in range(args.cases)]
    cases = [
        prepare_case(template, output, index + 1, seed, salt)
        for index, seed in enumerate(seeds)
    ]
    private_manifest = output / "private-seeds.json"
    private_manifest.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "saltHex": salt.hex(),
                "seeds": [
                    {"caseId": case["caseId"], "seed": seed}
                    for case, seed in zip(cases, seeds)
                ],
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    os.chmod(private_manifest, 0o600)

    if args.prepare_only:
        results = [
            {**case, "phase": "prepared", "executed": False}
            for case in cases
        ]
    else:
        results = [
            run_case(
                case,
                args.server_command,
                args.startup_timeout_seconds,
                args.probe_timeout_seconds,
                args.case_timeout_seconds,
                args.route,
            )
            for case in cases
        ]
    summary = public_summary(results, args.route, binding)
    (output / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if all("harnessFailure" not in item for item in results) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exception:
        print(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "harnessFailure": type(exception).__name__,
                    "detail": str(exception)[:512],
                },
                ensure_ascii=False,
            ),
            file=sys.stderr,
        )
        raise SystemExit(2)
