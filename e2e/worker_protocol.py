"""Provider-neutral, fail-closed worker evidence protocol.

The worker protocol is deliberately independent of AWS/GitHub/any cloud
vendor.  A coordinator hands a worker a JSON job document and receives a JSON
result document plus an immutable artifact directory.  The job contains only
seed commitments and model metadata; raw seeds, credentials, world paths and
player identities are rejected.  The coordinator can therefore verify the
result without trusting the worker's filesystem or its claimed counters.

This module is test-only infrastructure.  It must never be packaged in the
production Mod JAR.
"""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
from typing import Any, Iterable


SCHEMA_VERSION = 1
JOB_KIND = "mcai_worker_job"
RESULT_KIND = "mcai_worker_result"
ALLOWED_STATUSES = {
    "PASS",
    "FAIL",
    "NOT_RUN",
    "BLOCKED_INFRA",
    "BLOCKED_CREDENTIAL",
    "BLOCKED_BUDGET",
}
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
FORGE_RE = re.compile(r"^65\.[0-9]{1,3}\.[0-9]{1,3}$")
SAFE_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
SEED_COMMITMENT_KEYS = {"seedCommitment", "seedCommitments"}
SENSITIVE_KEYS = {
    "apiKey",
    "api_key",
    "apikey",
    "authorization",
    "bearer",
    "password",
    "rawSeed",
    "seed",
    "secret",
    "token",
}
SENSITIVE_TEXT_MARKERS = (
    "MCAI_API_KEY=",
    "MCAI_API_KEY_FILE=",
    "Authorization: Bearer ",
)

# A worker job may describe a future statistical scenario, but the current
# runner has only one implemented real-client slice.  Keep this contract
# explicit so a job labelled M1/M2/M4 cannot silently execute the smaller
# chat-follow-inventory scenario and be counted as the requested workload.
SUPPORTED_WORKER_SCENARIOS = {
    "real_client_chat_follow_inventory": {"caseCount": 1},
}


class WorkerProtocolError(ValueError):
    """A malformed or unverifiable worker document."""


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace(
        "+00:00", "Z"
    )


def canonical_json(value: Any) -> bytes:
    """Return the one canonical representation used for document hashes."""

    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise WorkerProtocolError(message)


def _text(value: Any, name: str, maximum: int = 512) -> str:
    _require(isinstance(value, str), f"{name} must be text")
    value = value.strip()
    _require(0 < len(value) <= maximum, f"{name} has invalid length")
    return value


def _safe_id(value: Any, name: str) -> str:
    text = _text(value, name)
    _require(SAFE_ID_RE.fullmatch(text) is not None, f"{name} is unsafe")
    return text


def _sha(value: Any, name: str) -> str:
    text = _text(value, name, 64).lower()
    _require(SHA256_RE.fullmatch(text) is not None, f"{name} is not SHA-256")
    return text


def _commit(value: Any, name: str) -> str:
    text = _text(value, name, 40).lower()
    _require(COMMIT_RE.fullmatch(text) is not None, f"{name} is not a commit")
    return text


