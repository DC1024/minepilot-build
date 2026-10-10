"""Fail-closed public evidence protocol for the M3 companion gate.

M3 is not a single survival statistic.  It combines real multiplayer
conversation, construction, mechanism generalisation, restart/chunk unload
recovery, and long-running memory pressure.  The evaluator writes one public
summary per isolated worker; this module validates and aggregates those
summaries without reading a world seed, world path, player identity, prompt,
or credential.

The protocol is deliberately stricter than the development GameTests.  A
summary is useful evidence only when every case was exercised through a real
dedicated server, a normal client connection, and the configured model.  A
fixture or a deterministic local model therefore cannot satisfy this gate.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
from typing import Any, Iterable


PROTOCOL = "real-m3-companion-v1"
SCHEMA_VERSION = 1
CASE_ID_PATTERN = re.compile(r"^m3-case-[0-9]{4,}$")
PRODUCT_HASH_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SOURCE_COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")

MIN_COMPANION_CASES = 50
MIN_BUILDING_CASES = 30
MIN_VARIANTS_PER_CAPABILITY = 3
MIN_SOAK_HOURS = 100
MIN_WAYPOINTS = 10_000
MIN_ASSETS = 100_000
MAX_QUERY_P95_MILLIS = 50.0
MAX_ROUTE_P95_MILLIS = 100.0

# These names are capability labels, not fixed build blueprints.  The
# evaluator may report a material/version-specific implementation behind each
# label, but every label must be observed in three distinct unseen variants.
REQUIRED_FARM_CAPABILITIES = frozenset({
    "wheat",
    "carrot",
    "potato",
    "beetroot",
    "tree_replanting",
    "sugar_cane",
    "bamboo",
    "cactus",
    "kelp",
    "pumpkin_melon",
    "mushroom",
    "flower_dye",
    "animal_breeding",
    "wool",
    "honey",
    "dripstone_lava",
    "cobblestone",
    "stone",
    "basalt",
    "iron",
    "hostile_mob",
    "slime",
    "gold",
    "raid",
    "guardian",
    "wither_skeleton",
    "enderman",
    "piglin_barter",
    "villager_breeding",
    "trading_hall",
    "experience",
})

REQUIRED_MACHINE_CAPABILITIES = frozenset({
    "item_sorter",
    "bulk_storage",
    "overflow_protection",
    "furnace_array",
    "fuel_distribution",
    "minecart_loader",
    "automatic_brewing",
    "crop_harvest_recovery",
    "redstone_pulse_clock_counter",
    "piston_door",
    "vertical_transport",
    "item_waterway",
    "shulker_box_loader",
    "transport_hub",
    "nether_coordinate_transport",
    "automatic_restock",
    "fault_alarm_safe_shutdown",
})


class M3EvidenceError(ValueError):
    """Raised when a public M3 summary is not independently auditable."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise M3EvidenceError(message)


def _integer(value: Any, name: str, *, minimum: int = 0) -> int:
    _require(
        isinstance(value, int)
        and not isinstance(value, bool)
        and value >= minimum,
        f"{name} must be an integer >= {minimum}",
    )
    return int(value)


def _number(value: Any, name: str, *, minimum: float = 0.0) -> float:
    _require(
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and float(value) >= minimum,
        f"{name} must be a number >= {minimum}",
    )
    return float(value)


