#!/usr/bin/env python3
"""Validate the checked-in Forge support declaration.

This checker is deliberately a declaration/evidence consistency check.  It
does not turn a compile or a GameTest into the formal M1--M4 gates; those gates
are recorded separately in docs/progress/GOAL_STATE.json.
"""

from __future__ import annotations

import argparse
import ast
import json
import re
import sys
from pathlib import Path


REQUIRED_LINE_FIELDS = {
    "forgeMajor",
    "minecraft",
    "minimumForge",
    "recommendedForge",
    "module",
    "status",
    "publishedPatches",
    "compileVerifiedPatches",
    "runtimeSmokeVerifiedPatches",
    "formalMatrixUnverifiedPatches",
}
VERSION_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")


def _split_toml_items(value: str) -> list[str]:
    """Split a small TOML array/table while respecting nesting and quotes."""
    items: list[str] = []
    start = 0
    depth = 0
    quote = False
    escaped = False
    for index, char in enumerate(value):
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quote = False
            continue
        if char == '"':
            quote = True
        elif char in "[{":
            depth += 1
        elif char in "]}":
            depth -= 1
        elif char == "," and depth == 0:
            item = value[start:index].strip()
            if item:
                items.append(item)
            start = index + 1
    item = value[start:].strip()
    if item:
        items.append(item)
    return items


def _parse_toml_value(value: str):
    value = value.strip()
    if value in ("true", "false"):
        return value == "true"
    if re.fullmatch(r"-?\d+", value):
        return int(value)
    if value.startswith('"') and value.endswith('"'):
        return ast.literal_eval(value)
    if value.startswith("[") and value.endswith("]"):
        return [_parse_toml_value(item) for item in _split_toml_items(value[1:-1])]
    if value.startswith("{") and value.endswith("}"):
        table: dict[str, object] = {}
        for item in _split_toml_items(value[1:-1]):
            key, separator, nested = item.partition("=")
            if not separator:
                raise ValueError(f"invalid inline TOML table item: {item!r}")
            table[key.strip()] = _parse_toml_value(nested)
        return table
    raise ValueError(f"unsupported TOML value: {value!r}")


def _fallback_toml_load(raw: str) -> dict[str, object]:
    """Parse the deliberately small TOML subset used by forge-lines.toml.

    Python 3.11's stdlib ``tomllib`` is preferred.  The fallback keeps the
    repository checker usable on the macOS system Python 3.9 without adding a
    runtime dependency merely for a validation script.
    """
    document: dict[str, object] = {}
    current: dict[str, object] = document
    pending = ""

    def balanced(text: str) -> bool:
        depth = 0
        quote = False
        escaped = False
        for char in text:
            if quote:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    quote = False
            elif char == '"':
                quote = True
            elif char in "[{":
                depth += 1
            elif char in "]}":
                depth -= 1
        return depth == 0 and not quote

    for raw_line in raw.splitlines():
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if pending:
            pending += " " + stripped
        elif stripped.startswith("[[") and stripped.endswith("]]"):
            name = stripped[2:-2].strip()
            values = document.setdefault(name, [])
            if not isinstance(values, list):
                raise ValueError(f"array table collides with scalar: {name}")
            current = {}
            values.append(current)
            continue
        elif stripped.startswith("[") and stripped.endswith("]"):
            path = [part.strip() for part in stripped[1:-1].split(".")]
            current = document
            for part in path:
                nested = current.setdefault(part, {})
                if not isinstance(nested, dict):
                    raise ValueError(f"table collides with scalar: {part}")
                current = nested
            continue
        else:
            pending = stripped
        if not balanced(pending):
            continue
        key, separator, value = pending.partition("=")
        if not separator:
            raise ValueError(f"invalid TOML assignment: {pending!r}")
        current[key.strip()] = _parse_toml_value(value)
        pending = ""
    if pending:
        raise ValueError(f"unterminated TOML value: {pending!r}")
    return document


def load_toml(path: Path) -> dict[str, object]:
    raw = path.read_text(encoding="utf-8")
    try:
        import tomllib
    except ModuleNotFoundError:
        return _fallback_toml_load(raw)
    return tomllib.loads(raw)


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def version_key(value: str) -> tuple[int, int, int]:
    match = VERSION_RE.fullmatch(value)
    if not match:
        raise ValueError(f"invalid semantic Forge patch: {value!r}")
    return tuple(int(part) for part in match.groups())