def _walk_sensitive_keys(value: Any, path: str = "document") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            _require(isinstance(key, str), f"{path} has a non-text key")
            normalized = key.replace("-", "_").lower()
            if normalized in SENSITIVE_KEYS:
                raise WorkerProtocolError(f"sensitive field is forbidden: {path}.{key}")
            if "seed" in normalized and key not in SEED_COMMITMENT_KEYS:
                raise WorkerProtocolError(f"raw seed field is forbidden: {path}.{key}")
            _walk_sensitive_keys(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _walk_sensitive_keys(child, f"{path}[{index}]")


def _without_hash(document: dict[str, Any]) -> dict[str, Any]:
    unsigned = dict(document)
    unsigned.pop("manifestSha256", None)
    unsigned.pop("resultSha256", None)
    return unsigned


def manifest_sha256(document: dict[str, Any]) -> str:
    return sha256_bytes(canonical_json(_without_hash(document)))


def _validate_seed_commitments(value: Any) -> None:
    if value is None:
        return
    if isinstance(value, str):
        _sha(value, "seedCommitment")
        return
    _require(isinstance(value, list), "seedCommitments must be a list")
    _require(0 < len(value) <= 100_000, "seedCommitments has invalid size")
    for index, commitment in enumerate(value):
        _sha(commitment, f"seedCommitments[{index}]")


def validate_job_manifest(document: dict[str, Any]) -> dict[str, Any]:
    """Validate and return a shallow copy of a job manifest.

    The returned document is safe to use as an execution contract.  It is
    still not trusted as gameplay evidence until the result artifact hashes
    and the production audit are independently verified.
    """

    _require(isinstance(document, dict), "job manifest must be an object")
    _walk_sensitive_keys(document)
    _require(document.get("schemaVersion") == SCHEMA_VERSION, "unsupported job schema")
    _require(document.get("kind") == JOB_KIND, "wrong job kind")
    _safe_id(document.get("jobId"), "jobId")
    _safe_id(document.get("shardId"), "shardId")
    source = document.get("source")
    _require(isinstance(source, dict), "source metadata is required")
    _commit(source.get("commit"), "source.commit")
    _require(isinstance(source.get("dirty"), bool), "source.dirty must be boolean")
    _text(source.get("label"), "source.label", 128)
    product = document.get("product")
    _require(isinstance(product, dict), "product metadata is required")
    _sha(product.get("sha256"), "product.sha256")
    _safe_id(product.get("jarName"), "product.jarName")
    minecraft = document.get("minecraft")
    _require(isinstance(minecraft, dict), "minecraft metadata is required")
    _require(minecraft.get("version") == "26.2", "unsupported Minecraft version")
    forge = _text(minecraft.get("forgeVersion"), "minecraft.forgeVersion", 32)
    _require(FORGE_RE.fullmatch(forge) is not None, "worker supports Forge 65.x.y only")
    _require(minecraft.get("javaMajor") == 25, "worker requires Java 25")
    model = document.get("model")
    _require(isinstance(model, dict), "model metadata is required")
    _text(model.get("model"), "model.model", 256)
    _text(model.get("baseUrlHost"), "model.baseUrlHost", 256)
    credential_present = model.get("credentialPresent")
    _require(isinstance(credential_present, bool), "model.credentialPresent must be boolean")
    credential_source = model.get("credentialSource")
    _require(credential_source in {"environment", "file", "injected", None}, "invalid credential source")
    _require(
        credential_present and credential_source is not None
        or not credential_present and credential_source is None,
        "model credential presence/source are inconsistent",
    )
    scenario = document.get("scenario")
    _require(isinstance(scenario, dict), "scenario metadata is required")
    _safe_id(scenario.get("id"), "scenario.id")
    case_count = scenario.get("caseCount")
    _require(isinstance(case_count, int) and 1 <= case_count <= 100_000, "invalid scenario.caseCount")
    _validate_seed_commitments(scenario.get("seedCommitments"))
    limits = document.get("limits")
    _require(isinstance(limits, dict), "worker limits are required")
    for key, lower, upper in (
        ("timeoutSeconds", 1, 86_400),
        ("maxWorkerHours", 0.01, 10_000),
        ("maxParallelism", 1, 256),
    ):
        value = limits.get(key)
        _require(isinstance(value, (int, float)) and lower <= value <= upper, f"invalid limits.{key}")
    expected_hash = document.get("manifestSha256")
    _sha(expected_hash, "manifestSha256")
    _require(expected_hash == manifest_sha256(document), "job manifest hash mismatch")
    return dict(document)


def model_binding_errors(
    expected: dict[str, Any],
    actual: dict[str, Any],
) -> list[str]:
    """Return public model-metadata mismatches without exposing values.

    ``credentialSource=injected`` means the coordinator intentionally does
    not prescribe whether the worker's secret manager presents the key as an
    environment variable or a file.  It still requires the worker to report
    that a credential is present.  All other sources are exact declarations.
    """

    _require(isinstance(expected, dict), "expected model metadata must be an object")
    _require(isinstance(actual, dict), "actual model metadata must be an object")
    errors: list[str] = []
    for key in ("model", "baseUrlHost", "credentialPresent"):
        if expected.get(key) != actual.get(key):
            errors.append(key)
    expected_source = expected.get("credentialSource")
    actual_source = actual.get("credentialSource")
    if expected_source == "injected":
        if actual_source not in {"environment", "file"}:
            errors.append("credentialSource")
    elif expected_source != actual_source:
        errors.append("credentialSource")
    return errors


def worker_scenario_errors(scenario: dict[str, Any]) -> list[str]:
    """Return execution-contract errors for the provider-neutral runner.

    The public job schema intentionally accepts future scenario identifiers so
    a coordinator can prepare a shard before a worker image is upgraded.  A
    worker must nevertheless fail closed instead of running a different
    scenario under the requested label.
    """

    _require(isinstance(scenario, dict), "scenario metadata must be an object")
    scenario_id = scenario.get("id")
    case_count = scenario.get("caseCount")
    errors: list[str] = []
    if scenario_id not in SUPPORTED_WORKER_SCENARIOS:
        errors.append("unsupported_scenario")
        return errors
    expected = SUPPORTED_WORKER_SCENARIOS[scenario_id]["caseCount"]
    if case_count != expected:
        errors.append("unsupported_case_count")
    commitments = scenario.get("seedCommitments", [])
    if not isinstance(commitments, list) or len(commitments) != expected:
        errors.append("scenario_commitment_count_mismatch")
    return errors


def build_job_manifest(
    *,
    job_id: str,
    shard_id: str,
    source_commit: str,
    source_dirty: bool,
    source_label: str,
    product_jar_name: str,
    product_sha256: str,
    forge_version: str,
    model_name: str,
    base_url_host: str,
    credential_present: bool,
    credential_source: str | None,
    scenario_id: str,
    case_count: int,
    seed_commitments: Iterable[str] | None,
    timeout_seconds: int,
    max_worker_hours: float,
    max_parallelism: int,
) -> dict[str, Any]:
    document: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "kind": JOB_KIND,
        "jobId": job_id,
        "shardId": shard_id,
        "createdAtUtc": utc_now(),
        "source": {
            "commit": source_commit,
            "dirty": source_dirty,
            "label": source_label,
        },
        "product": {
            "jarName": product_jar_name,
            "sha256": product_sha256,
        },
        "minecraft": {
            "version": "26.2",
            "forgeVersion": forge_version,
            "javaMajor": 25,
        },
        "model": {
            "model": model_name,
            "baseUrlHost": base_url_host,
            "credentialPresent": credential_present,
            "credentialSource": credential_source,
        },
        "scenario": {
            "id": scenario_id,
            "caseCount": case_count,
            "seedCommitments": list(seed_commitments or []),
        },
        "limits": {
            "timeoutSeconds": timeout_seconds,
            "maxWorkerHours": max_worker_hours,
            "maxParallelism": max_parallelism,
        },
    }
    _walk_sensitive_keys(document)
    document["manifestSha256"] = manifest_sha256(document)
    return validate_job_manifest(document)


