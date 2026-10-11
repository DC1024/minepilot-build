#!/usr/bin/env python3
"""Fail closed when Forge publishes a major line without an adapter.

The Forge promotions document is intentionally used only for major-line
discovery.  It is not compatibility evidence and it does not promote a
patch-matrix result.  Patch enumeration remains a separate, explicit review
against the official Minecraft-version index.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any
from urllib.request import Request, urlopen


DEFAULT_PROMOTIONS_URL = (
    "https://files.minecraftforge.net/net/minecraftforge/forge/"
    "promotions_slim.json"
)
DEFAULT_INDEX_TEMPLATE = (
    "https://files.minecraftforge.net/net/minecraftforge/forge/"
    "index_{minecraft}.html"
)
VERSION_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
INDEX_VERSION_RE = re.compile(
    r'<td\s+class="download-version"[^>]*>\s*'
    r"(\d+\.\d+\.\d+)(?:\s|<[^>]*>)*</td>",
    re.IGNORECASE,
)


def _load_declaration(path: Path) -> dict[str, Any]:
    raw = path.read_text(encoding="utf-8")
    try:
        import tomllib  # type: ignore[attr-defined]
    except ModuleNotFoundError:
        # The project supports Python 3.9 for its local E2E helpers.  The
        # declaration is deliberately simple, so a small fallback is safer
        # than adding a runtime dependency just for this discovery check.
        majors = [int(value) for value in re.findall(
            r"^forgeMajor\s*=\s*(\d+)\s*$", raw, re.MULTILINE
        )]
        modules = re.findall(
            r'^module\s*=\s*"([^"]+)"\s*$', raw, re.MULTILINE
        )
        minecrafts = re.findall(
            r'^minecraft\s*=\s*"([^"]+)"\s*$', raw, re.MULTILINE
        )
        patch_block = re.search(
            r"^publishedPatches\s*=\s*\[(.*?)\]",
            raw,
            re.MULTILINE | re.DOTALL,
        )
        published = (
            re.findall(r'"(\d+\.\d+\.\d+)"', patch_block.group(1))
            if patch_block is not None
            else []
        )
        return {"line": [
            {
                "forgeMajor": major,
                "module": modules[index] if index < len(modules) else "",
                "minecraft": minecrafts[index] if index < len(minecrafts) else "",
                "publishedPatches": published,
            }
            for index, major in enumerate(majors)
        ]}
    return tomllib.loads(raw)


def _read_bytes(path: Path | None, url: str, accept: str) -> bytes:
    if path is not None:
        return path.read_bytes()
    request = Request(
        url,
        headers={
            "Accept": accept,
            "User-Agent": "mcai-companion-forge-major-discovery/1",
        },
    )
    with urlopen(request, timeout=20) as response:  # nosec B310 - fixed HTTPS default or explicit review URL
        if response.status != 200:
            raise RuntimeError(f"Forge promotions returned HTTP {response.status}")
        return response.read()


def _read_promotions(path: Path | None, url: str) -> dict[str, Any]:
    return json.loads(_read_bytes(path, url, "application/json").decode("utf-8"))


def _read_index(path: Path | None, url: str) -> str:
    return _read_bytes(path, url, "text/html").decode("utf-8")


def _index_versions(html: str, forge_major: int) -> set[str]:
    versions = set()
    for value in INDEX_VERSION_RE.findall(html):
        if int(value.split(".", 1)[0]) == forge_major:
            versions.add(value)
    return versions


def _observed_lines(document: dict[str, Any], minimum_major: int) -> list[dict[str, Any]]:
    promotions = document.get("promos")
    if not isinstance(promotions, dict):
        raise ValueError("Forge promotions document has no object-valued 'promos'")
    observed: dict[tuple[str, int], dict[str, Any]] = {}
    for key, value in promotions.items():
        if not isinstance(key, str) or not isinstance(value, str):
            continue
        minecraft, separator, channel = key.rpartition("-")
        if not separator or channel not in {"latest", "recommended"}:
            continue
        match = VERSION_RE.fullmatch(value)
        if match is None:
            continue
        forge_major = int(match.group(1))
        if forge_major < minimum_major:
            continue
        observed[(minecraft, forge_major)] = {
            "minecraft": minecraft,
            "forgeMajor": forge_major,
            "latest": value if channel == "latest" else None,
            "recommended": value if channel == "recommended" else None,
        }
    # Merge latest/recommended records for the same Minecraft/major pair.
    for key, record in list(observed.items()):
        minecraft, forge_major = key
        for channel in ("latest", "recommended"):
            promotion = promotions.get(f"{minecraft}-{channel}")
            if isinstance(promotion, str) and promotion.split(".", 1)[0].isdigit():
                if int(promotion.split(".", 1)[0]) == forge_major:
                    record[channel] = promotion
    return sorted(observed.values(), key=lambda item: (item["forgeMajor"], item["minecraft"]))


def discover(
    declaration: Path,
    promotions: dict[str, Any],
    minimum_major: int,
    index_documents: dict[str, str] | None = None,
) -> dict[str, Any]:
    document = _load_declaration(declaration)
    lines = document.get("line", [])
    declared: dict[int, dict[str, Any]] = {}
    if isinstance(lines, list):
        for line in lines:
            if not isinstance(line, dict):
                continue
            major = line.get("forgeMajor")
            if isinstance(major, int):
                declared[major] = line
    observed = _observed_lines(promotions, minimum_major)
    missing = [
        record for record in observed
        if record["forgeMajor"] not in declared
        or not str(declared[record["forgeMajor"]].get("module", "")).strip()
    ]
    missing_patches: list[dict[str, Any]] = []
    stale_patches: list[dict[str, Any]] = []
    if index_documents is not None:
        for major, line in declared.items():
            if major < minimum_major or not isinstance(line, dict):
                continue
            minecraft = line.get("minecraft")
            html = index_documents.get(str(minecraft))
            if html is None:
                missing_patches.append({
                    "forgeMajor": major,
                    "minecraft": minecraft,
                    "reason": "official index was not fetched",
                })
                continue
            official = _index_versions(html, major)
            declared_patches = {
                str(value) for value in line.get("publishedPatches", [])
                if VERSION_RE.fullmatch(str(value))
            }
            for patch in sorted(official - declared_patches):
                missing_patches.append({
                    "forgeMajor": major,
                    "minecraft": minecraft,
                    "patch": patch,
                })
            for patch in sorted(declared_patches - official):
                stale_patches.append({
                    "forgeMajor": major,
                    "minecraft": minecraft,
                    "patch": patch,
                })
    return {
        "status": "PASS" if not missing and not missing_patches and not stale_patches else "FAIL",
        "declaration": str(declaration),
        "minimumForgeMajor": minimum_major,
        "declaredForgeMajors": sorted(declared),
        "observed": observed,
        "missingAdapters": missing,
        "missingPatches": missing_patches,
        "stalePatches": stale_patches,
        "policy": "A newly promoted Forge major without a checked-in adapter is a release-blocking condition.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--declaration",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "compat" / "forge-lines.toml",
    )
    parser.add_argument("--promotions-file", type=Path)
    parser.add_argument("--promotions-url", default=DEFAULT_PROMOTIONS_URL)
    parser.add_argument(
        "--check-patches",
        action="store_true",
        help="also compare each declared line with its official download index",
    )
    parser.add_argument(
        "--index-file",
        type=Path,
        help="offline HTML index fixture for the declared Minecraft line",
    )
    parser.add_argument("--index-url-template", default=DEFAULT_INDEX_TEMPLATE)
    parser.add_argument("--minimum-major", type=int, default=65)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    try:
        promotions = _read_promotions(args.promotions_file, args.promotions_url)
        index_documents = None
        if args.check_patches:
            if args.index_file is not None:
                index_documents = {"26.2": _read_index(args.index_file, "")}
            else:
                declaration = _load_declaration(args.declaration)
                index_documents = {}
                for line in declaration.get("line", []):
                    if not isinstance(line, dict):
                        continue
                    minecraft = line.get("minecraft")
                    if isinstance(minecraft, str):
                        index_documents[minecraft] = _read_index(
                            None,
                            args.index_url_template.format(minecraft=minecraft),
                        )
        result = discover(
            args.declaration,
            promotions,
            args.minimum_major,
            index_documents,
        )
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
        result = {"status": "ERROR", "error": str(exc)}
    if args.json:
        print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    else:
        print(f"{result['status']}: Forge major discovery")
        if result.get("observed"):
            print(f"  observed={result['observed']}")
        for record in result.get("missingAdapters", []):
            print(f"  ERROR: no adapter for Forge {record['forgeMajor']} / Minecraft {record['minecraft']}", file=sys.stderr)
        for record in result.get("missingPatches", []):
            print(f"  ERROR: official Forge patch is absent from the lock: {record}", file=sys.stderr)
        for record in result.get("stalePatches", []):
            print(f"  ERROR: locked Forge patch is not in the official index: {record}", file=sys.stderr)
        if result.get("error"):
            print(f"  ERROR: {result['error']}", file=sys.stderr)
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
