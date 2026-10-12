#!/usr/bin/env python3
"""Prepare hidden-seed evaluation cases for MinePilot autonomous runs.

Every case gets a freshly drawn world seed.  The seed itself is never written to
summary.json: that file only carries a salted SHA-256 commitment, while the raw
seeds stay in private-seeds.json next to it.  --prepare-only materialises the
case directories and manifests and nothing else - no Minecraft server is
started and no model endpoint is contacted, so the run reports executed=false.

Running without --prepare-only is refused.  A real evaluation needs a live
Minecraft server plus a reachable model endpoint, and this script reports that
missing capability instead of emitting a hollow success.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import secrets
import sys
from pathlib import Path


# Hardcore survival settings the evaluation harness requires on every case.
REQUIRED_PROPERTIES = {
    "hardcore": "true",
    "difficulty": "hard",
    "gamemode": "survival",
    "enable-command-block": "false",
}


def render_properties(template: str) -> str:
    """Return the template properties with the required keys applied.

    Keys already present in the template are rewritten in place; keys absent
    from the template are appended, so template-only settings survive.
    """
    lines: list[str] = []
    seen: set[str] = set()
    for raw in template.splitlines():
        key, separator, _ = raw.partition("=")
        if not separator:
            lines.append(raw)
            continue
        name = key.strip()
        seen.add(name)
        if name in REQUIRED_PROPERTIES:
            lines.append(f"{name}={REQUIRED_PROPERTIES[name]}")
        else:
            lines.append(raw)
    for name, value in REQUIRED_PROPERTIES.items():
        if name not in seen:
            lines.append(f"{name}={value}")
    return "\n".join(lines) + "\n"


def seed_commitment(seed: int, salt: str) -> str:
    """Publishable commitment: the seed cannot be recovered from it alone."""
    return hashlib.sha256(f"{seed}:{salt}".encode("utf-8")).hexdigest()


def write_json(path: Path, payload: object) -> None:
    with path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2)
        handle.write("\n")


def prepare(template_dir: Path, output_dir: Path, cases: int) -> int:
    template_file = template_dir / "server.properties"
    if not template_file.is_file():
        print(f"missing template: {template_file}", file=sys.stderr)
        return 2
    properties = render_properties(template_file.read_text(encoding="utf-8"))

    results: list[dict[str, object]] = []
    private: list[dict[str, object]] = []
    for index in range(1, cases + 1):
        case_dir = output_dir / f"case-{index:04d}"
        case_dir.mkdir(parents=True, exist_ok=True)
        (case_dir / "server.properties").write_text(properties, encoding="utf-8")

        seed = secrets.randbits(64)
        salt = secrets.token_hex(16)
        commitment = seed_commitment(seed, salt)
        results.append(
            {
                "index": index,
                "case": case_dir.name,
                "seedCommitment": commitment,
                "commitmentAlgorithm": "sha256(seed:salt)",
            }
        )
        private.append(
            {
                "index": index,
                "case": case_dir.name,
                "seed": seed,
                "salt": salt,
                "seedCommitment": commitment,
            }
        )

    summary = {
        "mode": "prepare-only",
        "executed": False,
        "cases": cases,
        "terminalCases": 0,
        "results": results,
    }
    manifest = {
        "notice": "Private material: seeds are hidden from summary.json.",
        "executed": False,
        "cases": private,
    }

    output_dir.mkdir(parents=True, exist_ok=True)
    write_json(output_dir / "summary.json", summary)
    write_json(output_dir / "private-seeds.json", manifest)

    print(f"prepared {cases} case(s) under {output_dir}")
    print("executed=false: no Minecraft server was started, no model endpoint called")
    print("seeds are in private-seeds.json; only commitments appear in summary.json")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--template-dir",
        type=Path,
        required=True,
        help="directory holding the server.properties template",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        required=True,
        help="directory that receives the generated cases and manifests",
    )
    parser.add_argument(
        "--cases",
        type=int,
        default=2,
        help="number of cases to prepare (default: 2)",
    )
    parser.add_argument(
        "--prepare-only",
        action="store_true",
        help="only create case directories and manifests; do not run anything",
    )
    args = parser.parse_args()

    if args.cases < 1:
        print("--cases must be at least 1", file=sys.stderr)
        return 2

    if not args.prepare_only:
        print(
            "REFUSING: a real hidden-seed evaluation requires a running Minecraft "
            "server and a reachable model endpoint, neither of which is available "
            "here. Re-run with --prepare-only to only materialise case "
            "directories, or invoke the evaluation from an environment that has "
            "both.",
            file=sys.stderr,
        )
        return 1

    return prepare(args.template_dir, args.output_dir, args.cases)


if __name__ == "__main__":
    raise SystemExit(main())