def artifact_inventory(root: Path, *, exclude: set[str] | None = None) -> dict[str, dict[str, int | str]]:
    """Hash regular files under root without following symlinks."""

    root = root.resolve()
    excluded = exclude or set()
    inventory: dict[str, dict[str, int | str]] = {}
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.is_symlink():
            continue
        relative = path.relative_to(root).as_posix()
        if relative in excluded or relative.endswith(".tmp"):
            continue
        _require(".." not in Path(relative).parts, "artifact path escapes root")
        size = path.stat().st_size
        _require(size <= 2 * 1024 * 1024 * 1024, f"artifact is too large: {relative}")
        inventory[relative] = {"sha256": sha256_file(path), "size": size}
    _require(len(inventory) <= 250_000, "too many worker artifacts")
    return inventory


def _read_artifact_json(root: Path, relative: str) -> dict[str, Any]:
    """Read a bounded, regular JSON artifact from a worker result bundle."""

    candidate = (root.resolve() / relative).resolve()
    resolved_root = root.resolve()
    _require(
        resolved_root == candidate or resolved_root in candidate.parents,
        f"artifact escapes root: {relative}",
    )
    _require(candidate.is_file() and not candidate.is_symlink(),
             f"missing functional artifact: {relative}")
    _require(candidate.stat().st_size <= 8 * 1024 * 1024,
             f"functional artifact is too large: {relative}")
    try:
        value = json.loads(candidate.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exception:
        raise WorkerProtocolError(f"invalid functional artifact: {relative}") from exception
    _require(isinstance(value, dict), f"functional artifact must be an object: {relative}")
    return value


def _validate_functional_run_evidence(
    root: Path,
    job: dict[str, Any],
) -> None:
    """Verify the public evidence contract behind a worker ``PASS``.

    Artifact hashes prove that a bundle was not changed after it was written;
    they do not prove that a worker ran the requested gameplay.  The exact
    functional verifier therefore remains the authority for the causal chain,
    while this check ensures the verifier's result, source/JAR/model binding,
    and independent Oracle evidence are all present in the bundle being
    published.
    """

    run_manifest = _read_artifact_json(root, "run/manifest.json")
    _require(run_manifest.get("status") == "PASS",
             "passing worker result requires a PASS run manifest")
    run_source = run_manifest.get("source")
    _require(isinstance(run_source, dict)
             and run_source.get("commit") == job["source"].get("commit")
             and run_source.get("dirty") == job["source"].get("dirty"),
             "worker run source metadata mismatch")
    platform = run_manifest.get("platform")
    _require(isinstance(platform, dict), "worker run platform metadata is required")
    _require(platform.get("minecraft") == job["minecraft"]["version"],
             "worker run Minecraft version mismatch")
    _require(platform.get("forge") == job["minecraft"]["forgeVersion"],
             "worker run Forge version mismatch")
    artifacts = run_manifest.get("artifacts")
    _require(isinstance(artifacts, dict), "worker run artifacts are required")
    production = artifacts.get("productionJar")
    _require(isinstance(production, dict)
             and production.get("sha256") == job["product"]["sha256"],
             "worker run product JAR binding mismatch")
    _require(
        not model_binding_errors(job["model"], run_manifest.get("model", {})),
        "worker run model binding mismatch",
    )

    verdict = _read_artifact_json(root, "run/e2e-verdict.json")
    _require(verdict.get("status") == "PASS",
             "passing worker result requires a PASS e2e verdict")
    missing = verdict.get("missingEvidence")
    _require(isinstance(missing, list) and not missing,
             "passing e2e verdict contains missing evidence")
    for key in ("causalModelTrace", "inventoryCausalModelTrace"):
        trace = verdict.get(key)
        _require(isinstance(trace, dict),
                 f"passing e2e verdict requires {key}")
        _require(isinstance(trace.get("requestId"), str)
                 and trace["requestId"].startswith("brain-"),
                 f"passing e2e verdict has invalid {key} request")
    _require(verdict.get("expectedProductSha256") == job["product"]["sha256"],
             "e2e verdict product binding mismatch")
    copies = verdict.get("loadedProductCopiesSha256")
    _require(isinstance(copies, dict),
             "e2e verdict product-copy binding is required")
    for role in ("server", "actor", "observer"):
        _require(copies.get(role) == job["product"]["sha256"],
                 f"e2e verdict {role} product copy mismatch")

    oracle = _read_artifact_json(root, "run/oracle-result.json")
    _require(oracle.get("status") == "PASS"
             and oracle.get("oraclePassed") is True,
             "passing worker result requires independent Oracle PASS")
    for relative in (
        "run/model-audit.jsonl",
        "run/action-trace.jsonl",
        "run/world-events.jsonl",
    ):
        candidate = (root.resolve() / relative).resolve()
        _require(candidate.is_file() and not candidate.is_symlink()
                 and candidate.stat().st_size > 0,
                 f"passing worker result requires {relative}")


def build_result_manifest(
    job: dict[str, Any],
    *,
    status: str,
    exit_code: int | None,
    started_at_utc: str,
    finished_at_utc: str,
    root: Path,
    functional_ai_claim: bool,
    reason: str,
) -> dict[str, Any]:
    job = validate_job_manifest(job)
    _require(status in ALLOWED_STATUSES, "invalid worker result status")
    _require(isinstance(exit_code, int) or exit_code is None, "invalid exit code")
    result: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "kind": RESULT_KIND,
        "jobId": job["jobId"],
        "shardId": job["shardId"],
        "jobManifestSha256": job["manifestSha256"],
        "source": job["source"],
        "product": job["product"],
        "minecraft": job["minecraft"],
        "model": job["model"],
        "scenario": {
            "id": job["scenario"]["id"],
            "caseCount": job["scenario"]["caseCount"],
            "seedCommitments": job["scenario"].get("seedCommitments", []),
        },
        "status": status,
        "exitCode": exit_code,
        "startedAtUtc": started_at_utc,
        "finishedAtUtc": finished_at_utc,
        "functionalAiClaim": bool(functional_ai_claim),
        "reason": _text(reason, "reason", 1024),
        "artifacts": artifact_inventory(root, exclude={"worker-result.json"}),
    }
    _walk_sensitive_keys(result)
    result["resultSha256"] = manifest_sha256(result)
    return result


