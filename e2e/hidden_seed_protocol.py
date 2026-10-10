"""Shared, fail-closed protocol checks for hidden-seed evidence.

The evaluator writes one public summary per isolated shard.  This module is
kept independent of Minecraft so the statistical gate can audit those files
without starting a server or learning a private seed.
"""

from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
import re
from typing import Any, Iterable


PROTOCOL = "fresh-hidden-random-seed-hardcore-v2"
SCHEMA_VERSION = 2
ONE_HOUR_TICKS = 60 * 60 * 20
TWO_HOUR_TICKS = 2 * 60 * 60 * 20
SIX_HOUR_TICKS = 6 * 60 * 60 * 20
COMMITMENT_PATTERN = re.compile(r"^[0-9a-f]{64}$")
CASE_ID_PATTERN = re.compile(r"^case-[0-9]{4,}$")
PRODUCT_HASH_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SOURCE_COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")


class HiddenSeedEvidenceError(ValueError):
    """Raised when a public summary cannot be trusted by a gate."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise HiddenSeedEvidenceError(message)


def _integer(value: Any, name: str, *, minimum: int = 0) -> int:
    _require(
        isinstance(value, int) and not isinstance(value, bool) and value >= minimum,
        f"{name} must be an integer >= {minimum}",
    )
    return int(value)


def validate_terminal_result(raw: Any, route: str) -> dict[str, Any]:
    """Validate one server-authored terminal result and return it unchanged."""

    _require(isinstance(raw, dict), "terminal result is not an object")
    required = {
        "schemaVersion",
        "routeProfile",
        "outcome",
        "detailCode",
        "goalRevision",
        "hardcore",
        "evaluationLocked",
        "contaminated",
        "hardcoreDead",
        "foundationVerified",
        "dragonKilled",
        "returnedFromEnd",
        "startedGameTick",
        "finishedGameTick",
        "elapsedTicks",
        "observedGameTick",
    }
    _require(set(raw) == required, "terminal result schema mismatch")
    _require(raw["schemaVersion"] == 2, "terminal schema version mismatch")
    expected_profile = route.upper()
    _require(raw["routeProfile"] == expected_profile, "terminal route mismatch")
    _require(
        isinstance(raw["goalRevision"], int)
        and not isinstance(raw["goalRevision"], bool)
        and raw["goalRevision"] >= 0,
        "goalRevision must be a non-negative integer",
    )
    start = _integer(raw["startedGameTick"], "startedGameTick")
    finish = _integer(raw["finishedGameTick"], "finishedGameTick")
    elapsed = _integer(raw["elapsedTicks"], "elapsedTicks")
    _require(finish >= start, "finishedGameTick precedes start")
    _require(elapsed == finish - start, "elapsedTicks is inconsistent")
    _require(
        isinstance(raw["observedGameTick"], int)
        and not isinstance(raw["observedGameTick"], bool)
        and raw["observedGameTick"] >= finish,
        "observedGameTick must cover terminal result",
    )
    for key in ("hardcore", "evaluationLocked"):
        _require(raw[key] is True, f"{key} must be true")
    _require(raw["contaminated"] is False, "terminal result is contaminated")
    _require(isinstance(raw["hardcoreDead"], bool), "hardcoreDead must be boolean")
    for key in ("foundationVerified", "dragonKilled", "returnedFromEnd"):
        _require(isinstance(raw[key], bool), f"{key} must be boolean")
    _require(isinstance(raw["outcome"], str), "outcome must be text")
    if raw["outcome"] == "COMPLETED":
        _require(raw["hardcoreDead"] is False, "completed result is a death")
        if route == "completion":
            _require(raw["dragonKilled"] is True, "dragon victory is absent")
            _require(raw["returnedFromEnd"] is True, "return portal evidence is absent")
        else:
            _require(raw["foundationVerified"] is True, "foundation evidence is absent")
    return raw


def _reject_private_seed_fields(value: Any, path: str = "summary") -> None:
    """Reject raw seed fields while allowing the public commitment field."""

    if isinstance(value, dict):
        for key, child in value.items():
            lowered = str(key).lower().replace("_", "-")
            if lowered in {
                "seed",
                "raw-seed",
                "seed-value",
                "seed-number",
                "private-seeds",
                "salt",
                "salt-hex",
            }:
                raise HiddenSeedEvidenceError(
                    f"private seed material appears at {path}.{key}"
                )
            _reject_private_seed_fields(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _reject_private_seed_fields(child, f"{path}[{index}]")


def validate_public_summary(
    raw: Any,
    route: str,
    *,
    source_name: str = "summary",
) -> dict[str, Any]:
    """Validate one public shard summary and recompute every counter."""

    _require(route in {"completion", "foundation"}, "unsupported route")
    _require(isinstance(raw, dict), f"{source_name}: summary is not an object")
    _reject_private_seed_fields(raw)
    _require(raw.get("schemaVersion") == SCHEMA_VERSION, f"{source_name}: schema version")
    _require(raw.get("protocol") == PROTOCOL, f"{source_name}: protocol")
    _require(raw.get("route") == route, f"{source_name}: route")
    binding = raw.get("artifactBinding")
    _require(isinstance(binding, dict), f"{source_name}: artifact binding is missing")
    _require(set(binding) == {"productSha256", "sourceCommit"}, f"{source_name}: artifact binding schema")
    product_hash = binding.get("productSha256")
    source_commit = binding.get("sourceCommit")
    _require(
        isinstance(product_hash, str) and PRODUCT_HASH_PATTERN.fullmatch(product_hash.lower()),
        f"{source_name}: productSha256",
    )
    _require(
        isinstance(source_commit, str) and SOURCE_COMMIT_PATTERN.fullmatch(source_commit.lower()),
        f"{source_name}: sourceCommit",
    )
    cases = _integer(raw.get("cases"), f"{source_name}.cases", minimum=1)
    results = raw.get("results")
    _require(isinstance(results, list), f"{source_name}.results must be a list")
    _require(len(results) == cases, f"{source_name}: cases/result count mismatch")

    case_ids: set[str] = set()
    commitments: set[str] = set()
    completed = within_one = within_two = within_six = 0
    for index, result in enumerate(results):
        label = f"{source_name}.results[{index}]"
        _require(isinstance(result, dict), f"{label} is not an object")
        _require("caseDir" not in result, f"{label} leaks caseDir")
        case_id = result.get("caseId")
        commitment = result.get("seedCommitment")
        _require(isinstance(case_id, str) and CASE_ID_PATTERN.fullmatch(case_id), f"{label}.caseId")
        _require(case_id not in case_ids, f"duplicate caseId: {case_id}")
        case_ids.add(case_id)
        _require(isinstance(commitment, str) and COMMITMENT_PATTERN.fullmatch(commitment), f"{label}.seedCommitment")
        _require(commitment not in commitments, f"duplicate seed commitment: {commitment}")
        commitments.add(commitment)
        _require(result.get("phase") == "terminal", f"{label}: case did not reach terminal")
        _require("harnessFailure" not in result, f"{label}: harness failure present")
        terminal = validate_terminal_result(result.get("terminal"), route)
        if terminal["outcome"] == "COMPLETED":
            completed += 1
            elapsed = int(terminal["elapsedTicks"])
            within_one += elapsed <= ONE_HOUR_TICKS
            within_two += elapsed <= TWO_HOUR_TICKS
            within_six += elapsed <= SIX_HOUR_TICKS

    expected = {
        "terminalCases": cases,
        "completedCases": completed,
        "completedWithinOneHour": within_one,
        "completedWithinTwoHours": within_two,
        "completedWithinSixHours": within_six,
    }
    for key, value in expected.items():
        _require(raw.get(key) == value, f"{source_name}.{key} is not recomputed")
    for key, denominator in (
        ("oneHourRate", cases),
        ("twoHourRate", cases),
        ("sixHourRate", cases),
    ):
        actual = raw.get(key)
        expected_rate = expected[
            {"oneHourRate": "completedWithinOneHour", "twoHourRate": "completedWithinTwoHours", "sixHourRate": "completedWithinSixHours"}[key]
        ] / denominator
        _require(isinstance(actual, (int, float)) and not isinstance(actual, bool), f"{source_name}.{key}")
        _require(abs(float(actual) - expected_rate) <= 1e-12, f"{source_name}.{key} is not recomputed")
    return {
        "cases": cases,
        "completedCases": completed,
        "completedWithinOneHour": within_one,
        "completedWithinTwoHours": within_two,
        "completedWithinSixHours": within_six,
        "caseIds": case_ids,
        "seedCommitments": commitments,
        "productSha256": product_hash.lower(),
        "sourceCommit": source_commit.lower(),
    }


def _ceil_fraction(total: int, numerator: int, denominator: int) -> int:
    return math.ceil(total * numerator / denominator)


def aggregate_summaries(
    paths: Iterable[Path],
    route: str,
    *,
    minimum_cases: int,
    expected_product_sha256: str | None = None,
    expected_source_commit: str | None = None,
) -> dict[str, Any]:
    """Aggregate disjoint, fully executed shard summaries fail-closed."""

    source_paths = [Path(path) for path in paths]
    _require(source_paths, "at least one summary is required")
    _require(minimum_cases > 0, "minimum_cases must be positive")
    shards: list[dict[str, Any]] = []
    all_cases: set[str] = set()
    all_commitments: set[str] = set()
    product_hashes: set[str] = set()
    source_commits: set[str] = set()
    for path in source_paths:
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exception:
            raise HiddenSeedEvidenceError(f"cannot read {path}: {exception}") from exception
        checked = validate_public_summary(raw, route, source_name=path.name)
        if all_cases.intersection(checked["caseIds"]):
            raise HiddenSeedEvidenceError(f"duplicate case IDs across shards: {path.name}")
        if all_commitments.intersection(checked["seedCommitments"]):
            raise HiddenSeedEvidenceError(f"duplicate seed commitments across shards: {path.name}")
        all_cases.update(checked["caseIds"])
        all_commitments.update(checked["seedCommitments"])
        product_hashes.add(checked["productSha256"])
        source_commits.add(checked["sourceCommit"])
        shards.append(checked)
    if len(product_hashes) != 1:
        raise HiddenSeedEvidenceError("product SHA-256 differs across shards")
    if len(source_commits) != 1:
        raise HiddenSeedEvidenceError("source commit differs across shards")
    bound_product = next(iter(product_hashes))
    bound_commit = next(iter(source_commits))
    if expected_product_sha256 is not None:
        _require(
            PRODUCT_HASH_PATTERN.fullmatch(expected_product_sha256.lower())
            and bound_product == expected_product_sha256.lower(),
            "product SHA-256 does not match the expected artifact",
        )
    if expected_source_commit is not None:
        _require(
            SOURCE_COMMIT_PATTERN.fullmatch(expected_source_commit.lower())
            and bound_commit == expected_source_commit.lower(),
            "source commit does not match the expected release",
        )

    total = sum(shard["cases"] for shard in shards)
    completed = sum(shard["completedCases"] for shard in shards)
    within_one = sum(shard["completedWithinOneHour"] for shard in shards)
    within_two = sum(shard["completedWithinTwoHours"] for shard in shards)
    within_six = sum(shard["completedWithinSixHours"] for shard in shards)
    if route == "foundation":
        required_one = _ceil_fraction(total, 95, 100)
        required_two = required_six = 0
        pass_gate = total >= minimum_cases and within_one >= required_one
        profile = "M1_FOUNDATION"
    elif minimum_cases >= 1000:
        required_one = 0
        required_two = _ceil_fraction(total, 95, 100)
        required_six = _ceil_fraction(total, 99, 100)
        pass_gate = total >= minimum_cases and within_two >= required_two and within_six >= required_six
        profile = "M4_COMPLETION"
    else:
        required_one = 0
        required_two = 0
        required_six = _ceil_fraction(total, 90, 100)
        pass_gate = total >= minimum_cases and within_six >= required_six
        profile = "M2_COMPLETION"
    return {
        "schemaVersion": 1,
        "protocol": PROTOCOL,
        "route": route,
        "profile": profile,
        "status": "PASS" if pass_gate else "FAIL",
        "shardCount": len(shards),
        "cases": total,
        "terminalCases": total,
        "completedCases": completed,
        "completedWithinOneHour": within_one,
        "completedWithinTwoHours": within_two,
        "completedWithinSixHours": within_six,
        "oneHourRate": within_one / total,
        "twoHourRate": within_two / total,
        "sixHourRate": within_six / total,
        "minimumCases": minimum_cases,
        "requiredWithinOneHour": required_one,
        "requiredWithinTwoHours": required_two,
        "requiredWithinSixHours": required_six,
        "uniqueCaseCount": len(all_cases),
        "uniqueSeedCommitmentCount": len(all_commitments),
        "artifactBinding": {
            "productSha256": bound_product,
            "sourceCommit": bound_commit,
        },
        "shards": [
            {
                "path": path.name,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "cases": checked["cases"],
            }
            for path, checked in zip(source_paths, shards)
        ],
        "claimBoundary": (
            "Only fully executed, independently committed Hardcore summaries "
            "are counted; raw seeds, preparation-only cases, fixtures, and "
            "controlled GameTests are not accepted."
        ),
    }