def _reject_private_fields(value: Any, path: str = "summary") -> None:
    """Reject data that could leak secrets or private world/player state."""

    if isinstance(value, dict):
        for key, child in value.items():
            lowered = str(key).lower().replace("_", "-")
            if lowered in {
                "api-key",
                "apikey",
                "authorization",
                "access-token",
                "refresh-token",
                "password",
                "prompt",
                "system-prompt",
                "world-path",
                "case-dir",
                "player-name",
                "player-uuid",
                "raw-seed",
                "seed",
                "seed-value",
                "private-salt",
            }:
                raise M3EvidenceError(
                    f"private material appears at {path}.{key}"
                )
            _reject_private_fields(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _reject_private_fields(child, f"{path}[{index}]")


def _artifact_binding(raw: dict[str, Any], source_name: str) -> tuple[str, str]:
    binding = raw.get("artifactBinding")
    _require(
        isinstance(binding, dict),
        f"{source_name}: artifactBinding is missing",
    )
    _require(
        set(binding) == {"productSha256", "sourceCommit"},
        f"{source_name}: artifactBinding schema mismatch",
    )
    product = binding.get("productSha256")
    commit = binding.get("sourceCommit")
    _require(
        isinstance(product, str)
        and PRODUCT_HASH_PATTERN.fullmatch(product.lower()),
        f"{source_name}: invalid productSha256",
    )
    _require(
        isinstance(commit, str)
        and SOURCE_COMMIT_PATTERN.fullmatch(commit.lower()),
        f"{source_name}: invalid sourceCommit",
    )
    return product.lower(), commit.lower()


def _require_real_case(case: Any, index: int, source_name: str) -> dict[str, Any]:
    label = f"{source_name}.cases[{index}]"
    _require(isinstance(case, dict), f"{label} is not an object")
    _require(
        set(case) == {
            "caseId",
            "category",
            "capability",
            "variantKey",
            "unseenVariant",
            "naturalLanguage",
            "status",
            "realDedicatedServer",
            "realClient",
            "realModel",
            "observerVerified",
            "noHumanIntervention",
            "noCheatCommands",
            "noDirectMutation",
            "restartVerified",
            "chunkUnloadVerified",
            "playerInterruptionVerified",
        },
        f"{label} schema mismatch",
    )
    case_id = case.get("caseId")
    _require(
        isinstance(case_id, str) and CASE_ID_PATTERN.fullmatch(case_id),
        f"{label}.caseId is invalid",
    )
    category = case.get("category")
    _require(
        category in {"companion", "building", "farm", "machine"},
        f"{label}.category is invalid",
    )
    capability = case.get("capability")
    _require(
        isinstance(capability, str) and capability.strip(),
        f"{label}.capability is required",
    )
    variant = case.get("variantKey")
    _require(
        isinstance(variant, str) and variant.strip(),
        f"{label}.variantKey is required",
    )
    _require(case.get("unseenVariant") is True, f"{label} is not unseen")
    _require(
        case.get("naturalLanguage") is True,
        f"{label} is not a natural-language task",
    )
    _require(case.get("status") == "PASS", f"{label} did not pass")
    for key in (
        "realDedicatedServer",
        "realClient",
        "realModel",
        "observerVerified",
        "noHumanIntervention",
        "noCheatCommands",
        "noDirectMutation",
        "restartVerified",
        "chunkUnloadVerified",
        "playerInterruptionVerified",
    ):
        _require(case.get(key) is True, f"{label}.{key} is not true")
    if category == "farm":
        _require(
            capability in REQUIRED_FARM_CAPABILITIES,
            f"{label}: unknown farm capability",
        )
    if category == "machine":
        _require(
            capability in REQUIRED_MACHINE_CAPABILITIES,
            f"{label}: unknown machine capability",
        )
    return {
        "caseId": case_id,
        "category": category,
        "capability": capability,
        "variantKey": variant,
    }


def validate_summary(raw: Any, *, source_name: str = "summary") -> dict[str, Any]:
    """Validate one public M3 shard and return the recomputed counters."""

    _require(isinstance(raw, dict), f"{source_name}: summary is not an object")
    _reject_private_fields(raw)
    _require(
        raw.get("schemaVersion") == SCHEMA_VERSION,
        f"{source_name}: schemaVersion",
    )
    _require(raw.get("protocol") == PROTOCOL, f"{source_name}: protocol")
    _require(raw.get("phase") == "terminal", f"{source_name}: not terminal")
    product, commit = _artifact_binding(raw, source_name)
    cases = raw.get("cases")
    _require(isinstance(cases, list) and cases, f"{source_name}.cases is empty")
    case_ids: set[str] = set()
    companion = 0
    building = 0
    farms: dict[str, set[str]] = {}
    machines: dict[str, set[str]] = {}
    for index, case in enumerate(cases):
        checked = _require_real_case(case, index, source_name)
        _require(
            checked["caseId"] not in case_ids,
            f"{source_name}: duplicate caseId {checked['caseId']}",
        )
        case_ids.add(checked["caseId"])
        category = checked["category"]
        capability = checked["capability"]
        variant = checked["variantKey"]
        if category == "companion":
            companion += 1
        elif category == "building":
            building += 1
        elif category == "farm":
            farms.setdefault(capability, set()).add(variant)
        elif category == "machine":
            machines.setdefault(capability, set()).add(variant)

    # These are shard-local counters.  Aggregate validation repeats all
    # thresholds after merging, so a worker cannot claim a complete matrix by
    # putting only a partial count in a summary field.
    waypoint_count = _integer(
        raw.get("waypointCount"),
        f"{source_name}.waypointCount",
        minimum=0,
    )
    asset_count = _integer(
        raw.get("assetCount"),
        f"{source_name}.assetCount",
        minimum=0,
    )
    soak_hours = _number(
        raw.get("soakHours"),
        f"{source_name}.soakHours",
    )
    waypoint_p95 = _number(
        raw.get("waypointQueryP95Millis"),
        f"{source_name}.waypointQueryP95Millis",
    )
    asset_p95 = _number(
        raw.get("assetQueryP95Millis"),
        f"{source_name}.assetQueryP95Millis",
    )
    route_p95 = _number(
        raw.get("routeQueryP95Millis"),
        f"{source_name}.routeQueryP95Millis",
    )
    for key in (
        "restartVerified",
        "chunkUnloadVerified",
        "playerInterruptionVerified",
        "memoryStressVerified",
    ):
        _require(raw.get(key) is True, f"{source_name}.{key} is not true")
    return {
        "caseIds": case_ids,
        "productSha256": product,
        "sourceCommit": commit,
        "companionCases": companion,
        "buildingCases": building,
        "farms": farms,
        "machines": machines,
        "waypointCount": waypoint_count,
        "assetCount": asset_count,
        "soakHours": soak_hours,
        "waypointQueryP95Millis": waypoint_p95,
        "assetQueryP95Millis": asset_p95,
        "routeQueryP95Millis": route_p95,
    }


def _merge_sets(
    target: dict[str, set[str]],
    source: dict[str, set[str]],
    *,
    label: str,
) -> None:
    for capability, variants in source.items():
        bucket = target.setdefault(capability, set())
        overlap = bucket.intersection(variants)
        _require(
            not overlap,
            f"duplicate {label} variants for {capability}: {sorted(overlap)}",
        )
        bucket.update(variants)


def aggregate_summaries(
    paths: Iterable[Path],
    *,
    expected_product_sha256: str | None = None,
    expected_source_commit: str | None = None,
) -> dict[str, Any]:
    """Aggregate disjoint M3 shards and apply the complete matrix gate."""

    source_paths = [Path(path) for path in paths]
    _require(source_paths, "at least one M3 summary is required")
    all_ids: set[str] = set()
    products: set[str] = set()
    commits: set[str] = set()
    farms: dict[str, set[str]] = {}
    machines: dict[str, set[str]] = {}
    companion = building = 0
    waypoint_count = asset_count = 0
    soak_hours = 0.0
    waypoint_p95 = asset_p95 = route_p95 = 0.0
    shard_info: list[dict[str, Any]] = []
    for path in source_paths:
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exception:
            raise M3EvidenceError(f"cannot read {path}: {exception}") from exception
        checked = validate_summary(raw, source_name=path.name)
        overlap = all_ids.intersection(checked["caseIds"])
        _require(not overlap, f"duplicate M3 case IDs across shards: {sorted(overlap)}")
        all_ids.update(checked["caseIds"])
        products.add(checked["productSha256"])
        commits.add(checked["sourceCommit"])
        _merge_sets(farms, checked["farms"], label="farm")
        _merge_sets(machines, checked["machines"], label="machine")
        companion += checked["companionCases"]
        building += checked["buildingCases"]
        waypoint_count = max(waypoint_count, checked["waypointCount"])
        asset_count = max(asset_count, checked["assetCount"])
        soak_hours = max(soak_hours, checked["soakHours"])
        waypoint_p95 = max(waypoint_p95, checked["waypointQueryP95Millis"])
        asset_p95 = max(asset_p95, checked["assetQueryP95Millis"])
        route_p95 = max(route_p95, checked["routeQueryP95Millis"])
        shard_info.append({
            "path": path.name,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "cases": len(checked["caseIds"]),
        })
    _require(len(products) == 1, "product SHA-256 differs across M3 shards")
    _require(len(commits) == 1, "source commit differs across M3 shards")
    product = next(iter(products))
    commit = next(iter(commits))
    if expected_product_sha256 is not None:
        _require(
            PRODUCT_HASH_PATTERN.fullmatch(expected_product_sha256.lower())
            and product == expected_product_sha256.lower(),
            "product SHA-256 does not match expected release artifact",
        )
    if expected_source_commit is not None:
        _require(
            SOURCE_COMMIT_PATTERN.fullmatch(expected_source_commit.lower())
            and commit == expected_source_commit.lower(),
            "source commit does not match expected release",
        )
    missing_farms = sorted(
        capability
        for capability in REQUIRED_FARM_CAPABILITIES
        if len(farms.get(capability, set())) < MIN_VARIANTS_PER_CAPABILITY
    )
    missing_machines = sorted(
        capability
        for capability in REQUIRED_MACHINE_CAPABILITIES
        if len(machines.get(capability, set())) < MIN_VARIANTS_PER_CAPABILITY
    )
    thresholds = {
        "companionCases": companion >= MIN_COMPANION_CASES,
        "buildingCases": building >= MIN_BUILDING_CASES,
        "farmVariants": not missing_farms,
        "machineVariants": not missing_machines,
        "soakHours": soak_hours >= MIN_SOAK_HOURS,
        "waypointCount": waypoint_count >= MIN_WAYPOINTS,
        "assetCount": asset_count >= MIN_ASSETS,
        "waypointQueryP95": waypoint_p95 <= MAX_QUERY_P95_MILLIS,
        "assetQueryP95": asset_p95 <= MAX_QUERY_P95_MILLIS,
        "routeQueryP95": route_p95 <= MAX_ROUTE_P95_MILLIS,
    }
    return {
        "schemaVersion": SCHEMA_VERSION,
        "protocol": PROTOCOL,
        "status": "PASS" if all(thresholds.values()) else "FAIL",
        "shardCount": len(source_paths),
        "caseCount": len(all_ids),
        "companionCases": companion,
        "buildingCases": building,
        "farmVariantCounts": {
            capability: len(variants)
            for capability, variants in sorted(farms.items())
        },
        "machineVariantCounts": {
            capability: len(variants)
            for capability, variants in sorted(machines.items())
        },
        "missingFarmCapabilities": missing_farms,
        "missingMachineCapabilities": missing_machines,
        "soakHours": soak_hours,
        "waypointCount": waypoint_count,
        "assetCount": asset_count,
        "waypointQueryP95Millis": waypoint_p95,
        "assetQueryP95Millis": asset_p95,
        "routeQueryP95Millis": route_p95,
        "thresholds": thresholds,
        "artifactBinding": {
            "productSha256": product,
            "sourceCommit": commit,
        },
        "shards": shard_info,
        "claimBoundary": (
            "Only real dedicated-server/client/model summaries with an "
            "observer audit and explicit no-cheat/no-direct-mutation flags "
            "are counted; fixtures, local models and partial matrices are "
            "never promoted to M3 PASS."
        ),
    }