def validate(path: Path) -> dict[str, object]:
    errors: list[str] = []
    try:
        document = load_toml(path)
    except (OSError, ValueError, SyntaxError) as exc:
        return {"status": "FAIL", "path": str(path), "errors": [str(exc)]}

    if document.get("schemaVersion") != 2:
        fail(errors, "schemaVersion must be 2")
    if "lines" in document:
        fail(errors, "legacy [[lines]] table is not allowed; use [[line]]")
    if not isinstance(document.get("checkedAtUtc"), str):
        fail(errors, "checkedAtUtc is required")
    if document.get("compatChecker") != "scripts/validate-compat.py":
        fail(errors, "compatChecker must point to scripts/validate-compat.py")

    sources = document.get("sources")
    if not isinstance(sources, list) or not sources:
        fail(errors, "sources must be a non-empty array")
    elif any(
        not isinstance(source, str) or not source.startswith("https://")
        for source in sources
    ):
        fail(errors, "all compatibility sources must be HTTPS URLs")

    lines = document.get("line")
    if not isinstance(lines, list) or not lines:
        fail(errors, "compatibility file must contain at least one [[line]]")
        lines = []

    majors: set[int] = set()
    for index, line in enumerate(lines):
        if not isinstance(line, dict):
            fail(errors, f"line[{index}] is not a table")
            continue
        missing = sorted(REQUIRED_LINE_FIELDS - line.keys())
        if missing:
            fail(errors, f"line[{index}] missing required fields: {', '.join(missing)}")
        major = line.get("forgeMajor")
        if not isinstance(major, int) or major < 65:
            fail(errors, f"line[{index}].forgeMajor must be an integer >= 65")
        else:
            if major in majors:
                fail(errors, f"duplicate forgeMajor {major}")
            majors.add(major)
        if line.get("status") != "required":
            fail(errors, f"line[{index}].status must be 'required'")
        module = line.get("module")
        if not isinstance(module, str) or not module:
            fail(errors, f"line[{index}].module must be a non-empty module name")

        published = line.get("publishedPatches")
        if not isinstance(published, list) or not published:
            fail(errors, f"line[{index}].publishedPatches must be a non-empty array")
            published = []
        else:
            try:
                parsed = [version_key(str(value)) for value in published]
            except ValueError as exc:
                fail(errors, str(exc))
                parsed = []
            if parsed != sorted(parsed):
                fail(errors, f"line[{index}].publishedPatches must be sorted")
            if len(set(published)) != len(published):
                fail(errors, f"line[{index}].publishedPatches contains duplicates")

        published_set = set(published)
        for evidence_field in (
            "compileVerifiedPatches",
            "runtimeSmokeVerifiedPatches",
            "formalMatrixUnverifiedPatches",
        ):
            values = line.get(evidence_field)
            if not isinstance(values, list):
                fail(errors, f"line[{index}].{evidence_field} must be an array")
                continue
            unknown = sorted(set(values) - published_set)
            if unknown:
                fail(errors, f"line[{index}].{evidence_field} names unpublished patches: {unknown}")

        smoke = set(line.get("runtimeSmokeVerifiedPatches", []))
        formal_unverified = set(line.get("formalMatrixUnverifiedPatches", []))
        if not smoke <= formal_unverified:
            fail(errors, f"line[{index}].runtimeSmokeVerifiedPatches must remain inside formalMatrixUnverifiedPatches until the formal matrix is complete")

        minimum = line.get("minimumForge")
        recommended = line.get("recommendedForge")
        if isinstance(minimum, str) and isinstance(recommended, str):
            try:
                if version_key(minimum) > version_key(recommended):
                    fail(errors, f"line[{index}].minimumForge is newer than recommendedForge")
            except ValueError as exc:
                fail(errors, str(exc))

    future = document.get("future", {})
    forge66 = future.get("forge66") if isinstance(future, dict) else None
    if not isinstance(forge66, dict) or forge66.get("released") is not False:
        fail(errors, "future.forge66.released must be false until an official Forge 66 release is observed")

    return {
        "status": "PASS" if not errors else "FAIL",
        "path": str(path),
        "schemaVersion": document.get("schemaVersion"),
        "lines": [
            {
                "forgeMajor": line.get("forgeMajor"),
                "module": line.get("module"),
                "status": line.get("status"),
                "verificationStatus": line.get("verificationStatus"),
                "publishedPatches": line.get("publishedPatches", []),
                "runtimeSmokeVerifiedPatches": line.get("runtimeSmokeVerifiedPatches", []),
                "formalMatrixComplete": not bool(line.get("formalMatrixUnverifiedPatches", [])),
            }
            for line in lines
            if isinstance(line, dict)
        ],
        "errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--file",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "compat" / "forge-lines.toml",
        help="compatibility declaration to validate",
    )
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    args = parser.parse_args()
    result = validate(args.file)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    else:
        print(f"{result['status']}: {result['path']}")
        for line in result.get("lines", []):
            print(
                f"  Forge {line['forgeMajor']} ({line['module']}): "
                f"published={len(line['publishedPatches'])}, "
                f"runtimeSmoke={line['runtimeSmokeVerifiedPatches']}, "
                f"formalMatrixComplete={line['formalMatrixComplete']}"
            )
        for error in result.get("errors", []):
            print(f"  ERROR: {error}", file=sys.stderr)
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
