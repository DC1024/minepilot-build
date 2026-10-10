#!/usr/bin/env python3
"""Real Minecraft/Forge black-box E2E process orchestrator.

This program does not drive the user's desktop. Functional runs are permitted
only on Linux with an isolated Xvfb display. The real Actor client sends normal
Minecraft chat; the Observer and server Oracle independently record evidence.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import sqlite3
import struct
import subprocess
import sys
import time
from typing import Any
from urllib.parse import urlparse
import uuid


ROOT = Path(__file__).resolve().parents[1]
RESULTS = Path(
    os.environ.get("MCAI_E2E_RESULTS", str(ROOT / "e2e" / "results"))
).resolve()
JAVA_HOME_DEFAULT = Path(
    "/Users/weida/.gradle/jdks/temurin-25-aarch64/Contents/Home"
)
PRODUCTION_GLOB = "mcai_companion-*-mc26.2.jar"
FORGE_FLOOR = "65.0.0"
SAFE_FORGE_VERSION = re.compile(r"65\.\d{1,3}\.\d{1,3}")
E2E_ACTOR_NAME = "MCAIActor"
MODEL_AUDIT_TYPES = (
    "ai_perception_received",
    "model_request_started",
    "model_response_received",
    "decision_schema_validated",
    "decision_revision_accepted",
    "skill_started",
)
CAUSAL_AUDIT_TYPES = MODEL_AUDIT_TYPES + (
    "low_level_actions_issued",
)
TRANSPORT_AUDIT_TYPE = "connection_transport_audit"
RUNTIME_LIFECYCLE_AUDIT_TYPE = "runtime_lifecycle_audit"
TASK_ACCEPTANCE_AUDIT_TYPE = "conversation_task_accepted"
PRODUCTION_AUDIT_TYPES = CAUSAL_AUDIT_TYPES + (
    TRANSPORT_AUDIT_TYPE,
    RUNTIME_LIFECYCLE_AUDIT_TYPE,
    TASK_ACCEPTANCE_AUDIT_TYPE,
)


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def parse_utc(value: Any) -> dt.datetime | None:
    """Parse an evidence timestamp without relying on string ordering.

    Java's ``Instant`` may emit a whole-second ``...Z`` timestamp while the
    Python actor/observer write fractional seconds.  Lexicographic comparison
    orders those two valid ISO forms incorrectly (``Z`` sorts after ``.``),
    which could accidentally associate an event from before a chat message.
    Evidence with an invalid or timezone-less timestamp is deliberately not
    eligible for causal claims.
    """
    if not isinstance(value, str) or not value:
        return None
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = dt.datetime.fromisoformat(normalized)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(dt.timezone.utc)


def occurs_at_or_after(event: dict[str, Any], marker_utc: str) -> bool:
    event_time = parse_utc(event.get("atUtc"))
    marker_time = parse_utc(marker_utc)
    return (
        event_time is not None
        and marker_time is not None
        and event_time >= marker_time
    )


def occurs_before(event: dict[str, Any], marker_utc: str) -> bool:
    event_time = parse_utc(event.get("atUtc"))
    marker_time = parse_utc(marker_utc)
    return (
        event_time is not None
        and marker_time is not None
        and event_time < marker_time
    )


def normalized_chat_sha256(value: Any) -> str | None:
    """Match the companion's bounded chat normalization before hashing."""
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    if not normalized:
        return None
    # Python strings index Unicode code points, matching Java's code-point
    # bound used by CompanionConversationCoordinator for normal chat text.
    bounded = normalized[:512]
    return hashlib.sha256(bounded.encode("utf-8")).hexdigest()


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def current_build_version() -> str:
    """Read the exact Gradle artifact version used by this checkout.

    A developer can legitimately keep an older development JAR in
    ``build/libs`` while iterating.  Selecting by directory glob alone then
    makes a smoke run fail (or, worse, choose the wrong product).  Bind the
    staging run to the version declared by the current source instead.
    """
    build_file = ROOT / "build.gradle"
    try:
        source = build_file.read_text(encoding="utf-8")
    except OSError as exception:
        raise RuntimeError("Could not read build.gradle version") from exception
    match = re.search(
        r"(?m)^\s*version\s*=\s*['\"]([^'\"]+)['\"]\s*$",
        source,
    )
    if match is None:
        raise RuntimeError("build.gradle does not declare a fixed version")
    version = match.group(1).strip()
    if not version or any(character in version for character in "/\\\0"):
        raise RuntimeError("build.gradle version is invalid")
    return version