def validate_result_manifest(
    result: dict[str, Any],
    root: Path,
    expected_job: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Verify result provenance and every listed artifact hash."""

    _require(isinstance(result, dict), "worker result must be an object")
    _walk_sensitive_keys(result)
    _require(result.get("schemaVersion") == SCHEMA_VERSION, "unsupported result schema")
    _require(result.get("kind") == RESULT_KIND, "wrong result kind")
    _safe_id(result.get("jobId"), "result.jobId")
    _safe_id(result.get("shardId"), "result.shardId")
    _sha(result.get("jobManifestSha256"), "result.jobManifestSha256")
    _require(result.get("status") in ALLOWED_STATUSES, "invalid result status")
    _require(isinstance(result.get("functionalAiClaim"), bool), "functionalAiClaim must be boolean")
    _require(isinstance(result.get("artifacts"), dict), "result artifacts are required")
    _sha(result.get("resultSha256"), "resultSha256")
    _require(result["resultSha256"] == manifest_sha256(result), "result manifest hash mismatch")
    if expected_job is not None:
        job = validate_job_manifest(expected_job)
        _require(result["jobManifestSha256"] == job["manifestSha256"], "result is bound to a different job")
        _require(result["jobId"] == job["jobId"], "result jobId mismatch")
        _require(result["shardId"] == job["shardId"], "result shardId mismatch")
        for key in ("source", "product", "minecraft", "model"):
            _require(result.get(key) == job.get(key), f"result {key} metadata mismatch")
        _require(result.get("scenario") == {
            "id": job["scenario"]["id"],
            "caseCount": job["scenario"]["caseCount"],
            "seedCommitments": job["scenario"].get("seedCommitments", []),
        }, "result scenario metadata mismatch")
        # A worker result hash authenticates the bundle's integrity, not the
        # truth of a worker's claims.  For any result that claims gameplay
        # evidence, independently recompute the public model binding recorded
        # by the worker preflight.  Blocked infrastructure/credential results
        # may legitimately have an incomplete or mismatched model section.
        if result.get("status") == "PASS" or result.get("functionalAiClaim"):
            _require(
                not worker_scenario_errors(job["scenario"]),
                "passing worker result uses an unimplemented scenario",
            )
            preflight_path = root.resolve() / "functional-preflight.json"
            _require(
                preflight_path.is_file() and not preflight_path.is_symlink(),
                "passing worker result requires functional-preflight.json",
            )
            try:
                preflight = json.loads(preflight_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as exception:
                raise WorkerProtocolError("invalid functional preflight artifact") from exception
            _require(isinstance(preflight, dict), "functional preflight must be an object")
            mismatches = model_binding_errors(
                job["model"],
                preflight.get("model", {}),
            )
            binding = preflight.get("jobModelBinding")
            _require(isinstance(binding, dict), "worker model binding report is required")
            reported_fields = binding.get("mismatchedFields")
            _require(isinstance(reported_fields, list), "worker model binding fields are invalid")
            _require(
                sorted(reported_fields) == sorted(mismatches)
                and binding.get("matches") == (not mismatches),
                "worker model binding report mismatch",
            )
            _require(not mismatches, "passing worker result has an unbound model")
            _require(preflight.get("ready") is True, "passing worker result requires ready preflight")
            _validate_functional_run_evidence(root, job)
    root = root.resolve()
    for relative, metadata in result["artifacts"].items():
        _require(isinstance(relative, str) and relative, "invalid artifact path")
        candidate = (root / relative).resolve()
        _require(root == candidate or root in candidate.parents, f"artifact escapes root: {relative}")
        _require(isinstance(metadata, dict), f"invalid artifact metadata: {relative}")
        _sha(metadata.get("sha256"), f"artifact.sha256:{relative}")
        _require(isinstance(metadata.get("size"), int) and metadata["size"] >= 0, f"invalid artifact size: {relative}")
        _require(candidate.is_file() and not candidate.is_symlink(), f"missing artifact: {relative}")
        _require(candidate.stat().st_size == metadata["size"], f"artifact size mismatch: {relative}")
        _require(sha256_file(candidate) == metadata["sha256"], f"artifact hash mismatch: {relative}")
    return dict(result)


def assert_no_secret_bytes(root: Path, secrets: Iterable[str]) -> None:
    """Fail before publishing a worker bundle if a supplied secret leaked."""

    needles = [secret.encode("utf-8") for secret in secrets if secret]
    if not needles:
        return
    for path in root.rglob("*"):
        if not path.is_file() or path.is_symlink():
            continue
        try:
            data = path.read_bytes()
        except OSError as exception:
            raise WorkerProtocolError(f"cannot audit artifact: {path}") from exception
        if any(needle in data for needle in needles):
            raise WorkerProtocolError(f"credential leaked into artifact: {path.name}")