def command_output(command: list[str]) -> str:
    completed = subprocess.run(
        command,
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    return completed.stdout.strip()


def source_identity() -> dict[str, Any]:
    commit = command_output(["git", "rev-parse", "HEAD"])
    if (
        len(commit) != 40
        or any(character not in "0123456789abcdef" for character in commit)
    ):
        commit = None
    dirty_output = command_output(["git", "status", "--porcelain"])
    dirty = bool(dirty_output)
    label = (commit or "no-commit")[:12]
    if dirty:
        label += "-dirty"
    return {
        "commit": commit,
        "dirty": dirty,
        "label": label,
        "releaseEligible": commit is not None and not dirty,
    }


def java_environment() -> dict[str, str]:
    environment = os.environ.copy()
    if not environment.get("JAVA_HOME") and JAVA_HOME_DEFAULT.is_dir():
        environment["JAVA_HOME"] = str(JAVA_HOME_DEFAULT)
    java_home = environment.get("JAVA_HOME")
    if java_home:
        environment["PATH"] = (
            str(Path(java_home) / "bin")
            + os.pathsep
            + environment.get("PATH", "")
        )
    return environment


def without_model_credentials(
    environment: dict[str, str],
) -> dict[str, str]:
    restricted = environment.copy()
    for name in (
        "MCAI_API_KEY",
        "MCAI_API_KEY_FILE",
        "CREDENTIALS_DIRECTORY",
    ):
        restricted.pop(name, None)
    return restricted


def run_checked(
    command: list[str],
    *,
    environment: dict[str, str],
    log: Path | None = None,
) -> None:
    if log is None:
        completed = subprocess.run(
            command,
            cwd=ROOT,
            env=environment,
            check=False,
        )
    else:
        log.parent.mkdir(parents=True, exist_ok=True)
        with log.open("w", encoding="utf-8") as output:
            completed = subprocess.run(
                command,
                cwd=ROOT,
                env=environment,
                stdout=output,
                stderr=subprocess.STDOUT,
                check=False,
            )
    if completed.returncode != 0:
        raise RuntimeError(
            f"Command failed with exit {completed.returncode}: "
            + " ".join(command)
        )


def build_artifacts(
    environment: dict[str, str],
    command_log: list[list[str]],
) -> tuple[Path, Path, Path]:
    command = [
        str(ROOT / "gradlew"),
        "jarJar",
        "verifyReleaseJar",
        "e2eClientJar",
        "e2eOracleJar",
        "--no-daemon",
    ]
    command_log.append(command)
    run_checked(command, environment=environment)

    version = current_build_version()
    expected_product = ROOT / "build" / "libs" / (
        f"mcai_companion-{version}.jar"
    )
    products = [expected_product] if expected_product.is_file() else []
    if len(products) != 1:
        raise RuntimeError(
            "Expected the current bundled Minecraft 26.2 product JAR; "
            f"missing {expected_product.name}"
        )
    clients = sorted(
        (ROOT / "build" / "e2e-libs").glob(
            f"mcai-e2e-client-{version}.jar"
        )
    )
    oracles = sorted(
        (ROOT / "build" / "e2e-libs").glob(
            f"mcai-e2e-oracle-{version}.jar"
        )
    )
    if len(clients) != 1 or len(oracles) != 1:
        raise RuntimeError("Expected one client and one Oracle test JAR")
    return products[0], clients[0], oracles[0]


def allocate_loopback_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def safe_nonce() -> str:
    return "E2E" + secrets.token_hex(6).upper()


def offline_player_uuid(player_name: str) -> str:
    """Return the vanilla offline-mode UUID for a bounded test name.

    Dedicated E2E runs deliberately disable Mojang authentication. Minecraft
    derives the player's UUID as Java's ``UUID.nameUUIDFromBytes`` over
    ``OfflinePlayer:<name>``.  Reproducing that exact, version-3 UUID lets the
    isolated server grant only the real Actor's narrow chat-task permission;
    it does not make the Actor an operator and never changes production
    authorization defaults.
    """
    encoded = hashlib.md5(
        ("OfflinePlayer:" + player_name).encode("utf-8")
    ).digest()
    value = bytearray(encoded)
    value[6] = (value[6] & 0x0F) | 0x30
    value[8] = (value[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(value)))


def forge_version(value: str) -> str:
    if not SAFE_FORGE_VERSION.fullmatch(value):
        raise argparse.ArgumentTypeError(
            "Forge runtime must be one 65.x.y patch"
        )
    return value


def create_run(
    *,
    source: dict[str, Any],
    nonce: str,
) -> tuple[str, Path]:
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_id = f"{stamp}-{nonce.lower()}"
    run_root = RESULTS / source["label"] / run_id
    if run_root.exists():
        raise RuntimeError(f"Refusing to reuse E2E run directory {run_root}")
    for child in (
        "screenshots",
        "video",
        "crash-reports",
        "instances/server/mods",
        "instances/actor/mods",
        "instances/observer/mods",
    ):
        (run_root / child).mkdir(parents=True, exist_ok=False)
    return run_id, run_root


def install_exact_product(product: Path, run_root: Path) -> dict[str, str]:
    hashes: dict[str, str] = {}
    expected = sha256(product)
    for role in ("server", "actor", "observer"):
        target = (
            run_root / "instances" / role / "mods" / product.name
        )
        shutil.copy2(product, target)
        actual = sha256(target)
        if actual != expected:
            raise RuntimeError(f"Product JAR copy mismatch for {role}")
        hashes[role] = actual
    return hashes


def write_instance_files(run_root: Path, port: int, nonce: str) -> None:
    server = run_root / "instances" / "server"
    (server / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    properties = {
        "allow-flight": "true",
        "broadcast-console-to-ops": "false",
        "difficulty": "peaceful",
        "enable-command-block": "false",
        "enable-rcon": "false",
        "enable-status": "true",
        "enforce-secure-profile": "false",
        "force-gamemode": "false",
        "gamemode": "survival",
        "hardcore": "false",
        "level-name": f"world-{nonce.lower()}",
        "max-players": "4",
        "motd": f"MCAI E2E {nonce}",
        "online-mode": "false",
        "prevent-proxy-connections": "true",
        "server-ip": "127.0.0.1",
        "server-port": str(port),
        "simulation-distance": "8",
        "spawn-protection": "0",
        "sync-chunk-writes": "true",
        "view-distance": "8",
        "white-list": "false",
    }
    (server / "server.properties").write_text(
        "".join(f"{key}={value}\n" for key, value in properties.items()),
        encoding="utf-8",
    )
    # The real Actor is an ordinary non-OP client.  Explicitly authorize its
    # offline-mode UUID for gameplay chat in this isolated run; without this
    # file the production permission boundary correctly rejects the task
    # before the model, making the black-box chain impossible to observe.
    config = server / "config"
    config.mkdir(parents=True, exist_ok=True)
    (config / "mcai-companion.toml").write_text(
        "chat.allowedSenders = [\""
        + offline_player_uuid(E2E_ACTOR_NAME)
        + "\"]\n",
        encoding="utf-8",
    )
    for role in ("actor", "observer"):
        (run_root / "instances" / role / "options.txt").write_text(
            "fullscreen:false\n"
            "enableVsync:false\n"
            "maxFps:30\n"
            "renderDistance:8\n"
            "simulationDistance:8\n"
            "guiScale:2\n",
            encoding="utf-8",
        )


def model_metadata(environment: dict[str, str], run_id: str) -> dict[str, Any]:
    base = environment.get("MCAI_BASE_URL", "")
    model = environment.get("MCAI_MODEL", "")
    host = urlparse(base).hostname if base else None
    fingerprint = None
    credential_source = None
    direct = environment.get("MCAI_API_KEY", "")
    key_file = environment.get("MCAI_API_KEY_FILE", "").strip()
    if direct.strip():
        digest = hashlib.sha256()
        digest.update((run_id + "\0").encode("utf-8"))
        digest.update(direct.encode("utf-8"))
        fingerprint = digest.hexdigest()[:16]
        credential_source = "environment"
    elif key_file:
        candidate = Path(key_file)
        try:
            size = candidate.stat().st_size
            if candidate.is_file() and 0 < size <= 32 * 1024:
                digest = hashlib.sha256()
                digest.update((run_id + "\0").encode("utf-8"))
                with candidate.open("rb") as stream:
                    for chunk in iter(
                        lambda: stream.read(4 * 1024),
                        b"",
                    ):
                        digest.update(chunk)
                fingerprint = digest.hexdigest()[:16]
                credential_source = "file"
        except OSError:
            pass
    return {
        "model": model or None,
        "baseUrlHost": host,
        "credentialPresent": credential_source is not None,
        "credentialSource": credential_source,
        "credentialRunSaltedFingerprint": fingerprint,
    }


def required_model_environment(environment: dict[str, str]) -> None:
    key_present = bool(environment.get("MCAI_API_KEY", "").strip())
    key_file = environment.get("MCAI_API_KEY_FILE", "").strip()
    if key_file:
        key_present = Path(key_file).is_file()
    missing = [
        name
        for name in ("MCAI_BASE_URL", "MCAI_MODEL")
        if not environment.get(name, "").strip()
    ]
    if not key_present:
        missing.append("MCAI_API_KEY or MCAI_API_KEY_FILE")
    if missing:
        raise RuntimeError(
            "Formal real-model E2E requires: " + ", ".join(missing)
        )


def java_major_version(java_executable: str) -> int | None:
    """Return only the Java major version from ``java -version`` output.

    The version is written to stderr by most JDKs and to stdout by a few
    wrappers.  Keep the parser deliberately small and return no raw command
    output: preflight reports must never become an accidental environment
    dump.
    """
    try:
        completed = subprocess.run(
            [java_executable, "-version"],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=5,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    output = (completed.stdout + "\n" + completed.stderr)[:4096]
    for pattern in (
        r'\bversion\s+"(\d+)',
        r'\bopenjdk\s+(\d+)(?:\.|\s)',
        r'\bjava\s+(\d+)(?:\.|\s)',
    ):
        match = re.search(pattern, output, flags=re.IGNORECASE)
        if match is not None:
            try:
                return int(match.group(1))
            except ValueError:
                return None
    return None


def functional_preflight(
    environment: dict[str, str],
    forge_version: str,
) -> dict[str, Any]:
    """Report prerequisites without starting Minecraft or touching a display.

    This is deliberately an infrastructure report, not a test verdict.  It
    lets a developer or CI job distinguish a missing offscreen runtime or
    model credential from a product failure before the expensive exact-JAR
    launch.  No secret value, secret path, or request body is returned.
    """
    missing: list[str] = []
    is_linux = sys.platform.startswith("linux")
    if not is_linux:
        missing.append("linux_host")
    xvfb = shutil.which("Xvfb")
    if xvfb is None:
        missing.append("Xvfb")
    for variable in ("MCAI_BASE_URL", "MCAI_MODEL"):
        if not environment.get(variable, "").strip():
            missing.append(variable)
    key_present = bool(environment.get("MCAI_API_KEY", "").strip())
    key_file = environment.get("MCAI_API_KEY_FILE", "").strip()
    if key_file:
        key_present = Path(key_file).is_file()
    if not key_present:
        missing.append("MCAI_API_KEY_or_MCAI_API_KEY_FILE")

    java_home = environment.get("JAVA_HOME", "").strip()
    java_executable = (
        str(Path(java_home) / "bin" / "java")
        if java_home
        else shutil.which("java")
    )
    java_available = bool(java_executable and Path(java_executable).exists())
    detected_java_major = (
        java_major_version(java_executable)
        if java_available and java_executable
        else None
    )
    if not java_available or detected_java_major != 25:
        missing.append("java25")

    metadata = model_metadata(environment, "preflight")
    return {
        "schemaVersion": 1,
        "kind": "functional_e2e_preflight",
        "atUtc": utc_now(),
        "hostPlatform": sys.platform,
        "requiresIsolatedXvfb": True,
        "physicalDisplayUntouched": True,
        "forgeVersion": forge_version,
        "xvfbExecutablePresent": xvfb is not None,
        "javaExecutablePresent": java_available,
        "javaMajor": detected_java_major,
        "model": metadata,
        "missing": missing,
        "ready": not missing,
    }


def command_preflight(args: argparse.Namespace) -> int:
    environment = java_environment()
    report = functional_preflight(environment, args.forge_version)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["ready"] else 2


def wait_for_text(path: Path, text: str, timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if text in path.read_text(
                encoding="utf-8",
                errors="replace",
            ):
                return True
        except FileNotFoundError:
            pass
        time.sleep(0.25)
    return False


def start_process(
    command: list[str],
    *,
    environment: dict[str, str],
    log_path: Path,
) -> tuple[subprocess.Popen[str], Any]:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    output = log_path.open("w", encoding="utf-8")
    process = subprocess.Popen(
        command,
        cwd=ROOT,
        env=environment,
        stdin=subprocess.PIPE,
        stdout=output,
        stderr=subprocess.STDOUT,
        text=True,
        start_new_session=True,
    )
    return process, output


def terminate_process(
    name: str,
    process: subprocess.Popen[str],
    *,
    graceful_input: str | None = None,
) -> dict[str, Any]:
    requested_at = utc_now()
    if process.poll() is None and graceful_input and process.stdin:
        try:
            process.stdin.write(graceful_input)
            process.stdin.flush()
            process.wait(timeout=15)
        except (BrokenPipeError, subprocess.TimeoutExpired):
            pass
    if process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=10)
    return {
        "name": name,
        "pid": process.pid,
        "exitCode": process.returncode,
        "terminationRequestedAtUtc": requested_at,
    }


def read_json_lines(
    path: Path,
    maximum_bytes: int = 64 * 1024 * 1024,
) -> list[dict[str, Any]]:
    if not path.is_file() or path.stat().st_size > maximum_bytes:
        return []
    values: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as stream:
        for line in stream:
            try:
                value = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict):
                values.append(value)
    return values


def write_json_lines(path: Path, values: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        for value in values:
            stream.write(
                json.dumps(
                    value,
                    ensure_ascii=False,
                    separators=(",", ":"),
                )
                + "\n"
            )
    temporary.replace(path)


def read_production_audit(
    run_root: Path,
) -> tuple[list[dict[str, Any]], list[str]]:
    databases = sorted(
        (
            run_root
            / "instances"
            / "server"
        ).glob("world-*/data/mcai_companion/memory.db")
    )
    if len(databases) != 1:
        return [], [
            "production-audit:exactly-one-memory-database"
        ]
    database = databases[0]
    events: list[dict[str, Any]] = []
    try:
        connection = sqlite3.connect(
            f"file:{database}?mode=ro",
            uri=True,
            timeout=5.0,
        )
        try:
            placeholders = ",".join("?" for _ in PRODUCTION_AUDIT_TYPES)
            rows = connection.execute(
                f"""
                SELECT sequence, occurred_at, event_type, source,
                       payload_json, world_revision, goal_revision
                FROM event_log
                WHERE event_type IN ({placeholders})
                ORDER BY sequence ASC
                """,
                PRODUCTION_AUDIT_TYPES,
            )
            for row in rows:
                try:
                    payload = json.loads(row[4])
                except (TypeError, json.JSONDecodeError):
                    payload = {}
                if not isinstance(payload, dict):
                    payload = {}
                events.append(
                    {
                        "schemaVersion": 1,
                        "sequence": int(row[0]),
                        "atUtc": str(row[1]),
                        "type": str(row[2]),
                        "source": str(row[3]),
                        "payload": payload,
                        "worldRevision": int(row[5]),
                        "goalRevision": int(row[6]),
                    }
                )
        finally:
            connection.close()
    except (OSError, sqlite3.Error, ValueError):
        return [], ["production-audit:sqlite-read"]
    return events, []


def causal_model_trace(
    events: list[dict[str, Any]],
    chat_at_utc: str,
    expected_skill: str = "follow_entity",
    expected_action: str = "move",
    expected_goal_revision: int | None = None,
) -> dict[str, Any] | None:
    """Return one fully ordered model-to-action trace for one accepted goal.

    ``expected_goal_revision`` is supplied by the production task-acceptance
    notice emitted synchronously when the real server accepts the actor's
    chat.  It prevents an older or unrelated planner request from satisfying
    the formal Actor/Observer scenario merely because it happened later.
    """
    by_request: dict[str, list[dict[str, Any]]] = {}
    for event in events:
        payload = event.get("payload")
        request_id = (
            payload.get("requestId")
            if isinstance(payload, dict)
            else None
        )
        if (
            not isinstance(request_id, str)
            or not request_id.startswith("brain-")
            or not occurs_at_or_after(event, chat_at_utc)
        ):
            continue
        by_request.setdefault(request_id, []).append(event)

    required = list(CAUSAL_AUDIT_TYPES)
    for request_id, candidates in by_request.items():
        ordered = sorted(
            candidates,
            key=lambda event: int(event.get("sequence", -1)),
        )
        selected: list[dict[str, Any]] = []
        cursor = 0
        for required_type in required:
            match = None
            for index in range(cursor, len(ordered)):
                if ordered[index].get("type") == required_type:
                    match = ordered[index]
                    cursor = index + 1
                    break
            if match is None:
                selected = []
                break
            selected.append(match)
        if not selected:
            continue

        revisions = {
            int(event.get("goalRevision", -1))
            for event in selected
        }
        if len(revisions) != 1 or next(iter(revisions)) < 0:
            continue
        if (
            expected_goal_revision is not None
            and next(iter(revisions)) != expected_goal_revision
        ):
            continue
        response = selected[2].get("payload", {})
        accepted = selected[4].get("payload", {})
        skill = selected[5].get("payload", {})
        action = selected[6].get("payload", {})
        if (
            not isinstance(response, dict)
            or not isinstance(accepted, dict)
            or not isinstance(skill, dict)
            or not isinstance(action, dict)
            or response.get("protocol")
                not in {"RESPONSES", "CHAT_COMPLETIONS"}
            or not isinstance(response.get("httpStatus"), int)
            or not 200 <= response["httpStatus"] <= 299
            or accepted.get("decision") != "START_SKILL"
            or accepted.get("skillName") != expected_skill
            or skill.get("decision") != "START_SKILL"
            or skill.get("skillName") != expected_skill
            or action.get("skillName") != expected_skill
            or action.get("action") != expected_action
        ):
            continue
        return {
            "requestId": request_id,
            "goalRevision": next(iter(revisions)),
            "firstSequence": selected[0]["sequence"],
            "lastSequence": selected[-1]["sequence"],
            "protocol": response["protocol"],
            "httpStatus": response["httpStatus"],
            "providerRequestIdPresent": bool(
                response.get("providerRequestId")
            ),
            "skillName": expected_skill,
            "firstLowLevelAction": expected_action,
        }
    return None


def accepted_task_goal_after_chat(
    events: list[dict[str, Any]],
    chat_at_utc: str,
    after_goal_revision: int | None = None,
    expected_sender_uuid: str | None = None,
    expected_message_sha256: str | None = None,
) -> dict[str, Any] | None:
    """Bind a normal actor chat to its accepted gameplay-goal revision.

    The companion's conversation coordinator emits this content-free event
    only after the server has admitted an immediate player task through
    ``GoalCoordinator``.  When supplied, the sender UUID and SHA-256 must
    match the independently observed normal chat packet; this is stronger
    than associating an arbitrary later event by timestamp alone.
    """
    normalized_sender = (
        expected_sender_uuid.lower()
        if isinstance(expected_sender_uuid, str)
        and expected_sender_uuid
        else None
    )
    normalized_digest = (
        expected_message_sha256.lower()
        if isinstance(expected_message_sha256, str)
        and re.fullmatch(r"[0-9a-f]{64}", expected_message_sha256.lower())
        else None
    )
    accepted: list[dict[str, Any]] = []
    for event in events:
        if event.get("type") != TASK_ACCEPTANCE_AUDIT_TYPE:
            continue
        payload = event.get("payload")
        sender_uuid = (
            payload.get("senderUuid") if isinstance(payload, dict) else None
        )
        message_digest = (
            payload.get("messageSha256")
            if isinstance(payload, dict)
            else None
        )
        intent_code = (
            payload.get("intentCode") if isinstance(payload, dict) else None
        )
        goal_revision = event.get("goalRevision")
        if (
            not isinstance(sender_uuid, str)
            or not isinstance(message_digest, str)
            or not re.fullmatch(r"[0-9a-f]{64}", message_digest)
            or not isinstance(intent_code, str)
            or not isinstance(goal_revision, int)
            or goal_revision < 0
            or (
                normalized_sender is not None
                and sender_uuid.lower() != normalized_sender
            )
            or (
                normalized_digest is not None
                and message_digest != normalized_digest
            )
            or (
                after_goal_revision is not None
                and goal_revision <= after_goal_revision
            )
            or not occurs_at_or_after(event, chat_at_utc)
        ):
            continue
        accepted.append(event)
    if not accepted:
        return None
    selected = min(accepted, key=lambda event: int(event.get("sequence", -1)))
    payload = selected.get("payload", {})
    return {
        "sequence": int(selected.get("sequence", -1)),
        "atUtc": str(selected.get("atUtc", "")),
        "goalRevision": int(selected["goalRevision"]),
        "senderUuid": str(payload.get("senderUuid", "")),
        "messageSha256": str(payload.get("messageSha256", "")),
        "intentCode": str(payload.get("intentCode", "")),
    }


def observer_motion_summary(
    events: list[dict[str, Any]],
    after_utc: str,
    ai_name: str = "MCAI",
) -> dict[str, Any]:
    samples = [
        event["ai"]
        for event in events
        if event.get("type") == "client_world_sample"
        and occurs_at_or_after(event, after_utc)
        and isinstance(event.get("ai"), dict)
        and all(
            isinstance(event["ai"].get(axis), (int, float))
            for axis in ("x", "y", "z")
        )
    ]
    listed_in_tab = any(
        event.get("type") == "client_world_sample"
        and occurs_at_or_after(event, after_utc)
        and isinstance(event.get("relevantTabNames"), list)
        and ai_name in event["relevantTabNames"]
        for event in events
    )
    displacement = 0.0
    maximum_step = 0.0
    if samples:
        first = samples[0]
        previous = first
        for sample in samples[1:]:
            displacement = max(
                displacement,
                math.dist(
                    (first["x"], first["y"], first["z"]),
                    (sample["x"], sample["y"], sample["z"]),
                ),
            )
            maximum_step = max(
                maximum_step,
                math.dist(
                    (
                        previous["x"],
                        previous["y"],
                        previous["z"],
                    ),
                    (sample["x"], sample["y"], sample["z"]),
                ),
            )
            previous = sample
    return {
        "sawAi": bool(samples),
        "listedInTab": listed_in_tab,
        "displacement": displacement,
        "maximumStep": maximum_step,
        "sawMotion": displacement >= 2.0 and maximum_step <= 2.0,
    }


def rendered_png_info(path: Path) -> dict[str, int] | None:
    """Read only the bounded PNG signature/IHDR needed for audit evidence.

    The screenshot itself is produced by the real Minecraft client.  The
    verifier intentionally does not decode or OCR it; it only rejects a
    missing, oversized, truncated, or non-PNG artifact and records its basic
    dimensions for later human review.
    """
    try:
        size = path.stat().st_size
        if size < 24 or size > 20 * 1024 * 1024:
            return None
        with path.open("rb") as stream:
            header = stream.read(24)
    except (OSError, ValueError):
        return None
    if len(header) != 24:
        return None
    if header[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    if header[12:16] != b"IHDR":
        return None
    try:
        width, height = struct.unpack(">II", header[16:24])
    except struct.error:
        return None
    if not (0 < width <= 16_384 and 0 < height <= 16_384):
        return None
    return {"bytes": size, "width": width, "height": height}


def has_ordered_event_chain(
    events: list[dict[str, Any]],
    required_types: tuple[str, ...],
) -> bool:
    """Require one real-client lifecycle chain in monotonically increasing order.

    Event files are append-only evidence emitted by the test clients.  Merely
    finding the individual event names is insufficient: a synthetic bundle
    could otherwise combine a login from one attempt with world samples from
    another.  Invalid/missing sequence values fail closed.
    """
    cursor = -1
    for required_type in required_types:
        match_sequence: int | None = None
        for event in events:
            if event.get("type") != required_type:
                continue
            try:
                sequence = int(event.get("sequence", -1))
            except (TypeError, ValueError):
                continue
            if sequence > cursor:
                match_sequence = sequence
                break
        if match_sequence is None:
            return False
        cursor = match_sequence
    return True


def event_nonce_binding(
    events: list[dict[str, Any]],
    expected_nonce: str | None,
) -> bool:
    """Return true only when every event carries the current run nonce."""
    if not expected_nonce:
        return False
    if not events:
        return False
    return all(event.get("nonce") == expected_nonce for event in events)


def verify_evidence(
    run_root: Path,
    expected_product_hash: str,
    expected_nonce: str | None,
) -> dict[str, Any]:
    actor = read_json_lines(run_root / "actor-client-events.jsonl")
    observer = read_json_lines(
        run_root / "observer-client-events.jsonl"
    )
    oracle = read_json_lines(run_root / "oracle-events.jsonl")
    try:
        oracle_result = json.loads(
            (run_root / "oracle-result.json").read_text(
                encoding="utf-8"
            )
        )
    except (FileNotFoundError, json.JSONDecodeError):
        oracle_result = {}
    chat_events = [
        event
        for event in oracle
        if event.get("type") == "server_chat_received"
    ]
    chat_at_utc = (
        str(chat_events[0].get("atUtc", ""))
        if chat_events
        else ""
    )
    inventory_chat_events = [
        event
        for event in oracle
        if event.get("type") == "inventory_chat_received"
    ]
    inventory_chat_at_utc = (
        str(inventory_chat_events[0].get("atUtc", ""))
        if inventory_chat_events
        else ""
    )
    fixture_item_event = next(
        (
            event
            for event in oracle
            if event.get("type") == "fixture_item_spawned"
        ),
        {},
    )
    movement_delta_event = next(
        (
            event
            for event in oracle
            if event.get("type") == "server_observed_world_delta"
        ),
        {},
    )
    inventory_delta_event = next(
        (
            event
            for event in oracle
            if event.get("type")
                == "server_observed_inventory_delta"
        ),
        {},
    )
    production_audit, production_audit_errors = (
        read_production_audit(run_root)
    )
    task_acceptance_events = [
        event
        for event in production_audit
        if event.get("type") == TASK_ACCEPTANCE_AUDIT_TYPE
        and isinstance(event.get("payload"), dict)
        and isinstance(event["payload"].get("senderUuid"), str)
        and isinstance(event["payload"].get("messageSha256"), str)
    ]
    write_json_lines(
        run_root / "model-audit.jsonl",
        [
            event
            for event in production_audit
            if event.get("type") in MODEL_AUDIT_TYPES
        ],
    )
    write_json_lines(
        run_root / "action-trace.jsonl",
        [
            event
            for event in production_audit
            if event.get("type") == "low_level_actions_issued"
        ],
    )
    write_json_lines(
        run_root / "task-bindings.jsonl",
        task_acceptance_events,
    )
    write_json_lines(
        run_root / "world-events.jsonl",
        [
            event
            for event in oracle
            if event.get("type")
                in {
                    "server_chat_received",
                    "inventory_chat_received",
                    "server_vanilla_item_pickup",
                    "server_world_sample",
                    "server_inventory_sample",
                    "server_observed_world_delta",
                    "server_observed_inventory_delta",
                    "movement_objective_oracle_passed",
                    "inventory_transaction_oracle_passed",
                    "objective_oracle_passed",
                }
        ],
    )

    actor_types = {event.get("type") for event in actor}
    observer_types = {event.get("type") for event in observer}
    oracle_types = {event.get("type") for event in oracle}
    observer_motion = observer_motion_summary(
        observer,
        chat_at_utc,
    )
    observer_saw_ai = bool(observer_motion["sawAi"])
    observer_saw_ai_in_tab = bool(observer_motion["listedInTab"])
    observer_ai_displacement = float(
        observer_motion["displacement"]
    )
    observer_ai_max_step = float(observer_motion["maximumStep"])
    observer_saw_motion = bool(observer_motion["sawMotion"])
    rendered_screenshot_path = (
        run_root / "screenshots" / "observer-rendered.png"
    )
    rendered_screenshot = rendered_png_info(rendered_screenshot_path)
    screenshot_saved_event = next(
        (
            event
            for event in observer
            if event.get("type") == "observer_screenshot_saved"
        ),
        None,
    )
    observer_screenshot_valid = bool(
        rendered_screenshot is not None
        and screenshot_saved_event is not None
        and screenshot_saved_event.get("file")
            == str(rendered_screenshot_path)
        and screenshot_saved_event.get("bytes")
            == rendered_screenshot["bytes"]
        and screenshot_saved_event.get("width")
            == rendered_screenshot["width"]
        and screenshot_saved_event.get("height")
            == rendered_screenshot["height"]
    )
    actor_received_ai_followup = any(
        event.get("type") == "ai_chat_followup_received_by_actor"
        and event.get("actorRole") is True
        and event.get("afterInventoryChat") is False
        and occurs_at_or_after(event, chat_at_utc)
        and (
            not inventory_chat_at_utc
            or occurs_before(event, inventory_chat_at_utc)
        )
        for event in actor
    )
    actor_received_inventory_followup = any(
        event.get("type") == "ai_chat_followup_received_by_actor"
        and event.get("actorRole") is True
        and event.get("afterInventoryChat") is True
        and occurs_at_or_after(event, inventory_chat_at_utc)
        for event in actor
    )
    required_actor = {
        "client_mod_ready",
        "client_connect_started",
        "client_logged_in",
        "scenario_ready_received",
        "actor_chat_sent",
        "actor_follow_arrival_observed",
        "actor_inventory_chat_sent",
    }
    required_observer = {
        "client_mod_ready",
        "client_connect_started",
        "client_logged_in",
        "scenario_ready_received",
        "client_world_sample",
        "observer_screenshot_saved",
    }
    required_oracle = {
        "dedicated_server_started",
        "fixture_setup_complete",
        "fixture_item_spawned",
        "server_chat_received",
        "inventory_chat_received",
        "server_vanilla_item_pickup",
        "server_observed_world_delta",
        "server_inventory_sample",
        "server_observed_inventory_delta",
        "movement_objective_oracle_passed",
        "inventory_transaction_oracle_passed",
        "objective_oracle_passed",
    }
    missing = [
        *(f"actor:{value}" for value in sorted(required_actor - actor_types)),
        *(
            f"observer:{value}"
            for value in sorted(required_observer - observer_types)
        ),
        *(f"oracle:{value}" for value in sorted(required_oracle - oracle_types)),
        *production_audit_errors,
    ]
    if not expected_nonce:
        missing.append("manifest:run-nonce")
    if not event_nonce_binding(actor, expected_nonce):
        missing.append("actor:run-nonce-binding")
    if not event_nonce_binding(observer, expected_nonce):
        missing.append("observer:run-nonce-binding")
    if not event_nonce_binding(oracle, expected_nonce):
        missing.append("oracle:run-nonce-binding")
    if (
        not isinstance(oracle_result.get("nonce"), str)
        or oracle_result.get("nonce") != expected_nonce
    ):
        missing.append("oracle-result:run-nonce-binding")
    if not has_ordered_event_chain(
        actor,
        (
            "client_mod_ready",
            "client_connect_started",
            "client_logged_in",
            "scenario_ready_received",
            "actor_chat_sent",
            "actor_follow_arrival_observed",
            "actor_inventory_chat_sent",
        ),
    ):
        missing.append("actor:ordered-real-client-lifecycle")
    if not has_ordered_event_chain(
        observer,
        (
            "client_mod_ready",
            "client_connect_started",
            "client_logged_in",
            "scenario_ready_received",
            "client_world_sample",
            "observer_screenshot_saved",
        ),
    ):
        missing.append("observer:ordered-real-client-lifecycle")
    if not observer_saw_ai:
        missing.append("observer:visible_ai_sample")
    if not observer_saw_ai_in_tab:
        missing.append("observer:ai_listed_in_tab")
    if not observer_saw_motion:
        missing.append("observer:visible_ai_motion")
    if rendered_screenshot is None:
        missing.append("observer:rendered_screenshot_file")
    if not observer_screenshot_valid:
        missing.append("observer:rendered_screenshot_contract")
    if not actor_received_ai_followup:
        missing.append("actor:ai_chat_followup")
    if not actor_received_inventory_followup:
        missing.append("actor:ai_inventory_chat_followup")
    movement_delta_valid = (
        isinstance(movement_delta_event.get("maxDisplacement"), (int, float))
        and movement_delta_event["maxDisplacement"] >= 2.0
        and isinstance(
            movement_delta_event.get("finalActorDistance"),
            (int, float),
        )
        and movement_delta_event["finalActorDistance"] <= 4.0
        and isinstance(movement_delta_event.get("maxTickStep"), (int, float))
        and movement_delta_event["maxTickStep"] <= 2.0
    )
    if not movement_delta_valid:
        missing.append("oracle:plausible-follow-world-delta")
    fixture_count = fixture_item_event.get("count")
    inventory_initial = inventory_delta_event.get(
        "initialOakLogCount"
    )
    inventory_final = inventory_delta_event.get("finalOakLogCount")
    inventory_delta_valid = (
        fixture_item_event.get("itemId") == "minecraft:oak_log"
        and isinstance(fixture_count, int)
        and fixture_count > 0
        and isinstance(inventory_initial, int)
        and isinstance(inventory_final, int)
        and inventory_final >= inventory_initial + fixture_count
        and inventory_delta_event.get("fixtureDropRemoved") is True
        and inventory_delta_event.get("vanillaPickupObserved") is True
        and isinstance(
            inventory_delta_event.get("vanillaPickupCount"),
            int,
        )
        and inventory_delta_event["vanillaPickupCount"] >= fixture_count
        and isinstance(
            inventory_delta_event.get("maxInventoryDisplacement"),
            (int, float),
        )
        and inventory_delta_event["maxInventoryDisplacement"] >= 2.0
        and isinstance(
            inventory_delta_event.get("maxInventoryTickStep"),
            (int, float),
        )
        and inventory_delta_event["maxInventoryTickStep"] <= 2.0
    )
    if not inventory_delta_valid:
        missing.append("oracle:vanilla-inventory-delta-contract")
    oracle_result_valid = (
        oracle_result.get("status") == "PASS"
        and oracle_result.get("reason")
            == "movement_and_inventory_passed"
        and oracle_result.get("platformBuilt") is True
        and oracle_result.get("setupComplete") is True
        and oracle_result.get("serverChatReceived") is True
        and oracle_result.get("movementPassed") is True
        and oracle_result.get("inventoryChatReceived") is True
        and oracle_result.get("inventoryPassed") is True
        and oracle_result.get("oraclePassed") is True
        and oracle_result.get("evidenceDropped") == 0
        and "evidenceFailure" not in oracle_result
        and oracle_result.get("initialOakLogCount")
            == inventory_initial
        and oracle_result.get("finalOakLogCount")
            == inventory_final
    )
    if not oracle_result_valid:
        missing.append("oracle-result:complete-PASS-contract")
    follow_chat_event = chat_events[0] if chat_events else None
    follow_sender_uuid = (
        follow_chat_event.get("senderUuid")
        if isinstance(follow_chat_event, dict)
        else None
    )
    follow_message_sha256 = normalized_chat_sha256(
        follow_chat_event.get("message")
        if isinstance(follow_chat_event, dict)
        else None
    )
    if chat_events and (
        not isinstance(follow_sender_uuid, str)
        or follow_message_sha256 is None
    ):
        missing.append("oracle:follow-chat-causal-identity")
    follow_task_binding = (
        accepted_task_goal_after_chat(
            production_audit,
            chat_at_utc,
            expected_sender_uuid=follow_sender_uuid,
            expected_message_sha256=follow_message_sha256,
        )
        if chat_events
        and isinstance(follow_sender_uuid, str)
        and follow_message_sha256 is not None
        else None
    )
    if follow_task_binding is None:
        missing.append(
            "production-audit:chat-to-follow-goal-binding"
        )
    causal_trace = (
        causal_model_trace(
            production_audit,
            chat_at_utc,
            "follow_entity",
            "move",
            (
                follow_task_binding["goalRevision"]
                if follow_task_binding is not None
                else None
            ),
        )
        if chat_events and follow_task_binding is not None
        else None
    )
    if causal_trace is None:
        missing.append(
            "production-audit:model-to-follow-to-move-causal-chain"
        )
    transport_events = [
        event
        for event in production_audit
        if event.get("type") == TRANSPORT_AUDIT_TYPE
    ]
    transport_payload = (
        transport_events[-1].get("payload", {})
        if transport_events
        else {}
    )
    transport_audit_valid = (
        isinstance(transport_payload, dict)
        and isinstance(
            transport_payload.get("outboundQueueHighWatermark"),
            int,
        )
        and transport_payload["outboundQueueHighWatermark"] <= 1024
        and transport_payload.get("unreleasedOutboundPackets") == 0
        and transport_payload.get("disconnectHandled") is True
    )
    if not transport_audit_valid:
        missing.append("production-audit:connection-transport-health")
    lifecycle_events = [
        event
        for event in production_audit
        if event.get("type") == RUNTIME_LIFECYCLE_AUDIT_TYPE
    ]
    inventory_chat_event = (
        inventory_chat_events[0] if inventory_chat_events else None
    )
    inventory_sender_uuid = (
        inventory_chat_event.get("senderUuid")
        if isinstance(inventory_chat_event, dict)
        else None
    )
    inventory_message_sha256 = normalized_chat_sha256(
        inventory_chat_event.get("message")
        if isinstance(inventory_chat_event, dict)
        else None
    )
    if inventory_chat_events and (
        not isinstance(inventory_sender_uuid, str)
        or inventory_message_sha256 is None
    ):
        missing.append("oracle:inventory-chat-causal-identity")
    inventory_task_binding = (
        accepted_task_goal_after_chat(
            production_audit,
            inventory_chat_at_utc,
            (
                follow_task_binding["goalRevision"]
                if follow_task_binding is not None
                else None
            ),
            inventory_sender_uuid,
            inventory_message_sha256,
        )
        if inventory_chat_events
        and isinstance(inventory_sender_uuid, str)
        and inventory_message_sha256 is not None
        else None
    )
    if inventory_task_binding is None:
        missing.append(
            "production-audit:chat-to-inventory-goal-binding"
        )
    inventory_causal_trace = (
        causal_model_trace(
            production_audit,
            inventory_chat_at_utc,
            "collect_observed_item",
            "move",
            (
                inventory_task_binding["goalRevision"]
                if inventory_task_binding is not None
                else None
            ),
        )
        if inventory_chat_events and inventory_task_binding is not None
        else None
    )
    if inventory_causal_trace is None:
        missing.append(
            "production-audit:model-to-collect-item-to-move-causal-chain"
        )
    if (
        causal_trace is not None
        and inventory_causal_trace is not None
        and (
            causal_trace["requestId"]
                == inventory_causal_trace["requestId"]
            or inventory_causal_trace["firstSequence"]
                <= causal_trace["lastSequence"]
            or inventory_causal_trace["goalRevision"]
                <= causal_trace["goalRevision"]
        )
    ):
        missing.append(
            "production-audit:distinct-ordered-goal-revisions"
        )

    copied_hashes: dict[str, str] = {}
    for role in ("server", "actor", "observer"):
        candidates = list(
            (run_root / "instances" / role / "mods").glob(
                "mcai_companion-*.jar"
            )
        )
        if len(candidates) != 1:
            missing.append(f"{role}:exact_product_jar")
            continue
        copied_hashes[role] = sha256(candidates[0])
        if copied_hashes[role] != expected_product_hash:
            missing.append(f"{role}:product_hash_match")

    return {
        "schemaVersion": 1,
        "verifiedAtUtc": utc_now(),
        "status": "PASS" if not missing else "FAIL",
        "missingEvidence": missing,
        "actorEventCount": len(actor),
        "observerEventCount": len(observer),
        "oracleEventCount": len(oracle),
        "observerSawAi": observer_saw_ai,
        "observerSawAiInTab": observer_saw_ai_in_tab,
        "observerAiDisplacement": observer_ai_displacement,
        "observerAiMaxStep": observer_ai_max_step,
        "observerSawAiMotion": observer_saw_motion,
        "observerRenderedScreenshot": observer_screenshot_valid,
        "observerScreenshot": rendered_screenshot,
        "actorReceivedAiFollowup": actor_received_ai_followup,
        "actorReceivedInventoryFollowup": (
            actor_received_inventory_followup
        ),
        "oracleResult": oracle_result.get("status"),
        "productionAuditEventCount": len(production_audit),
        "transportAuditEventCount": len(transport_events),
        "transportAuditValid": transport_audit_valid,
        "runtimeLifecycleEventCount": len(lifecycle_events),
        "followTaskGoalBinding": follow_task_binding,
        "inventoryTaskGoalBinding": inventory_task_binding,
        "causalModelTrace": causal_trace,
        "inventoryCausalModelTrace": inventory_causal_trace,
        "expectedProductSha256": expected_product_hash,
        "expectedNonce": expected_nonce,
        "loadedProductCopiesSha256": copied_hashes,
    }


def bounded_text(path: Path, maximum_bytes: int = 32 * 1024 * 1024) -> str:
    if not path.is_file() or path.stat().st_size > maximum_bytes:
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def verify_server_smoke(
    run_root: Path,
    expected_product_hash: str,
    server_exit_code: int | None,
) -> dict[str, Any]:
    oracle = read_json_lines(run_root / "oracle-events.jsonl")
    oracle_types = {event.get("type") for event in oracle}
    ai_login = any(
        event.get("type") == "server_player_logged_in"
        and event.get("playerName") == "MCAI"
        for event in oracle
    )
    server_log = bounded_text(run_root / "server.log")
    latest_log = bounded_text(
        run_root / "instances" / "server" / "logs" / "latest.log"
    )
    combined_log = server_log + "\n" + latest_log
    server_jars = list(
        (run_root / "instances" / "server" / "mods").glob(
            "mcai_companion-*.jar"
        )
    )
    loaded_hash = sha256(server_jars[0]) if len(server_jars) == 1 else None
    memory_databases = list(
        (run_root / "instances" / "server").glob(
            "world-*/data/mcai_companion/memory.db"
        )
    )
    checks = {
        "cleanExit": server_exit_code == 0,
        "dedicatedServerStarted": (
            "dedicated_server_started" in oracle_types
            and "Done (" in combined_log
        ),
        "oracleLoaded": "oracle_mod_ready" in oracle_types,
        "aiServerPlayerJoined": ai_login and "MCAI joined the game" in combined_log,
        "runtimeOpenedMemory": (
            len(memory_databases) == 1
            and "Opened companion runtime" in combined_log
        ),
        "sqliteLoadedOnlyFromProductJarJar": (
            "JarJar Candidated for org.xerial:sqlite-jdbc" in combined_log
            and "reads more than one module named org.xerial.sqlitejdbc"
            not in combined_log
        ),
        "exactProductJar": (
            loaded_hash is not None
            and loaded_hash == expected_product_hash
        ),
        "gracefulLifecycle": (
            "dedicated_server_stopping" in oracle_types
            and "Closed companion runtime" in combined_log
            and "MCAI left the game" in combined_log
        ),
        "noModLoadingFailure": (
            "Error loading mods" not in combined_log
            and "Failure message: Mod" not in combined_log
        ),
    }
    failed = sorted(name for name, passed in checks.items() if not passed)
    return {
        "schemaVersion": 1,
        "verifiedAtUtc": utc_now(),
        "scope": "dedicated_server_exact_jar_lifecycle_only",
        "status": "PASS" if not failed else "FAIL",
        "failedChecks": failed,
        "checks": checks,
        "serverExitCode": server_exit_code,
        "oracleEventCount": len(oracle),
        "expectedProductSha256": expected_product_hash,
        "loadedServerProductSha256": loaded_hash,
        "memoryDatabaseCount": len(memory_databases),
        "functionalAiClaim": False,
    }


def verify_delayed_anchor_evidence(
    run_root: Path,
    expected_product_hash: str,
    expected_nonce: str | None,
) -> dict[str, Any]:
    """Verify the real-client, no-human-first-login lifecycle slice.

    This is intentionally a separate non-model gate.  It proves that the
    production body can be active on a dedicated server before any human
    joins, and that a later ordinary client sees the same AI nearby after the
    normal login/relogin anchor path.  It never promotes a chat/model or
    Hardcore result.
    """
    oracle = read_json_lines(run_root / "oracle-events.jsonl")
    actor = read_json_lines(run_root / "actor-client-events.jsonl")
    observer = read_json_lines(run_root / "observer-client-events.jsonl")
    result_path = run_root / "oracle-result.json"
    try:
        result = json.loads(result_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        result = {}
    oracle_types = [event.get("type") for event in oracle]
    required_oracle = (
        "dedicated_server_started",
        "delayed_anchor_zero_human_active",
        "delayed_anchor_human_login_observed",
        "delayed_anchor_objective_oracle_passed",
    )
    missing: list[str] = []
    if not expected_nonce:
        missing.append("manifest:run-nonce")
    if not event_nonce_binding(oracle, expected_nonce):
        missing.append("oracle:run-nonce-binding")
    if not event_nonce_binding(actor, expected_nonce):
        missing.append("actor:run-nonce-binding")
    if not event_nonce_binding(observer, expected_nonce):
        missing.append("observer:run-nonce-binding")
    if result.get("nonce") != expected_nonce:
        missing.append("oracle-result:run-nonce-binding")
    cursor = -1
    for required in required_oracle:
        try:
            cursor = oracle_types.index(required, cursor + 1)
        except ValueError:
            missing.append(f"oracle:{required}")
    zero_human = next(
        (
            event
            for event in oracle
            if event.get("type") == "delayed_anchor_zero_human_active"
        ),
        {},
    )
    objective = next(
        (
            event
            for event in oracle
            if event.get("type")
            == "delayed_anchor_objective_oracle_passed"
        ),
        {},
    )
    zero_human_ticks = objective.get("zeroHumanTicks")
    if not isinstance(zero_human_ticks, int) or zero_human_ticks < 40:
        missing.append("oracle:at-least-40-zero-human-ticks")
    if result.get("status") != "PASS" or result.get("reason") != (
        "delayed_initial_anchor_passed"
    ):
        missing.append("oracle-result:delayed-anchor-pass")
    if result.get("scenario") != "delayed_first_human_anchor":
        missing.append("oracle-result:scenario-binding")
    if result.get("delayedAnchorPassed") is not True:
        missing.append("oracle-result:delayed-anchor-flag")
    for role, events in (("actor", actor), ("observer", observer)):
        types = {event.get("type") for event in events}
        if "client_logged_in" not in types:
            missing.append(f"{role}:client_logged_in")
        if "scenario_ready_received" not in types:
            missing.append(f"{role}:scenario_ready_received")
        if not any(
            event.get("type") == "client_initial_anchor_observed"
            and event.get("sameDimension") is True
            and isinstance(event.get("distance"), (int, float))
            and event["distance"] <= 12.0
            for event in events
        ):
            missing.append(f"{role}:client_initial_anchor_observed")
        if any(
            event.get("type") in {"actor_chat_sent", "actor_inventory_chat_sent"}
            for event in events
        ):
            missing.append(f"{role}:unexpected_chat_in_anchor_scenario")
    copied_hashes: dict[str, str] = {}
    for role in ("server", "actor", "observer"):
        candidates = list(
            (run_root / "instances" / role / "mods").glob(
                "mcai_companion-*.jar"
            )
        )
        if len(candidates) != 1:
            missing.append(f"{role}:exact_product_jar")
            continue
        copied_hashes[role] = sha256(candidates[0])
        if copied_hashes[role] != expected_product_hash:
            missing.append(f"{role}:product_hash_match")
    return {
        "schemaVersion": 1,
        "verifiedAtUtc": utc_now(),
        "scope": "real_client_delayed_first_human_anchor_non_model",
        "status": "PASS" if not missing else "FAIL",
        "missingEvidence": missing,
        "zeroHumanTicks": zero_human_ticks,
        "oracleResult": result.get("status"),
        "oracleEventCount": len(oracle),
        "actorEventCount": len(actor),
        "observerEventCount": len(observer),
        "expectedProductSha256": expected_product_hash,
        "expectedNonce": expected_nonce,
        "loadedProductCopiesSha256": copied_hashes,
    }


def record_anchor_infrastructure_not_run(
    run_root: Path,
    commands: list[list[str]],
    exception: BaseException,
) -> dict[str, Any]:
    """Close an anchor-smoke run as infrastructure NOT_RUN.

    The server may already have emitted useful no-human lifecycle evidence
    when a client prerequisite is discovered.  Keep that evidence, but make
    the immutable manifest and verdict unambiguously non-gameplay.
    """
    error = {
        "atUtc": utc_now(),
        "type": type(exception).__name__,
        "message": str(exception),
    }
    atomic_json(run_root / "infrastructure-error.json", error)
    manifest_path = run_root / "manifest.json"
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "NOT_RUN"
        manifest["finishedAtUtc"] = utc_now()
        manifest["functionalAiClaim"] = False
        manifest["infrastructureError"] = error
        manifest["commands"] = commands
        atomic_json(manifest_path, manifest)
    verdict = {
        "schemaVersion": 1,
        "verifiedAtUtc": utc_now(),
        "scope": "real_client_delayed_first_human_anchor_non_model",
        "status": "NOT_RUN",
        "reason": "infrastructure_prerequisite_missing",
        "functionalAiClaim": False,
        "infrastructureError": error,
    }
    atomic_json(run_root / "anchor-verdict.json", verdict)
    return error


def gradle_run_command(
    task: str,
    *,
    instance_root: Path,
    run_root: Path,
    port: int,
    nonce: str,
    chat: str,
    inventory_chat: str,
    forge_version: str,
    scenario: str = "chat_follow_inventory",
) -> list[str]:
    return [
        str(ROOT / "gradlew"),
        task,
        "--no-daemon",
        "--no-configuration-cache",
        f"-Pe2e_instance_root={instance_root}",
        f"-Pe2e_run_root={run_root}",
        f"-Pe2e_port={port}",
        f"-Pe2e_server=127.0.0.1:{port}",
        f"-Pe2e_nonce={nonce}",
        f"-Pe2e_actor_name={E2E_ACTOR_NAME}",
        "-Pe2e_observer_name=MCAIObserver",
        "-Pe2e_ai_name=MCAI",
        f"-Pe2e_chat={chat}",
        f"-Pe2e_inventory_chat={inventory_chat}",
        f"-Pe2e_scenario={scenario}",
        f"-Pforge_compile_version={forge_version}",
    ]


def prepare_manifest(
    *,
    run_id: str,
    nonce: str,
    run_root: Path,
    source: dict[str, Any],
    product: Path,
    client: Path,
    oracle: Path,
    environment: dict[str, str],
    command_log: list[list[str]],
    status: str,
    forge_version: str,
) -> dict[str, Any]:
    java_command = "java"
    if environment.get("JAVA_HOME"):
        java_command = str(
            Path(environment["JAVA_HOME"]) / "bin" / "java"
        )
    java_version = command_output([java_command, "-version"])
    manifest = {
        "schemaVersion": 1,
        "runId": run_id,
        "nonce": nonce,
        "createdAtUtc": utc_now(),
        "status": status,
        "evidenceClass": (
            "RELEASE_CANDIDATE"
            if source["releaseEligible"]
            else "NON_RELEASE"
        ),
        "source": source,
        "platform": {
            "os": sys.platform,
            "javaVersion": java_version,
            "minecraft": "26.2",
            "forge": forge_version,
        },
        "artifacts": {
            "productionJar": {
                "name": product.name,
                "sha256": sha256(product),
            },
            "clientTestJar": {
                "name": client.name,
                "sha256": sha256(client),
            },
            "oracleTestJar": {
                "name": oracle.name,
                "sha256": sha256(oracle),
            },
        },
        "model": model_metadata(environment, run_id),
        "commands": command_log,
    }
    atomic_json(run_root / "manifest.json", manifest)
    return manifest


def record_functional_preflight_failure(
    *,
    run_id: str,
    nonce: str,
    run_root: Path,
    source: dict[str, Any],
    environment: dict[str, str],
    forge_version: str,
    preflight: dict[str, Any],
    error: str,
) -> None:
    """Archive a functional attempt that could not launch.

    A preflight failure is infrastructure evidence, never a gameplay verdict.
    Keeping it in the same immutable run layout as a launched attempt makes
    CI and local audits distinguish ``NOT_RUN`` from a missing artifact while
    the model metadata remains secret-safe.
    """
    manifest = {
        "schemaVersion": 1,
        "runId": run_id,
        "nonce": nonce,
        "createdAtUtc": utc_now(),
        "status": "NOT_RUN",
        "evidenceClass": "INFRASTRUCTURE_PRECHECK",
        "scenario": "real_client_chat_follow_inventory",
        "functionalAiClaim": False,
        "source": source,
        "platform": {
            "os": sys.platform,
            "forge": forge_version,
            "minecraft": "26.2",
        },
        "model": model_metadata(environment, run_id),
        "artifacts": {},
        "preflight": preflight,
        "finishedAtUtc": utc_now(),
    }
    atomic_json(run_root / "manifest.json", manifest)
    atomic_json(run_root / "functional-preflight.json", preflight)
    atomic_json(
        run_root / "infrastructure-error.json",
        {
            "schemaVersion": 1,
            "atUtc": utc_now(),
            "type": "FunctionalPreflightError",
            "message": error,
            "preflight": preflight,
        },
    )


def command_prepare(args: argparse.Namespace) -> int:
    environment = java_environment()
    build_environment = without_model_credentials(environment)
    commands: list[list[str]] = []
    source = source_identity()
    nonce = args.nonce or safe_nonce()
    run_id, run_root = create_run(source=source, nonce=nonce)
    try:
        product, client, oracle = build_artifacts(
            build_environment,
            commands,
        )
        hashes = install_exact_product(product, run_root)
        write_instance_files(run_root, args.port or 25575, nonce)
        manifest = prepare_manifest(
            run_id=run_id,
            nonce=nonce,
            run_root=run_root,
            source=source,
            product=product,
            client=client,
            oracle=oracle,
            environment=environment,
            command_log=commands,
            status="PREPARED_NOT_RUN",
            forge_version=args.forge_version,
        )
        manifest["installedProductCopiesSha256"] = hashes
        atomic_json(run_root / "manifest.json", manifest)
        print(run_root)
        return 0
    except Exception as exception:
        atomic_json(
            run_root / "infrastructure-error.json",
            {
                "atUtc": utc_now(),
                "type": type(exception).__name__,
                "message": str(exception),
            },
        )
        # Keep the immutable run discoverable by CI wrappers even when the
        # launcher fails after staging the exact product JAR.
        print(run_root)
        raise


def command_server_smoke(args: argparse.Namespace) -> int:
    environment = java_environment()
    execution_environment = without_model_credentials(environment)
    commands: list[list[str]] = []
    source = source_identity()
    nonce = args.nonce or safe_nonce()
    port = args.port or allocate_loopback_port()
    run_id, run_root = create_run(source=source, nonce=nonce)
    instance_root = run_root / "instances"
    chat = f"MCAI server smoke {nonce}"
    inventory_chat = f"MCAI server smoke inventory {nonce}-ITEM"
    server_process: subprocess.Popen[str] | None = None
    server_output: Any | None = None
    process_exit: dict[str, Any] | None = None

    try:
        product, client, oracle = build_artifacts(
            execution_environment,
            commands,
        )
        installed_hashes = install_exact_product(product, run_root)
        write_instance_files(run_root, port, nonce)
        manifest = prepare_manifest(
            run_id=run_id,
            nonce=nonce,
            run_root=run_root,
            source=source,
            product=product,
            client=client,
            oracle=oracle,
            environment=execution_environment,
            command_log=commands,
            status="RUNNING",
            forge_version=args.forge_version,
        )
        manifest["scenario"] = (
            "dedicated_server_exact_jar_lifecycle_only"
        )
        manifest["installedProductCopiesSha256"] = installed_hashes
        atomic_json(run_root / "manifest.json", manifest)

        server_command = gradle_run_command(
            "runE2eOracleE2eInstalledServer",
            instance_root=instance_root,
            run_root=run_root,
            port=port,
            nonce=nonce,
            chat=chat,
            inventory_chat=inventory_chat,
            forge_version=args.forge_version,
        )
        commands.append(server_command)
        server_process, server_output = start_process(
            server_command,
            environment=execution_environment,
            log_path=run_root / "server.log",
        )
        if not wait_for_text(
            run_root / "server.log",
            "Done (",
            args.startup_timeout,
        ):
            raise RuntimeError("Dedicated server did not become ready")
        if not wait_for_text(
            run_root / "oracle-events.jsonl",
            '"playerName":"MCAI"',
            args.ai_timeout,
        ):
            raise RuntimeError(
                "Headless AI ServerPlayer did not join without humans"
            )

        process_exit = terminate_process(
            "server",
            server_process,
            graceful_input="stop\n",
        )
        server_process = None
        server_output.close()
        server_output = None
        atomic_json(run_root / "process-exits.json", [process_exit])

        verdict = verify_server_smoke(
            run_root,
            manifest["artifacts"]["productionJar"]["sha256"],
            process_exit["exitCode"],
        )
        atomic_json(run_root / "server-smoke-verdict.json", verdict)
        manifest["status"] = verdict["status"]
        manifest["finishedAtUtc"] = utc_now()
        manifest["commands"] = commands
        atomic_json(run_root / "manifest.json", manifest)
        print(run_root)
        return 0 if verdict["status"] == "PASS" else 1
    except Exception as exception:
        atomic_json(
            run_root / "infrastructure-error.json",
            {
                "atUtc": utc_now(),
                "type": type(exception).__name__,
                "message": str(exception),
            },
        )
        # Keep the immutable run discoverable by CI wrappers even when the
        # launcher fails after staging the exact product JAR.
        print(run_root)
        raise
    finally:
        if server_process is not None:
            process_exit = terminate_process(
                "server",
                server_process,
                graceful_input="stop\n",
            )
            atomic_json(run_root / "process-exits.json", [process_exit])
        if server_output is not None:
            server_output.close()


def command_anchor_smoke(args: argparse.Namespace) -> int:
    """Run a real-client delayed-first-human anchor check without a model.

    The server starts with no human process.  Only after the production AI
    body is observed online and a bounded wall-clock delay has elapsed are
    the ordinary Actor and Observer clients launched.  This keeps the
    no-human admission path separate from the model/chat functional gate.
    """
    environment = java_environment()
    execution_environment = without_model_credentials(environment)
    commands: list[list[str]] = []
    source = source_identity()
    nonce = args.nonce or safe_nonce()
    port = args.port or allocate_loopback_port()
    run_id, run_root = create_run(source=source, nonce=nonce)
    instance_root = run_root / "instances"
    anchor_chat = f"no-chat-anchor {nonce}"
    anchor_inventory_chat = f"no-chat-anchor {nonce}-ITEM"
    processes: list[tuple[str, subprocess.Popen[str], Any]] = []
    process_exits: list[dict[str, Any]] = []
    server_process: subprocess.Popen[str] | None = None
    server_output: Any | None = None
    try:
        product, client, oracle = build_artifacts(
            execution_environment,
            commands,
        )
        installed_hashes = install_exact_product(product, run_root)
        write_instance_files(run_root, port, nonce)
        manifest = prepare_manifest(
            run_id=run_id,
            nonce=nonce,
            run_root=run_root,
            source=source,
            product=product,
            client=client,
            oracle=oracle,
            environment=execution_environment,
            command_log=commands,
            status="RUNNING",
            forge_version=args.forge_version,
        )
        manifest["scenario"] = "real_client_delayed_first_human_anchor"
        manifest["functionalAiClaim"] = False
        manifest["zeroHumanDelaySeconds"] = args.zero_human_wait
        manifest["installedProductCopiesSha256"] = installed_hashes
        atomic_json(run_root / "manifest.json", manifest)

        server_command = gradle_run_command(
            "runE2eOracleE2eInstalledServer",
            instance_root=instance_root,
            run_root=run_root,
            port=port,
            nonce=nonce,
            chat=anchor_chat,
            inventory_chat=anchor_inventory_chat,
            forge_version=args.forge_version,
            scenario="delayed_first_human_anchor",
        )
        commands.append(server_command)
        server_process, server_output = start_process(
            server_command,
            environment=execution_environment,
            log_path=run_root / "server.log",
        )
        processes.append(("server", server_process, server_output))
        if not wait_for_text(
            run_root / "server.log",
            "Done (",
            args.startup_timeout,
        ):
            raise RuntimeError("Delayed-anchor server did not become ready")
        if not wait_for_text(
            run_root / "oracle-events.jsonl",
            '"type":"delayed_anchor_zero_human_active"',
            args.ai_timeout,
        ):
            raise RuntimeError(
                "Production AI did not reach zero-human ACTIVE anchor stage"
            )
        if args.zero_human_wait < 2.1:
            raise RuntimeError(
                "--zero-human-wait must be at least 2.1 seconds "
                "(40 server ticks)"
            )
        time.sleep(args.zero_human_wait)

        client_environment = without_model_credentials(environment)
        display_number = args.display
        xvfb = shutil.which("Xvfb")
        if xvfb is None:
            raise RuntimeError(
                "Delayed-anchor real-client smoke requires Xvfb"
            )
        xvfb_command = [
            xvfb,
            f":{display_number}",
            "-screen",
            "0",
            "1280x720x24",
            "-nolisten",
            "tcp",
            "-noreset",
        ]
        commands.append(xvfb_command)
        xvfb_process, xvfb_output = start_process(
            xvfb_command,
            environment=client_environment,
            log_path=run_root / "xvfb.log",
        )
        processes.append(("xvfb", xvfb_process, xvfb_output))
        client_environment["DISPLAY"] = f":{display_number}"
        for role, task in (
            ("observer", "runE2eClientE2eInstalledObserverClient"),
            ("actor", "runE2eClientE2eInstalledActorClient"),
        ):
            command = gradle_run_command(
                task,
                instance_root=instance_root,
                run_root=run_root,
                port=port,
                nonce=nonce,
                chat=anchor_chat,
                inventory_chat=anchor_inventory_chat,
                forge_version=args.forge_version,
                scenario="delayed_first_human_anchor",
            )
            commands.append(command)
            process, output = start_process(
                command,
                environment=client_environment,
                log_path=run_root / f"{role}-client.log",
            )
            processes.append((role, process, output))

        deadline = time.monotonic() + args.timeout
        while time.monotonic() < deadline:
            for name, process, _ in processes:
                if process.poll() not in (None, 0):
                    raise RuntimeError(
                        f"{name} process exited early with "
                        f"{process.returncode}"
                    )
            result_path = run_root / "oracle-result.json"
            if result_path.is_file():
                try:
                    result = json.loads(
                        result_path.read_text(encoding="utf-8")
                    )
                except json.JSONDecodeError:
                    result = {}
                if result.get("status") in {"PASS", "FAIL"}:
                    break
            time.sleep(0.5)
        else:
            raise RuntimeError(
                "Delayed-anchor scenario exceeded its wall timeout"
            )

        for name, process, output in reversed(processes):
            process_exits.append(
                terminate_process(
                    name,
                    process,
                    graceful_input="stop\n" if name == "server" else None,
                )
            )
            output.close()
        processes.clear()
        atomic_json(run_root / "process-exits.json", process_exits)
        verdict = verify_delayed_anchor_evidence(
            run_root,
            manifest["artifacts"]["productionJar"]["sha256"],
            manifest.get("nonce"),
        )
        atomic_json(run_root / "anchor-verdict.json", verdict)
        manifest["status"] = verdict["status"]
        manifest["finishedAtUtc"] = utc_now()
        manifest["commands"] = commands
        atomic_json(run_root / "manifest.json", manifest)
        print(run_root)
        return 0 if verdict["status"] == "PASS" else 1
    except Exception as exception:
        # An infrastructure prerequisite (for example Linux/Xvfb) can fail
        # after the server has already produced useful lifecycle evidence.
        # Never leave the immutable run manifest looking RUNNING: this is a
        # NOT_RUN result, not a gameplay failure and not a partial pass.
        try:
            record_anchor_infrastructure_not_run(
                run_root,
                commands,
                exception,
            )
        except (OSError, TypeError, ValueError, json.JSONDecodeError):
            # Preserve the original infrastructure exception and its exit
            # status even if a disk is full or a partially written manifest
            # cannot be rewritten.
            pass
        print(run_root)
        raise
    finally:
        for name, process, output in reversed(processes):
            process_exits.append(
                terminate_process(
                    name,
                    process,
                    graceful_input="stop\n" if name == "server" else None,
                )
            )
            output.close()
        if process_exits:
            atomic_json(run_root / "process-exits.json", process_exits)


def command_restart_smoke(args: argparse.Namespace) -> int:
    """Run two exact-JAR server boots against one world directory.

    This is lifecycle/persistence evidence only.  It deliberately starts no
    real clients and never claims model gameplay; the formal gate applies the
    release-source check separately.
    """
    environment = java_environment()
    execution_environment = without_model_credentials(environment)
    commands: list[list[str]] = []
    source = source_identity()
    nonce = args.nonce or safe_nonce()
    port = args.port or allocate_loopback_port()
    run_id, run_root = create_run(source=source, nonce=nonce)
    instance_root = run_root / "instances"
    chat = f"MCAI restart smoke {nonce}"
    inventory_chat = f"MCAI restart smoke inventory {nonce}-ITEM"
    process_exits: list[dict[str, Any]] = []
    server_process: subprocess.Popen[str] | None = None
    server_output: Any | None = None
    try:
        product, client, oracle = build_artifacts(
            execution_environment,
            commands,
        )
        installed_hashes = install_exact_product(product, run_root)
        write_instance_files(run_root, port, nonce)
        manifest = prepare_manifest(
            run_id=run_id,
            nonce=nonce,
            run_root=run_root,
            source=source,
            product=product,
            client=client,
            oracle=oracle,
            environment=execution_environment,
            command_log=commands,
            status="RUNNING",
            forge_version=args.forge_version,
        )
        manifest["scenario"] = (
            "dedicated_server_exact_jar_two_boot_restart_lifecycle"
        )
        manifest["functionalAiClaim"] = False
        manifest["installedProductCopiesSha256"] = installed_hashes
        atomic_json(run_root / "manifest.json", manifest)

        for boot in (1, 2):
            server_command = gradle_run_command(
                "runE2eOracleE2eInstalledServer",
                instance_root=instance_root,
                run_root=run_root,
                port=port,
                nonce=nonce,
                chat=chat,
                inventory_chat=inventory_chat,
                forge_version=args.forge_version,
            )
            commands.append(server_command)
            server_process, server_output = start_process(
                server_command,
                environment=execution_environment,
                log_path=run_root / f"server-boot-{boot}.log",
            )
            if not wait_for_text(
                run_root / f"server-boot-{boot}.log",
                "Done (",
                args.startup_timeout,
            ):
                raise RuntimeError(
                    f"Dedicated server boot {boot} did not become ready"
                )
            if boot == 1 and not wait_for_text(
                run_root / f"server-boot-{boot}.log",
                'MCAI joined the game',
                args.ai_timeout,
            ):
                raise RuntimeError(
                    "Headless AI ServerPlayer did not join on first boot"
                )
            process_exits.append(
                terminate_process(
                    f"server-boot-{boot}",
                    server_process,
                    graceful_input="stop\n",
                )
            )
            server_process = None
            server_output.close()
            server_output = None

        atomic_json(run_root / "process-exits.json", process_exits)
        verifier_command = [
            sys.executable,
            str(ROOT / "e2e" / "restart_gate.py"),
            "--run-root",
            str(run_root),
            "--json",
        ]
        # SQLite WAL visibility can lag the launcher process by a short
        # interval after the final JVM closes its virtual-thread writer.  A
        # single immediate read made a real two-boot archive look like it had
        # zero lifecycle rows even though the same immutable DB became valid
        # moments later. Retry the read only; never alter the database or
        # relax missing-evidence failures, and keep the settle window bounded.
        verdict: dict[str, Any] = {
            "status": "FAIL",
            "missingEvidence": ["restart_verifier_invalid_json"],
        }
        # The Forge userdev launcher can leave a short-lived descendant
        # holding SQLite's WAL shared-memory file after Gradle reports the
        # server task complete.  Thirty seconds is still bounded and well
        # below the test's process cleanup timeout, while covering that
        # shutdown tail on slower CI hosts.
        verifier_deadline = time.monotonic() + 30.0
        verifier_attempts = 0
        while True:
            verifier_attempts += 1
            restart_verdict = subprocess.run(
                verifier_command,
                cwd=ROOT,
                env=execution_environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )
            try:
                candidate = json.loads(restart_verdict.stdout)
            except json.JSONDecodeError:
                candidate = {
                    "status": "FAIL",
                    "missingEvidence": ["restart_verifier_invalid_json"],
                }
            if isinstance(candidate, dict):
                verdict = candidate
            if verdict.get("status") == "PASS":
                break
            if time.monotonic() >= verifier_deadline:
                break
            time.sleep(0.25)
        verdict["verifierAttempts"] = verifier_attempts
        atomic_json(run_root / "restart-verdict.json", verdict)
        manifest["status"] = verdict.get("status", "FAIL")
        manifest["finishedAtUtc"] = utc_now()
        manifest["commands"] = commands
        atomic_json(run_root / "manifest.json", manifest)
        print(run_root)
        return 0 if verdict.get("status") == "PASS" else 1
    except Exception as exception:
        atomic_json(
            run_root / "infrastructure-error.json",
            {
                "atUtc": utc_now(),
                "type": type(exception).__name__,
                "message": str(exception),
            },
        )
        print(run_root)
        raise
    finally:
        if server_process is not None:
            process_exits.append(
                terminate_process(
                    "server-restart-cleanup",
                    server_process,
                    graceful_input="stop\n",
                )
            )
            atomic_json(run_root / "process-exits.json", process_exits)
        if server_output is not None:
            server_output.close()


def command_functional(args: argparse.Namespace) -> int:
    environment = java_environment()
    source = source_identity()
    nonce = args.nonce or safe_nonce()
    run_id, run_root = create_run(source=source, nonce=nonce)
    preflight = functional_preflight(environment, args.forge_version)
    if not preflight["ready"]:
        message = (
            "Functional E2E preflight failed; missing: "
            + ", ".join(preflight["missing"])
        )
        record_functional_preflight_failure(
            run_id=run_id,
            nonce=nonce,
            run_root=run_root,
            source=source,
            environment=environment,
            forge_version=args.forge_version,
            preflight=preflight,
            error=message,
        )
        print(run_root)
        return 2
    xvfb = shutil.which("Xvfb")
    if xvfb is None:
        # Keep the type contract obvious even if the host changes between the
        # preflight and process launch.
        message = "Xvfb disappeared after functional preflight"
        record_functional_preflight_failure(
            run_id=run_id,
            nonce=nonce,
            run_root=run_root,
            source=source,
            environment=environment,
            forge_version=args.forge_version,
            preflight={
                **preflight,
                "ready": False,
                "missing": ["Xvfb_disappeared_after_preflight"],
            },
            error=message,
        )
        print(run_root)
        return 2
    client_environment = without_model_credentials(environment)
    commands: list[list[str]] = []
    port = args.port or allocate_loopback_port()
    instance_root = run_root / "instances"
    chat = f"MCAI，请走到我这里并跟着我。任务编号 {nonce}"
    inventory_chat = (
        "MCAI，请把你面前、玩家身后掉落的橡木原木捡进背包。"
        f"任务编号 {nonce}-ITEM"
    )
    processes: list[tuple[str, subprocess.Popen[str], Any]] = []
    display_number = args.display

    try:
        product, client, oracle = build_artifacts(
            client_environment,
            commands,
        )
        installed_hashes = install_exact_product(product, run_root)
        write_instance_files(run_root, port, nonce)
        manifest = prepare_manifest(
            run_id=run_id,
            nonce=nonce,
            run_root=run_root,
            source=source,
            product=product,
            client=client,
            oracle=oracle,
            environment=environment,
            command_log=commands,
            status="RUNNING",
            forge_version=args.forge_version,
        )
        manifest["scenario"] = "real_client_chat_follow_inventory"
        manifest["chatPrincipal"] = {
            "role": "actor",
            "name": E2E_ACTOR_NAME,
            "offlineUuid": offline_player_uuid(E2E_ACTOR_NAME),
            "authorization": "isolated_chat_allowed_senders_non_op",
        }
        manifest["installedProductCopiesSha256"] = installed_hashes
        atomic_json(run_root / "manifest.json", manifest)

        xvfb_command = [
            xvfb,
            f":{display_number}",
            "-screen",
            "0",
            "1280x720x24",
            "-nolisten",
            "tcp",
            "-noreset",
        ]
        commands.append(xvfb_command)
        xvfb_process, xvfb_log = start_process(
            xvfb_command,
            environment=client_environment,
            log_path=run_root / "xvfb.log",
        )
        processes.append(("xvfb", xvfb_process, xvfb_log))
        environment["DISPLAY"] = f":{display_number}"
        client_environment["DISPLAY"] = f":{display_number}"

        server_command = gradle_run_command(
            "runE2eOracleE2eInstalledServer",
            instance_root=instance_root,
            run_root=run_root,
            port=port,
            nonce=nonce,
            chat=chat,
            inventory_chat=inventory_chat,
            forge_version=args.forge_version,
        )
        commands.append(server_command)
        server_process, server_log = start_process(
            server_command,
            environment=environment,
            log_path=run_root / "server.log",
        )
        processes.append(("server", server_process, server_log))
        if not wait_for_text(
            run_root / "server.log",
            "Done (",
            args.startup_timeout,
        ):
            raise RuntimeError("Dedicated server did not become ready")

        for role, task in (
            (
                "observer",
                "runE2eClientE2eInstalledObserverClient",
            ),
            ("actor", "runE2eClientE2eInstalledActorClient"),
        ):
            command = gradle_run_command(
                task,
                instance_root=instance_root,
                run_root=run_root,
                port=port,
                nonce=nonce,
                chat=chat,
                inventory_chat=inventory_chat,
                forge_version=args.forge_version,
            )
            commands.append(command)
            process, output = start_process(
                command,
                environment=client_environment,
                log_path=run_root / f"{role}-client.log",
            )
            processes.append((role, process, output))

        deadline = time.monotonic() + args.timeout
        while time.monotonic() < deadline:
            for name, process, _ in processes:
                if name != "xvfb" and process.poll() not in (None, 0):
                    raise RuntimeError(
                        f"{name} process exited early with "
                        f"{process.returncode}"
                    )
            result_path = run_root / "oracle-result.json"
            if result_path.is_file():
                try:
                    result = json.loads(
                        result_path.read_text(encoding="utf-8")
                    )
                except json.JSONDecodeError:
                    result = {}
                actor_events = read_json_lines(
                    run_root / "actor-client-events.jsonl"
                )
                actor_has_followup = any(
                    event.get("type")
                        == "ai_chat_followup_received_by_actor"
                    and event.get("actorRole") is True
                    and event.get("afterInventoryChat") is False
                    for event in actor_events
                )
                actor_has_inventory_followup = any(
                    event.get("type")
                        == "ai_chat_followup_received_by_actor"
                    and event.get("actorRole") is True
                    and event.get("afterInventoryChat") is True
                    for event in actor_events
                )
                oracle_events = read_json_lines(
                    run_root / "oracle-events.jsonl"
                )
                chat_events = [
                    event
                    for event in oracle_events
                    if event.get("type")
                        == "server_chat_received"
                ]
                observer_motion = observer_motion_summary(
                    read_json_lines(
                        run_root
                        / "observer-client-events.jsonl"
                    ),
                    (
                        str(chat_events[0].get("atUtc", ""))
                        if chat_events
                        else ""
                    ),
                )
                if (
                    result.get("status") in {"PASS", "FAIL"}
                    and (
                        result.get("status") == "FAIL"
                        or (
                            actor_has_followup
                            and actor_has_inventory_followup
                            and observer_motion["sawMotion"]
                        )
                    )
                ):
                    break
            time.sleep(0.5)
        else:
            raise RuntimeError("E2E scenario exceeded its wall timeout")

        exits: list[dict[str, Any]] = []
        for name, process, output in reversed(processes):
            exits.append(
                terminate_process(
                    name,
                    process,
                    graceful_input="stop\n" if name == "server" else None,
                )
            )
            output.close()
        processes.clear()
        atomic_json(run_root / "process-exits.json", exits)

        verdict = verify_evidence(
            run_root,
            manifest["artifacts"]["productionJar"]["sha256"],
            manifest.get("nonce"),
        )
        atomic_json(run_root / "e2e-verdict.json", verdict)
        manifest["status"] = verdict["status"]
        # This field describes only this real-client functional slice.  Keep
        # it false for infrastructure/preflight and non-functional scenarios;
        # formal release promotion still independently requires a clean
        # release source and the gate verifier.
        manifest["functionalAiClaim"] = verdict["status"] == "PASS"
        manifest["finishedAtUtc"] = utc_now()
        manifest["commands"] = commands
        atomic_json(run_root / "manifest.json", manifest)
        print(run_root)
        return 0 if verdict["status"] == "PASS" else 1
    except Exception as exception:
        atomic_json(
            run_root / "infrastructure-error.json",
            {
                "atUtc": utc_now(),
                "type": type(exception).__name__,
                "message": str(exception),
            },
        )
        # Keep the immutable run discoverable by CI wrappers even when the
        # launcher fails after staging the exact product JAR.
        print(run_root)
        raise
    finally:
        exits: list[dict[str, Any]] = []
        for name, process, output in reversed(processes):
            exits.append(
                terminate_process(
                    name,
                    process,
                    graceful_input="stop\n" if name == "server" else None,
                )
            )
            output.close()
        if exits:
            atomic_json(run_root / "process-exits.json", exits)


def command_verify(args: argparse.Namespace) -> int:
    run_root = args.run_root.resolve()
    manifest = json.loads(
        (run_root / "manifest.json").read_text(encoding="utf-8")
    )
    expected = manifest["artifacts"]["productionJar"]["sha256"]
    verdict = verify_evidence(
        run_root,
        expected,
        manifest.get("nonce"),
    )
    atomic_json(run_root / "e2e-verdict.json", verdict)
    print(json.dumps(verdict, ensure_ascii=False, indent=2))
    return 0 if verdict["status"] == "PASS" else 1


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(
        description="Minecraft AI Companion real-client E2E orchestrator"
    )
    subparsers = value.add_subparsers(dest="command", required=True)

    prepare = subparsers.add_parser(
        "prepare",
        help="build and stage an exact-JAR run without launching clients",
    )
    prepare.add_argument("--nonce")
    prepare.add_argument("--port", type=int)
    prepare.add_argument(
        "--forge-version",
        type=forge_version,
        default=FORGE_FLOOR,
    )
    prepare.set_defaults(handler=command_prepare)

    server_smoke = subparsers.add_parser(
        "server-smoke",
        help=(
            "run exact-JAR dedicated-server lifecycle without "
            "claiming functional AI"
        ),
    )
    server_smoke.add_argument("--nonce")
    server_smoke.add_argument("--port", type=int)
    server_smoke.add_argument(
        "--forge-version",
        type=forge_version,
        default=FORGE_FLOOR,
    )
    server_smoke.add_argument(
        "--startup-timeout",
        type=float,
        default=180.0,
    )
    server_smoke.add_argument(
        "--ai-timeout",
        type=float,
        default=60.0,
    )
    server_smoke.set_defaults(handler=command_server_smoke)

    anchor_smoke = subparsers.add_parser(
        "anchor-smoke",
        help=(
            "run a real dedicated server with no humans first, then two "
            "offscreen clients to verify initial body anchoring"
        ),
    )
    anchor_smoke.add_argument("--nonce")
    anchor_smoke.add_argument("--port", type=int)
    anchor_smoke.add_argument(
        "--forge-version",
        type=forge_version,
        default=FORGE_FLOOR,
    )
    anchor_smoke.add_argument("--display", type=int, default=92)
    anchor_smoke.add_argument("--startup-timeout", type=float, default=180.0)
    anchor_smoke.add_argument("--ai-timeout", type=float, default=60.0)
    anchor_smoke.add_argument("--timeout", type=float, default=180.0)
    anchor_smoke.add_argument(
        "--zero-human-wait",
        type=float,
        default=3.0,
        help="seconds to keep the dedicated server human-free after AI ACTIVE",
    )
    anchor_smoke.set_defaults(handler=command_anchor_smoke)

    restart_smoke = subparsers.add_parser(
        "restart-smoke",
        help=(
            "run two exact-JAR server boots against one world directory "
            "without claiming functional AI"
        ),
    )
    restart_smoke.add_argument("--nonce")
    restart_smoke.add_argument("--port", type=int)
    restart_smoke.add_argument(
        "--forge-version",
        type=forge_version,
        default=FORGE_FLOOR,
    )
    restart_smoke.add_argument(
        "--startup-timeout",
        type=float,
        default=180.0,
    )
    restart_smoke.add_argument(
        "--ai-timeout",
        type=float,
        default=60.0,
    )
    restart_smoke.set_defaults(handler=command_restart_smoke)

    preflight = subparsers.add_parser(
        "preflight",
        help=(
            "report real-client functional prerequisites without launching "
            "Minecraft"
        ),
    )
    preflight.add_argument(
        "--forge-version",
        type=forge_version,
        default=FORGE_FLOOR,
    )
    preflight.set_defaults(handler=command_preflight)

    functional = subparsers.add_parser(
        "functional",
        help="run a real dedicated server and two offscreen clients",
    )
    functional.add_argument("--nonce")
    functional.add_argument("--port", type=int)
    functional.add_argument(
        "--forge-version",
        type=forge_version,
        default=FORGE_FLOOR,
    )
    functional.add_argument("--display", type=int, default=91)
    functional.add_argument("--startup-timeout", type=float, default=180.0)
    functional.add_argument("--timeout", type=float, default=240.0)
    functional.set_defaults(handler=command_functional)

    verify = subparsers.add_parser(
        "verify",
        help="re-evaluate an existing evidence directory",
    )
    verify.add_argument("run_root", type=Path)
    verify.set_defaults(handler=command_verify)
    return value


def main() -> int:
    arguments = parser().parse_args()
    try:
        return int(arguments.handler(arguments))
    except KeyboardInterrupt:
        return 130
    except Exception as exception:
        print(
            f"E2E infrastructure error: {type(exception).__name__}: "
            f"{exception}",
            file=sys.stderr,
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
